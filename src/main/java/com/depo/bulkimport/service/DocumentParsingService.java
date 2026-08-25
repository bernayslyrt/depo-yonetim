package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts uploaded documents into structure-preserving chunks and delegates
 * semantic product extraction to the local Ollama service.
 *
 * <p>No fixed column schema is imposed. Excel and CSV preprocessing preserves
 * row/cell boundaries, while PDF and DOCX text is grouped by logical blocks.</p>
 */
@Service
@Slf4j
public class DocumentParsingService {

    private static final int FALLBACK_HEADER_CONTEXT_ROW_COUNT = 3;
    private static final int MAX_HEADER_CONTEXT_ROW_COUNT = 5;
    private static final int MAX_STRUCTURED_HEADER_SCAN_ROW_COUNT = 50;
    private static final Pattern PDF_NUMBERED_RECORD_LINE = Pattern.compile(
            "^\\s*(\\d{1,4})\\s*(?:[.)\\-:|;]\\s*|\\s+)(?=.*\\p{L}).*?"
                    + "(?:[|;]\\s*|\\s+)(\\d{1,9})(?:\\s*(?:adet|ad\\.?|pcs?))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PDF_NUMBERED_ITEM_MARKER = Pattern.compile(
            "^\\s*(\\d{1,4})\\s*(?:[.)\\-:|;]\\s*|\\s+)(?=.*\\p{L}).*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PDF_BULLET_ITEM_MARKER = Pattern.compile(
            "^\\s*[-*•▪◦]\\s+.*\\p{L}.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PDF_EXPLICIT_ITEM_LABEL = Pattern.compile(
            "^\\s*(?:ürün|urun|malzeme|product|item)\\s+(?:adı|adi|name)\\s*[:|].*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final OllamaParsingService ollamaParsingService;
    private final int rowChunkSize;
    private final int textChunkMaxChars;
    private final int pdfTextChunkMaxChars;
    private final int pdfRecordsPerChunk;
    private final PdfRecordSegmenter pdfRecordSegmenter = new PdfRecordSegmenter();

    @Autowired
    public DocumentParsingService(
            OllamaParsingService ollamaParsingService,
            @Value("${ollama.chunk-size-rows:10}") int rowChunkSize,
            @Value("${ollama.text-chunk-max-chars:12000}") int textChunkMaxChars,
            @Value("${ollama.pdf-text-chunk-max-chars:2000}") int pdfTextChunkMaxChars,
            @Value("${ollama.pdf-records-per-chunk:5}") int pdfRecordsPerChunk) {
        if (rowChunkSize < 1) {
            throw new IllegalArgumentException("ollama.chunk-size-rows en az 1 olmalıdır.");
        }
        if (textChunkMaxChars < 1_000) {
            throw new IllegalArgumentException("ollama.text-chunk-max-chars en az 1000 olmalıdır.");
        }
        if (pdfTextChunkMaxChars < 1_000) {
            throw new IllegalArgumentException("ollama.pdf-text-chunk-max-chars en az 1000 olmalıdır.");
        }
        if (pdfRecordsPerChunk < 1) {
            throw new IllegalArgumentException("ollama.pdf-records-per-chunk en az 1 olmalıdır.");
        }
        this.ollamaParsingService = ollamaParsingService;
        this.rowChunkSize = rowChunkSize;
        this.textChunkMaxChars = textChunkMaxChars;
        this.pdfTextChunkMaxChars = pdfTextChunkMaxChars;
        this.pdfRecordsPerChunk = pdfRecordsPerChunk;
    }

    DocumentParsingService(
            OllamaParsingService ollamaParsingService,
            int rowChunkSize,
            int textChunkMaxChars,
            int pdfTextChunkMaxChars) {
        this(ollamaParsingService, rowChunkSize, textChunkMaxChars, pdfTextChunkMaxChars, 5);
    }

    DocumentParsingService(
            OllamaParsingService ollamaParsingService,
            int rowChunkSize,
            int textChunkMaxChars) {
        this(ollamaParsingService, rowChunkSize, textChunkMaxChars, 2_000, 5);
    }

    /** Prepares format-aware chunks and parses them sequentially with Ollama. */
    public List<ProductPreviewDto> parseFile(MultipartFile file) {
        List<DocumentChunk> chunks = prepareChunks(file);
        if (chunks.isEmpty()) {
            return List.of();
        }
        return ollamaParsingService.parseChunks(chunks);
    }

    /** Production preview entry point with record-level semantic recovery. */
    public BulkPreviewResponseDto parseFileWithRecovery(MultipartFile file) {
        return parseFileWithRecovery(file, BulkImportCancellationToken.none());
    }

    /** Production preview entry point bound to one explicitly cancellable import job. */
    public BulkPreviewResponseDto parseFileWithRecovery(
            MultipartFile file,
            BulkImportCancellationToken cancellationToken) {
        long totalStartedNanos = System.nanoTime();
        String originalFilename = file.getOriginalFilename();
        cancellationToken.throwIfCancelled();
        long deterministicStartedNanos = System.nanoTime();
        List<DocumentChunk> chunks = prepareChunks(file);
        cancellationToken.throwIfCancelled();
        long deterministicDurationMillis = elapsedMillis(deterministicStartedNanos);
        if (chunks.isEmpty()) {
            return new BulkPreviewResponseDto(null, List.of(), List.of(), true);
        }

        long parseStartedNanos = System.nanoTime();
        BulkPreviewResponseDto preview = ollamaParsingService.parseChunksRecovering(
                chunks, cancellationToken);
        cancellationToken.throwIfCancelled();
        long ollamaDurationMillis = elapsedMillis(parseStartedNanos);
        log.info("BULK_IMPORT_DIAGNOSTICS|stage=DOCUMENT|jobId={}|document={}|type={}|"
                        + "excelParsingMs={}|initialExcelChunks={}|ollamaPipelineMs={}|"
                        + "products={}|unresolvedRows={}|documentTotalMs={}",
                cancellationToken.jobId(), originalFilename, fileType(originalFilename),
                deterministicDurationMillis,
                chunks.size(), ollamaDurationMillis, preview.getProducts().size(),
                preview.getUnresolvedRecords().size(), elapsedMillis(totalStartedNanos));
        if (originalFilename != null && originalFilename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            log.info("PDF accuracy-first ayrıştırma tamamlandı: document={}, microChunks={}, "
                            + "finalPreviewProducts={}, unresolvedRecords={}, durationMs={}",
                    originalFilename, chunks.size(), preview.getProducts().size(),
                    preview.getUnresolvedRecords().size(), ollamaDurationMillis);
        }
        return preview;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String fileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "UNKNOWN";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    }

    private List<DocumentChunk> prepareChunks(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("Dosya adı veya uzantısı belirlenemedi.");
        }

        String extension = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        List<DocumentChunk> chunks = switch (extension) {
            case ".xlsx" -> createExcelChunks(file, originalFilename);
            case ".csv" -> createCsvChunks(file, originalFilename);
            case ".pdf" -> createPdfChunks(file, originalFilename);
            case ".docx" -> createTextChunks(
                    extractTextWithTika(file),
                    originalFilename,
                    "DOCX");
            default -> throw new IllegalArgumentException(
                    "Desteklenmeyen dosya formatı: " + extension
                            + ". Desteklenen formatlar: .xlsx, .csv, .pdf, .docx");
        };

        if (chunks.isEmpty()) {
            log.info("Belgede ayrıştırılacak içerik bulunamadı: {}", originalFilename);
            return chunks;
        }

        log.info("Belge {} mantıksal parçaya ayrıldı: {}", chunks.size(), originalFilename);
        return chunks;
    }

    private List<DocumentChunk> createExcelChunks(MultipartFile file, String sourceDocument) {
        List<DocumentChunk> chunks = new ArrayList<>();

        try (InputStream input = file.getInputStream(); Workbook workbook = new XSSFWorkbook(input)) {
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("tr-TR"));
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<StructuredRow> rows = readWorksheetRows(sheet, formatter, evaluator);
                if (rows.isEmpty()) {
                    log.info("Boş worksheet atlandı: {}", sheet.getSheetName());
                    continue;
                }

                HeaderContext headerContext = buildHeaderContext(rows);
                int firstChunkPosition = chunks.size();
                int chunkIndex = 1;
                for (int offset = 0; offset < rows.size(); offset += rowChunkSize) {
                    List<StructuredRow> chunkRows = rows.subList(
                            offset,
                            Math.min(offset + rowChunkSize, rows.size()));
                    StructuredRow first = chunkRows.get(0);
                    StructuredRow last = chunkRows.get(chunkRows.size() - 1);
                    List<ExcelChunkMetadata.SourceRecord> tableRecords = tableRecords(
                            chunkRows, headerContext, sheet.getSheetName());
                    int candidateRecordCount = tableRecords.isEmpty()
                            ? (int) chunkRows.stream()
                                    .filter(StructuredRow::clearlyNumberedRecord)
                                    .count()
                            : tableRecords.size();

                    List<ExcelChunkMetadata.SourceRecord> reliableRecords = tableRecords.isEmpty()
                            ? chunkRows.stream()
                                    .filter(StructuredRow::clearlyNumberedRecord)
                                    .map(row -> new ExcelChunkMetadata.SourceRecord(
                                            excelSourceIdentity(sheet.getSheetName(), row.rowNumber()),
                                            row.rowNumber(), row.content(), null, null))
                                    .toList()
                            : tableRecords;

                    StringBuilder content = new StringBuilder()
                            .append("POTENTIAL HEADER/CONTEXT ROWS (REFERENCE ONLY; "
                                    + "extract records only from DATA ROWS):\n")
                            .append(headerContext.content())
                            .append("\n\nDATA ROWS TO ANALYZE:\n");
                    chunkRows.forEach(row -> {
                        reliableRecords.stream()
                                .filter(record -> record.sourceRow() == row.rowNumber())
                                .findFirst()
                                .ifPresent(record -> content.append("SOURCE RECORD ID: ")
                                        .append(record.sourceIdentity()).append('\n'));
                        content.append(row.content()).append('\n');
                    });

                    List<String> sourceProductNameCandidates = tableRecords.isEmpty()
                            ? List.of()
                            : tableRecords.stream()
                                    .map(ExcelChunkMetadata.SourceRecord::productNameCandidate)
                                    .toList();

                    chunks.add(new DocumentChunk(
                            sourceDocument,
                            "XLSX",
                            sheet.getSheetName(),
                            chunkIndex++,
                            first.rowNumber(),
                            last.rowNumber(),
                            chunkRows.size(),
                            candidateRecordCount,
                            headerContext.explicitProductCodeFieldAbsent(),
                            content.toString().trim(),
                            sourceProductNameCandidates,
                            null,
                            0,
                            content.length(),
                            reliableRecords.stream()
                                    .map(ExcelChunkMetadata.SourceRecord::sourceRow)
                                    .toList(),
                            0,
                            null,
                            new ExcelChunkMetadata(
                                    headerContext.content(),
                                    headerContext.explicitProductCodeFieldAbsent(),
                                    reliableRecords,
                                    reliableRecords)));

                    log.info("Excel chunk hazırlandı: worksheet={}, chunk={}, sourceRows={}-{}, "
                                    + "rowsInChunk={}, obviousNumberedRecords={}",
                            sheet.getSheetName(), chunkIndex - 1, first.rowNumber(),
                            last.rowNumber(), chunkRows.size(), candidateRecordCount);
                }

                log.info("Excel worksheet hazırlandı: worksheet={}, nonEmptySourceRows={}, chunksCreated={}",
                        sheet.getSheetName(), rows.size(), chunks.size() - firstChunkPosition);
            }
        } catch (IOException exception) {
            throw new RuntimeException("Excel dosyası okunurken hata oluştu: " + exception.getMessage(), exception);
        }

        return chunks;
    }

    private List<StructuredRow> readWorksheetRows(
            Sheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        List<StructuredRow> rows = new ArrayList<>();

        for (Row row : sheet) {
            int lastCellIndex = row.getLastCellNum();
            if (lastCellIndex < 0) {
                continue;
            }

            List<String> cells = new ArrayList<>(lastCellIndex);
            boolean hasContent = false;
            int nonEmptyCellCount = 0;
            for (int cellIndex = 0; cellIndex < lastCellIndex; cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
                cells.add(value);
                hasContent |= !value.isBlank();
                if (!value.isBlank()) {
                    nonEmptyCellCount++;
                }
            }

            if (hasContent) {
                int sourceRowNumber = row.getRowNum() + 1;
                String firstNonEmptyCell = cells.stream()
                        .filter(value -> !value.isBlank())
                        .findFirst()
                        .orElse("");
                boolean clearlyNumberedRecord = nonEmptyCellCount >= 2
                        && firstNonEmptyCell.matches("\\d+(?:[.,]\\d+)?");
                rows.add(new StructuredRow(
                        sourceRowNumber,
                        "ROW " + sourceRowNumber + ":\n" + String.join(" | ", cells),
                        clearlyNumberedRecord,
                        List.copyOf(cells)));
            }
        }
        return rows;
    }

    private HeaderContext buildHeaderContext(List<StructuredRow> rows) {
        List<StructuredRow> possibleHeaderRows = new ArrayList<>();
        for (StructuredRow row : rows.stream()
                .limit(MAX_STRUCTURED_HEADER_SCAN_ROW_COUNT).toList()) {
            if (row.clearlyNumberedRecord()) {
                break;
            }
            possibleHeaderRows.add(row);
        }
        HeaderCellMatch productNameHeader = findProductNameColumn(possibleHeaderRows);
        HeaderCellMatch quantityHeader = findQuantityColumn(possibleHeaderRows);
        if (productNameHeader != null && quantityHeader != null) {
            int headerRowNumber = Math.max(
                    productNameHeader.rowNumber(), quantityHeader.rowNumber());
            List<StructuredRow> rowsThroughHeader = rows.stream()
                    .filter(row -> row.rowNumber() <= headerRowNumber)
                    .toList();
            int contextStart = Math.max(0,
                    rowsThroughHeader.size() - MAX_HEADER_CONTEXT_ROW_COUNT);
            String content = rowsThroughHeader.subList(contextStart, rowsThroughHeader.size()).stream()
                    .map(StructuredRow::content)
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new HeaderContext(
                    content,
                    !containsExplicitProductCodeLabel(content),
                    productNameHeader.columnIndex(),
                    quantityHeader.columnIndex(),
                    headerRowNumber);
        }

        StringBuilder context = new StringBuilder();
        List<StructuredRow> detectedHeaderRows = new ArrayList<>();
        boolean numberedDataRowFound = false;
        for (StructuredRow row : rows.stream().limit(MAX_HEADER_CONTEXT_ROW_COUNT).toList()) {
            if (row.clearlyNumberedRecord()) {
                numberedDataRowFound = true;
                break;
            }
            detectedHeaderRows.add(row);
            context.append(row.content()).append('\n');
        }

        if (context.isEmpty()) {
            return new HeaderContext(
                    "No separate header row was reliably detected. Infer field semantics from DATA ROWS.",
                    false,
                    null,
                    null,
                    null);
        }
        if (!numberedDataRowFound) {
            context.setLength(0);
            rows.stream()
                    .limit(FALLBACK_HEADER_CONTEXT_ROW_COUNT)
                    .forEach(row -> context.append(row.content()).append('\n'));
            return new HeaderContext(context.toString().trim(), false, null, null, null);
        }

        Integer productNameColumnIndex = findProductNameColumnIndex(detectedHeaderRows);
        String content = context.toString().trim();
        return new HeaderContext(
                content,
                !containsExplicitProductCodeLabel(content),
                productNameColumnIndex,
                null,
                null);
    }

    private List<ExcelChunkMetadata.SourceRecord> tableRecords(
            List<StructuredRow> rows,
            HeaderContext headerContext,
            String worksheetName) {
        if (headerContext.headerRowNumber() == null
                || headerContext.productNameColumnIndex() == null
                || headerContext.quantityColumnIndex() == null) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row.rowNumber() > headerContext.headerRowNumber())
                .map(row -> {
                    String productName = ProductPreviewValidation.trimToNull(
                            cellValue(row, headerContext.productNameColumnIndex()));
                    Integer quantity = parsePositiveIntegerCell(
                            cellValue(row, headerContext.quantityColumnIndex()));
                    if (productName == null || quantity == null) {
                        return null;
                    }
                    return new ExcelChunkMetadata.SourceRecord(
                            excelSourceIdentity(worksheetName, row.rowNumber()),
                            row.rowNumber(), row.content(), productName, quantity);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String excelSourceIdentity(String worksheetName, int physicalRow) {
        return "xlsx:" + worksheetName + ":row:" + physicalRow;
    }

    private HeaderCellMatch findQuantityColumn(List<StructuredRow> headerRows) {
        HeaderCellMatch best = null;
        int bestScore = 0;
        for (StructuredRow headerRow : headerRows) {
            for (int index = 0; index < headerRow.cells().size(); index++) {
                String normalized = normalizeHeaderCell(headerRow.cells().get(index));
                int score = quantityHeaderScore(normalized);
                if (score > bestScore || (score == bestScore && score > 0
                        && best != null
                        && headerRow.rowNumber() == best.rowNumber()
                        && index > best.columnIndex())) {
                    best = new HeaderCellMatch(index, headerRow.rowNumber());
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private Integer findQuantityColumnIndex(StructuredRow headerRow) {
        HeaderCellMatch match = findQuantityColumn(List.of(headerRow));
        return match == null ? null : match.columnIndex();
    }

    private int quantityHeaderScore(String normalized) {
        if (normalized.equals("genel toplam")
                || normalized.equals("toplam adet")
                || normalized.equals("toplam miktar")
                || normalized.equals("stok miktari")
                || normalized.equals("quantity")
                || normalized.equals("grand total")
                || normalized.equals("overall total")) {
            return 3;
        }
        if (normalized.contains("genel toplam")
                || normalized.contains("toplam adet")
                || normalized.contains("toplam miktar")) {
            return 3;
        }
        if (normalized.equals("miktar") || normalized.equals("adet")) {
            return 2;
        }
        if (normalized.equals("toplam") || normalized.equals("total")) {
            return 1;
        }
        return 0;
    }

    private Integer parsePositiveIntegerCell(String value) {
        String normalized = ProductPreviewValidation.trimToNull(value);
        if (normalized == null || !normalized.matches("\\d+")) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalizeHeaderCell(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Integer findProductNameColumnIndex(List<StructuredRow> headerRows) {
        HeaderCellMatch match = findProductNameColumn(headerRows);
        return match == null ? null : match.columnIndex();
    }

    private HeaderCellMatch findProductNameColumn(List<StructuredRow> headerRows) {
        for (StructuredRow headerRow : headerRows) {
            for (int index = 0; index < headerRow.cells().size(); index++) {
                String normalized = normalizeHeaderCell(headerRow.cells().get(index));
                if (normalized.contains("malzeme adi")
                        || normalized.contains("urun adi")
                        || normalized.contains("arac gerec")
                        || normalized.equals("malzeme")
                        || normalized.equals("urun")) {
                    return new HeaderCellMatch(index, headerRow.rowNumber());
                }
            }
        }
        return null;
    }

    private String cellValue(StructuredRow row, int columnIndex) {
        if (columnIndex < 0 || columnIndex >= row.cells().size()) {
            return "";
        }
        return row.cells().get(columnIndex);
    }

    private boolean containsExplicitProductCodeLabel(String headerContext) {
        String normalized = Normalizer.normalize(headerContext, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        return normalized.contains("urun kod")
                || normalized.contains("malzeme kod")
                || normalized.contains("stok kod")
                || normalized.contains("product code")
                || normalized.contains("item code")
                || normalized.contains("sku")
                || normalized.contains("kod no")
                || normalized.matches("(?s).*\\n\\s*kod\\s*(?:[:|]|$).*");
    }

    private List<DocumentChunk> createCsvChunks(MultipartFile file, String sourceDocument) {
        List<StructuredRow> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    rows.add(new StructuredRow(
                            lineNumber,
                            "ROW " + lineNumber + ":\n" + line,
                            false,
                            List.of(line)));
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("CSV dosyası okunurken hata oluştu: " + exception.getMessage(), exception);
        }

        if (rows.isEmpty()) {
            return List.of();
        }

        StructuredRow potentialHeader = rows.get(0);
        String delimiter = potentialHeader.content().contains(";") ? ";" : ",";
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 1;

        for (int offset = 0; offset < rows.size(); offset += rowChunkSize) {
            List<StructuredRow> chunkRows = rows.subList(
                    offset,
                    Math.min(offset + rowChunkSize, rows.size()));
            StructuredRow first = chunkRows.get(0);
            StructuredRow last = chunkRows.get(chunkRows.size() - 1);

            StringBuilder content = new StringBuilder()
                    .append("CSV DELIMITER: ").append(delimiter).append('\n')
                    .append("POTENTIAL HEADER/CONTEXT ROW (REFERENCE ONLY; this row is also "
                            + "included in DATA ROWS when it belongs to the current chunk):\n")
                    .append(potentialHeader.content())
                    .append("\n\nDATA ROWS TO ANALYZE:\n");
            chunkRows.forEach(row -> content.append(row.content()).append('\n'));

            chunks.add(new DocumentChunk(
                    sourceDocument,
                    "CSV",
                    null,
                    chunkIndex++,
                    first.rowNumber(),
                    last.rowNumber(),
                    chunkRows.size(),
                    content.toString().trim()));
        }
        return chunks;
    }

    /** Package-private for focused chunk-boundary tests. */
    List<DocumentChunk> createTextChunks(String extractedText, String sourceDocument, String sourceType) {
        if (extractedText == null || extractedText.isBlank()) {
            throw new RuntimeException("Belgeden metin çıkarılamadı. Dosya boş veya okunamıyor olabilir.");
        }

        String normalized = extractedText.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> blocks = splitIntoLogicalBlocks(normalized, textChunkMaxChars);
        List<String> packedChunks = packTextBlocks(blocks, textChunkMaxChars);
        List<DocumentChunk> chunks = new ArrayList<>(packedChunks.size());

        for (int index = 0; index < packedChunks.size(); index++) {
            chunks.add(new DocumentChunk(
                    sourceDocument,
                    sourceType,
                    null,
                    index + 1,
                    null,
                    null,
                    0,
                    "TEXT BLOCKS TO ANALYZE:\n" + packedChunks.get(index)));
        }
        return chunks;
    }

    /** Extracts PDF text one page at a time so page boundaries cannot be lost. */
    List<DocumentChunk> createPdfChunks(MultipartFile file, String sourceDocument) {
        List<DocumentChunk> chunks = new ArrayList<>();

        try (InputStream input = file.getInputStream(); PDDocument document = PDDocument.load(input)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String pageText = normalizeExtractedText(stripper.getText(document));
                if (pageText.isBlank()) {
                    log.info("PDF sayfa hazırlandı: page={}, extractedCharacters=0, "
                            + "logicalLines=0, chunksCreated=0", pageNumber);
                    continue;
                }

                PdfRecordSegmenter.Segmentation segmentation = pdfRecordSegmenter.segment(pageText);
                if (segmentation.confidence() == PdfRecordSegmenter.Confidence.RELIABLE) {
                    int firstChunkPosition = chunks.size();
                    for (int offset = 0; offset < segmentation.records().size();
                            offset += pdfRecordsPerChunk) {
                        List<PdfRecordSegmenter.LogicalRecord> chunkRecords = List.copyOf(
                                segmentation.records().subList(
                                        offset,
                                        Math.min(offset + pdfRecordsPerChunk,
                                                segmentation.records().size())));
                        DocumentChunk chunk = DocumentChunk.pdfRecords(
                                sourceDocument,
                                pageNumber,
                                chunks.size() - firstChunkPosition + 1,
                                segmentation.headerContext(),
                                chunkRecords,
                                segmentation.confidence(),
                                segmentation.mixedFormats());
                        chunks.add(chunk);
                        log.info("PDF record micro-chunk hazırlandı: page={}, chunk={}, "
                                        + "sourceRecords={}, recordIds={}, sourceCharacters={}, "
                                        + "logicalLines={}, recordBoundaryConfidence={}, "
                                        + "difficultLayout={}",
                                pageNumber, chunk.chunkIndex(), chunkRecords.size(),
                                summarizeRecordIdentifiers(chunk.sourceRecordIdentifiers()),
                                chunk.sourceCharacterCount(), chunk.logicalLineCount(),
                                segmentation.confidence(), segmentation.mixedFormats());
                    }
                    log.info("PDF sayfa record-aware hazırlandı: page={}, extractedCharacters={}, "
                                    + "logicalLines={}, sourceLogicalRecords={}, "
                                    + "recordBoundaryConfidence={}, microChunks={}",
                            pageNumber, pageText.length(), countNonBlankLines(pageText),
                            segmentation.records().size(), segmentation.confidence(),
                            chunks.size() - firstChunkPosition);
                    continue;
                }

                List<String> blocks = splitIntoLogicalBlocks(pageText, pdfTextChunkMaxChars);
                List<String> packedChunks = packTextBlocks(blocks, pdfTextChunkMaxChars);
                PdfCandidateEstimate pageCandidateEstimate = analyzePdfCandidates(pageText);
                Set<Integer> pageReliableIdentifiers = new HashSet<>(
                        pageCandidateEstimate.recordIdentifiers());
                boolean explicitProductCodeFieldAbsent = !containsExplicitProductCodeLabel(pageText);
                int pageApproximateItemMarkers = Math.max(
                        countApproximatePdfItemMarkers(pageText),
                        segmentation.detectedRecordMarkers());
                int firstChunkPosition = chunks.size();
                for (int chunkIndex = 0; chunkIndex < packedChunks.size(); chunkIndex++) {
                    String chunkText = packedChunks.get(chunkIndex);
                    int logicalLineCount = countNonBlankLines(chunkText);
                    PdfCandidateEstimate chunkCandidateEstimate = analyzePdfCandidates(chunkText);
                    List<Integer> chunkReliableIdentifiers = chunkCandidateEstimate.matchedIdentifiers()
                            .stream()
                            .filter(pageReliableIdentifiers::contains)
                            .toList();
                    int approximateItemMarkers = countApproximatePdfItemMarkers(chunkText);
                    chunks.add(DocumentChunk.pdf(
                            sourceDocument,
                            pageNumber,
                            chunkIndex + 1,
                            logicalLineCount,
                            chunkReliableIdentifiers,
                            explicitProductCodeFieldAbsent,
                            approximateItemMarkers,
                            chunkText));
                    log.info("PDF fallback chunk hazırlandı: page={}, chunk={}, sourceCharacters={}, "
                                    + "logicalLines={}, reliableCandidateCount={}, recordIds={}, "
                                    + "approximateItemMarkers={}, explicitProductCodeFieldAbsent={}, "
                                    + "recordBoundaryConfidence={}, ignoredHeaderMatches={}, "
                                    + "ignoredOutlierMatches={}",
                            pageNumber, chunkIndex + 1, chunkText.length(), logicalLineCount,
                            chunkReliableIdentifiers.size(),
                            summarizeRecordIdentifiers(chunkReliableIdentifiers),
                            approximateItemMarkers, explicitProductCodeFieldAbsent,
                            segmentation.confidence(),
                            chunkCandidateEstimate.headerMatchCount(),
                            chunkCandidateEstimate.matchedIdentifiers().size()
                                    - chunkReliableIdentifiers.size());
                }

                log.info("PDF sayfa fallback hazırlandı: page={}, extractedCharacters={}, logicalLines={}, "
                                + "reliableCandidateCount={}, recordIds={}, approximateItemMarkers={}, "
                                + "recordBoundaryConfidence={}, chunksCreated={}",
                        pageNumber, pageText.length(), countNonBlankLines(pageText),
                        pageCandidateEstimate.recordIdentifiers().size(),
                        summarizeRecordIdentifiers(pageCandidateEstimate.recordIdentifiers()),
                        pageApproximateItemMarkers,
                        segmentation.confidence(),
                        chunks.size() - firstChunkPosition);
            }
        } catch (IOException exception) {
            throw new RuntimeException("PDF dosyası sayfa bazında okunurken hata oluştu: "
                    + exception.getMessage(), exception);
        }
        return chunks;
    }

    private String normalizeExtractedText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private List<String> splitIntoLogicalBlocks(String text, int maxChars) {
        List<String> blocks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= maxChars) {
                blocks.add(trimmed);
            } else {
                blocks.addAll(splitOversizedBlock(trimmed, maxChars));
            }
        }
        return blocks;
    }

    private List<String> splitOversizedBlock(String block, int maxChars) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : block.split("\\n")) {
            if (line.length() > maxChars) {
                flushBlock(parts, current);
                parts.addAll(splitLongLine(line, maxChars));
            } else if (!current.isEmpty()
                    && current.length() + 1 + line.length() > maxChars) {
                flushBlock(parts, current);
                current.append(line);
            } else {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        flushBlock(parts, current);
        return parts;
    }

    private List<String> splitLongLine(String line, int maxChars) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + maxChars, line.length());
            if (end < line.length()) {
                int whitespace = line.lastIndexOf(' ', end);
                if (whitespace > start) {
                    end = whitespace;
                }
            }
            parts.add(line.substring(start, end).trim());
            start = end;
            while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    private List<String> packTextBlocks(List<String> blocks, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (!current.isEmpty()
                    && current.length() + 2 + block.length() > maxChars) {
                flushBlock(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        flushBlock(chunks, current);
        return chunks;
    }

    private int countNonBlankLines(String text) {
        int count = 0;
        for (String line : text.split("\\n")) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts only strongly table-like, numbered PDF rows. This conservative
     * estimate drives retries; prose headings and specification-only numbers do
     * not create a target count.
     */
    int estimateReliablePdfCandidates(String text) {
        return analyzePdfCandidates(text).recordIdentifiers().size();
    }

    private PdfCandidateEstimate analyzePdfCandidates(String text) {
        Set<Integer> matchedIdentifiers = new TreeSet<>();
        int headerMatchCount = 0;
        for (String line : text.split("\\n")) {
            Matcher matcher = PDF_NUMBERED_RECORD_LINE.matcher(line);
            if (matcher.matches()) {
                if (isPdfHeaderLikeLine(line)) {
                    headerMatchCount++;
                } else {
                    matchedIdentifiers.add(Integer.parseInt(matcher.group(1)));
                }
            }
        }

        List<Integer> reliableIdentifiers = new ArrayList<>();
        List<Integer> currentRun = new ArrayList<>();
        for (Integer identifier : matchedIdentifiers) {
            if (!currentRun.isEmpty()
                    && identifier != currentRun.get(currentRun.size() - 1) + 1) {
                addReliableRun(reliableIdentifiers, currentRun);
                currentRun.clear();
            }
            currentRun.add(identifier);
        }
        addReliableRun(reliableIdentifiers, currentRun);

        return new PdfCandidateEstimate(
                reliableIdentifiers,
                new ArrayList<>(matchedIdentifiers),
                headerMatchCount,
                matchedIdentifiers.size() - reliableIdentifiers.size());
    }

    private int countApproximatePdfItemMarkers(String text) {
        Set<Integer> numberedIdentifiers = new TreeSet<>();
        int bulletMarkers = 0;
        int explicitItemLabels = 0;
        for (String line : text.split("\\n")) {
            Matcher numberedMatcher = PDF_NUMBERED_ITEM_MARKER.matcher(line);
            if (numberedMatcher.matches()) {
                numberedIdentifiers.add(Integer.parseInt(numberedMatcher.group(1)));
            }
            if (PDF_BULLET_ITEM_MARKER.matcher(line).matches()) {
                bulletMarkers++;
            }
            if (PDF_EXPLICIT_ITEM_LABEL.matcher(line).matches()) {
                explicitItemLabels++;
            }
        }

        List<Integer> sequentialMarkers = new ArrayList<>();
        List<Integer> currentRun = new ArrayList<>();
        for (Integer identifier : numberedIdentifiers) {
            if (!currentRun.isEmpty()
                    && identifier != currentRun.get(currentRun.size() - 1) + 1) {
                addReliableRun(sequentialMarkers, currentRun);
                currentRun.clear();
            }
            currentRun.add(identifier);
        }
        addReliableRun(sequentialMarkers, currentRun);
        return Math.max(sequentialMarkers.size(), Math.max(bulletMarkers, explicitItemLabels));
    }

    private void addReliableRun(List<Integer> target, List<Integer> run) {
        if (run.size() >= 3) {
            target.addAll(run);
        }
    }

    private boolean isPdfHeaderLikeLine(String line) {
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
        Set<String> labels = new HashSet<>();
        addHeaderLabel(labels, normalized, "sira no", "sira numarasi");
        addHeaderLabel(labels, normalized, "urun kod", "malzeme kod", "stok kod");
        addHeaderLabel(labels, normalized, "urun adi", "malzeme adi");
        addHeaderLabel(labels, normalized, "teknik ozellik", "spesifikasyon");
        addHeaderLabel(labels, normalized, "birim");
        addHeaderLabel(labels, normalized, "miktar", "toplam adet");
        addHeaderLabel(labels, normalized, "aciklama");
        return labels.size() >= 3;
    }

    private void addHeaderLabel(Set<String> labels, String text, String... alternatives) {
        for (String alternative : alternatives) {
            if (text.contains(alternative)) {
                labels.add(alternatives[0]);
                return;
            }
        }
    }

    private String summarizeRecordIdentifiers(List<Integer> identifiers) {
        if (identifiers.isEmpty()) {
            return "unknown";
        }
        if (identifiers.size() == 1) {
            return identifiers.get(0).toString();
        }
        return identifiers.get(0) + "-" + identifiers.get(identifiers.size() - 1)
                + " (" + identifiers.size() + ")";
    }

    private record PdfCandidateEstimate(
            List<Integer> recordIdentifiers,
            List<Integer> matchedIdentifiers,
            int headerMatchCount,
            int outlierMatchCount) {

        private PdfCandidateEstimate {
            recordIdentifiers = List.copyOf(recordIdentifiers);
            matchedIdentifiers = List.copyOf(matchedIdentifiers);
        }
    }

    private void flushBlock(List<String> target, StringBuilder current) {
        if (!current.isEmpty()) {
            target.add(current.toString());
            current.setLength(0);
        }
    }

    private String extractTextWithTika(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(input, handler, metadata);
            return handler.toString();
        } catch (Exception exception) {
            throw new RuntimeException("Belge dosyası okunurken hata oluştu: " + exception.getMessage(), exception);
        }
    }

    private record StructuredRow(
            int rowNumber,
            String content,
            boolean clearlyNumberedRecord,
            List<String> cells) {
    }

    private record HeaderContext(
            String content,
            boolean explicitProductCodeFieldAbsent,
            Integer productNameColumnIndex,
            Integer quantityColumnIndex,
            Integer headerRowNumber) {
    }

    private record HeaderCellMatch(int columnIndex, int rowNumber) {
    }
}

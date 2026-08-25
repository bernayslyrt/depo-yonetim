package com.depo.bulkimport.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DocumentParsingServiceTest {

    @Mock
    private OllamaParsingService ollamaParsingService;

    private DocumentParsingService service;
    private AtomicReference<List<DocumentChunk>> capturedChunks;

    @BeforeEach
    void setUp() {
        service = new DocumentParsingService(ollamaParsingService, 2, 1_000);
        capturedChunks = new AtomicReference<>(List.of());
        lenient().doAnswer(invocation -> {
            capturedChunks.set(new ArrayList<>(invocation.getArgument(0)));
            return List.of();
        }).when(ollamaParsingService).parseChunks(anyList());
    }

    @Test
    void processesEveryNonEmptyWorksheetAndSkipsEmptyWorksheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "EK-1", 2);
            addSheet(workbook, "EK-2", 2);
            addSheet(workbook, "EK-3", 2);
            addSheet(workbook, "EK-4", 2);
            addSheet(workbook, "EK-5", 2);
            workbook.createSheet("EMPTY");

            service.parseFile(asUpload(workbook, "multi-sheet.xlsx"));
        }

        assertThat(capturedChunks.get())
                .extracting(DocumentChunk::worksheetName)
                .contains("EK-1", "EK-2", "EK-3", "EK-4", "EK-5")
                .doesNotContain("EMPTY");
    }

    @Test
    void largeWorksheetCreatesMultipleRowChunksWithoutLosingLastRows() throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 30, 1_000);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "LARGE", 65);
            service.parseFile(asUpload(workbook, "large.xlsx"));
        }

        List<DocumentChunk> chunks = capturedChunks.get();
        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(DocumentChunk::chunkIndex).containsExactly(1, 2, 3);
        assertThat(chunks.get(0).startRow()).isEqualTo(1);
        assertThat(chunks.get(2).endRow()).isEqualTo(66);
        assertThat(chunks.get(2).content()).contains("Ürün-65");
        assertThat(chunks).extracting(DocumentChunk::candidateRecordCount)
                .containsExactly(29, 30, 6);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.explicitProductCodeFieldAbsent()).isTrue());
        assertThat(chunks.get(0).sourceProductNameCandidates())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 29)
                                .mapToObj(index -> "Ürün-" + index)
                                .toList());
        assertThat(chunks.get(0).excelMetadata()).isNotNull();
        assertThat(chunks.get(0).excelMetadata().sourceRecords())
                .extracting(ExcelChunkMetadata.SourceRecord::sourceRow)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(2, 30).boxed().toList());
    }

    @Test
    void threeHundredOneRowWorkbookHasUniquePreAiPhysicalIdentities() throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 50, 1_000);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "FULL-301", 301);
            service.parseFile(asUpload(workbook, "full-301.xlsx"));
        }

        List<ExcelChunkMetadata.SourceRecord> records = capturedChunks.get().stream()
                .flatMap(chunk -> chunk.excelMetadata().sourceRecords().stream())
                .toList();
        assertThat(records).hasSize(301);
        assertThat(records).extracting(ExcelChunkMetadata.SourceRecord::sourceIdentity)
                .doesNotHaveDuplicates()
                .contains("xlsx:FULL-301:row:2", "xlsx:FULL-301:row:302");
        assertThat(records).extracting(ExcelChunkMetadata.SourceRecord::quantityCandidate)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 301).boxed().toList());
    }

    @Test
    void threeHundredOneRowsAcrossFiveSheetsStayBatchedInsteadOfBecomingPerRecordCalls()
            throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 10, 1_000);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "SHEET-1", 61);
            addSheet(workbook, "SHEET-2", 60);
            addSheet(workbook, "SHEET-3", 60);
            addSheet(workbook, "SHEET-4", 60);
            addSheet(workbook, "SHEET-5", 60);
            service.parseFile(asUpload(workbook, "five-sheet-301.xlsx"));
        }

        List<DocumentChunk> chunks = capturedChunks.get();
        assertThat(chunks).hasSize(35);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.excelMetadata().sourceRecords().size()).isLessThanOrEqualTo(10));
        assertThat(chunks).extracting(DocumentChunk::worksheetName)
                .contains("SHEET-1", "SHEET-2", "SHEET-3", "SHEET-4", "SHEET-5");
        assertThat(chunks.stream()
                .mapToInt(chunk -> chunk.excelMetadata().sourceRecords().size())
                .sum()).isEqualTo(301);
    }

    @Test
    void longTechnicalDescriptionStaysAttachedToItsOriginalExcelRow() throws Exception {
        String description = "12V adaptör, 5mm LED ve 300 parça içeren teknik eğitim seti "
                + "- bu sayılar stok miktarı değildir";

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("TECHNICAL");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Malzeme");
            header.createCell(1).setCellValue("Teknik Özellikler");
            header.createCell(2).setCellValue("Miktar");
            Row product = sheet.createRow(1);
            product.createCell(0).setCellValue("LED Eğitim Seti");
            product.createCell(1).setCellValue(description);
            product.createCell(2).setCellValue(4);

            service.parseFile(asUpload(workbook, "technical.xlsx"));
        }

        assertThat(capturedChunks.get())
                .anySatisfy(chunk -> assertThat(chunk.content())
                        .contains("LED Eğitim Seti | " + description + " | 4"));
    }

    @Test
    void productAndQuantityHeadersOnDifferentRowsStillCreateAuthoritativeRecords() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("EK-1");
            Row firstHeader = sheet.createRow(0);
            firstHeader.createCell(0).setCellValue("Sıra No");
            firstHeader.createCell(1).setCellValue("Malzeme Adı");
            firstHeader.createCell(2).setCellValue("Teknik Özellikler");
            Row secondHeader = sheet.createRow(1);
            secondHeader.createCell(3).setCellValue("Genel Toplam");
            Row product = sheet.createRow(2);
            product.createCell(0).setCellValue(1);
            product.createCell(1).setCellValue("Tuval_1");
            product.createCell(2).setCellValue("Beyaz");
            product.createCell(3).setCellValue(450);

            service.parseFile(asUpload(workbook, "multi-row-header.xlsx"));
        }

        List<ExcelChunkMetadata.SourceRecord> records = capturedChunks.get().stream()
                .flatMap(chunk -> chunk.excelMetadata().sourceRecords().stream())
                .toList();
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.sourceIdentity()).isEqualTo("xlsx:EK-1:row:3");
            assertThat(record.productNameCandidate()).isEqualTo("Tuval_1");
            assertThat(record.quantityCandidate()).isEqualTo(450);
        });
        assertThat(capturedChunks.get().stream()
                .flatMap(chunk -> chunk.sourceProductNameCandidates().stream()).toList())
                .containsExactly("Tuval_1");
    }

    @Test
    void multipleWorksheetsCanEachProduceMultipleChunksInWorkbookOrder() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "FIRST", 4);
            addSheet(workbook, "SECOND", 4);
            service.parseFile(asUpload(workbook, "combined.xlsx"));
        }

        assertThat(capturedChunks.get())
                .extracting(chunk -> chunk.worksheetName() + "#" + chunk.chunkIndex())
                .containsExactly(
                        "FIRST#1", "FIRST#2", "FIRST#3",
                        "SECOND#1", "SECOND#2", "SECOND#3");
    }

    @Test
    void smallWorkbookStillUsesTheSamePreviewPipeline() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            addSheet(workbook, "SMALL", 1);
            service.parseFile(asUpload(workbook, "small.xlsx"));
        }

        assertThat(capturedChunks.get()).hasSize(1);
        assertThat(capturedChunks.get().get(0).content())
                .contains("DATA ROWS TO ANALYZE")
                .contains("Ürün-1");
    }

    @Test
    void decorativeRowsAndNotesNeverBecomePhysicalProductContributionRecords() throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 50, 1_000);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet products = workbook.createSheet("Ürünler");
            products.createRow(0).createCell(0).setCellValue("ENVANTER TEST DOSYASI");
            products.createRow(1).createCell(0).setCellValue(
                    "Kontrol notu: Silikon Tabancası 10 ve Beher 250 ml 12 olmalıdır.");
            products.createRow(2); // decorative blank row
            Row header = products.createRow(3);
            header.createCell(0).setCellValue("Ürün Adı");
            header.createCell(1).setCellValue("Miktar");
            header.createCell(2).setCellValue("Birim");
            header.createCell(3).setCellValue("Minimum Stok");
            header.createCell(4).setCellValue("Raf");
            addInventoryRow(products, 4, "Silikon Tabancası", 10);
            addInventoryRow(products, 5, "Silikon Çubuğu", 25);
            addInventoryRow(products, 6, "Beher 250 ml", 12);
            addInventoryRow(products, 7, "Pil Yuvarlak", 40);
            addInventoryRow(products, 8, "Pil Kalem", 30);
            addInventoryRow(products, 9, "Kablo 1m", 20);
            addInventoryRow(products, 10, "Kablo 2m", 18);
            addInventoryRow(products, 11, "Vida M3", 100);
            addInventoryRow(products, 12, "Vida M4", 80);
            addInventoryRow(products, 13, "Multimetre", 5);

            Sheet notes = workbook.createSheet("Test Notları");
            notes.createRow(0).createCell(0).setCellValue("Bu sayfa yalnızca test talimatları içerir.");
            notes.createRow(1).createCell(0).setCellValue("Silikon Tabancası ve Beher değerlerini kontrol edin.");

            service.parseFile(asUpload(workbook, "quantity-integrity.xlsx"));
        }

        DocumentChunk productChunk = capturedChunks.get().stream()
                .filter(chunk -> "Ürünler".equals(chunk.worksheetName()))
                .findFirst()
                .orElseThrow();
        assertThat(productChunk.excelMetadata().sourceRecords()).hasSize(10);
        assertThat(productChunk.excelMetadata().sourceRecords())
                .extracting(ExcelChunkMetadata.SourceRecord::sourceRow)
                .containsExactly(5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(productChunk.excelMetadata().sourceRecords())
                .extracting(ExcelChunkMetadata.SourceRecord::sourceIdentity)
                .containsExactly(
                        "xlsx:Ürünler:row:5", "xlsx:Ürünler:row:6",
                        "xlsx:Ürünler:row:7", "xlsx:Ürünler:row:8",
                        "xlsx:Ürünler:row:9", "xlsx:Ürünler:row:10",
                        "xlsx:Ürünler:row:11", "xlsx:Ürünler:row:12",
                        "xlsx:Ürünler:row:13", "xlsx:Ürünler:row:14");
        assertThat(productChunk.excelMetadata().sourceRecords())
                .extracting(ExcelChunkMetadata.SourceRecord::productNameCandidate)
                .containsExactly("Silikon Tabancası", "Silikon Çubuğu", "Beher 250 ml",
                        "Pil Yuvarlak", "Pil Kalem", "Kablo 1m", "Kablo 2m",
                        "Vida M3", "Vida M4", "Multimetre");
        assertThat(productChunk.excelMetadata().sourceRecords())
                .extracting(ExcelChunkMetadata.SourceRecord::quantityCandidate)
                .containsExactly(10, 25, 12, 40, 30, 20, 18, 100, 80, 5);
        assertThat(capturedChunks.get().stream()
                .filter(chunk -> "Test Notları".equals(chunk.worksheetName()))
                .flatMap(chunk -> chunk.excelMetadata().sourceRecords().stream())
                .toList())
                .isEmpty();
    }

    @Test
    void csvIsChunkedByCompleteLogicalLinesAndRepeatsHeaderContext() {
        String csv = "Malzeme;Teknik Özellik;Miktar\n"
                + "LED Seti;12V ve 5mm parçalar;4\n"
                + "Kablo;2 metre teknik uzunluk;7\n"
                + "Adaptör;220V giriş;3\n";
        MockMultipartFile upload = new MockMultipartFile(
                "file", "large.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        service.parseFile(upload);

        assertThat(capturedChunks.get()).hasSize(2);
        assertThat(capturedChunks.get()).allSatisfy(chunk ->
                assertThat(chunk.content()).contains("Malzeme;Teknik Özellik;Miktar"));
        assertThat(capturedChunks.get().get(0).content())
                .contains("LED Seti;12V ve 5mm parçalar;4");
        assertThat(capturedChunks.get().get(1).content())
                .contains("Kablo;2 metre teknik uzunluk;7")
                .contains("Adaptör;220V giriş;3");
    }

    @Test
    void csvWithoutHeaderKeepsItsFirstProductRecord() {
        String csv = "LED Seti;12V ve 5mm parçalar;4\n";
        MockMultipartFile upload = new MockMultipartFile(
                "file", "headerless.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        service.parseFile(upload);

        assertThat(capturedChunks.get()).hasSize(1);
        assertThat(capturedChunks.get().get(0).content())
                .contains("DATA ROWS TO ANALYZE:\nROW 1:\nLED Seti;12V ve 5mm parçalar;4");
    }

    @Test
    void pdfAndDocxTextKeepsParagraphsWholeWhenPackingChunks() {
        String firstParagraph = "A".repeat(700);
        String secondParagraph = "B".repeat(700);

        List<DocumentChunk> chunks = service.createTextChunks(
                firstParagraph + "\n\n" + secondParagraph,
                "long.pdf",
                "PDF");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains(firstParagraph).doesNotContain(secondParagraph);
        assertThat(chunks.get(1).content()).contains(secondParagraph).doesNotContain(firstParagraph);
    }

    @Test
    void multiPagePdfPreservesEveryPageAndItsRecordCandidates() throws Exception {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            pages.add(List.of(
                    pdfRow(1, "Page" + page + "-Product1", 3),
                    pdfRow(2, "Page" + page + "-Product2", 4),
                    pdfRow(3, "Page" + page + "-Product3", 5)));
        }

        service.parseFile(asPdfUpload(pages, "four-pages.pdf"));

        assertThat(capturedChunks.get())
                .extracting(DocumentChunk::pageNumber)
                .containsExactly(1, 2, 3, 4);
        assertThat(capturedChunks.get())
                .extracting(DocumentChunk::candidateRecordCount)
                .containsExactly(3, 3, 3, 3);
        for (int page = 1; page <= 4; page++) {
            int currentPage = page;
            assertThat(capturedChunks.get())
                    .filteredOn(chunk -> chunk.pageNumber() == currentPage)
                    .singleElement()
                    .satisfies(chunk -> assertThat(chunk.content())
                            .contains("Page" + currentPage + "-Product3"));
        }
    }

    @Test
    void onePdfPageCanCreateMultipleChunksWithoutLosingLaterLines() throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 2, 1_000, 1_000);
        List<String> lines = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            lines.add(pdfRow(index, "Product-" + index + "-" + "X".repeat(45), index));
        }

        service.parseFile(asPdfUpload(List.of(lines), "long-page.pdf"));

        assertThat(capturedChunks.get()).hasSizeGreaterThan(1);
        assertThat(capturedChunks.get()).allSatisfy(chunk -> {
            assertThat(chunk.pageNumber()).isEqualTo(1);
            assertThat(chunk.sourceCharacterCount()).isLessThanOrEqualTo(1_000);
        });
        assertThat(capturedChunks.get().get(capturedChunks.get().size() - 1).content())
                .contains("Product-20");
    }

    @Test
    void pageLevelReliableIdentifiersRemainAssignedAcrossSmallChunkBoundaries() throws Exception {
        service = new DocumentParsingService(ollamaParsingService, 2, 1_000, 1_000);
        List<String> lines = new ArrayList<>();
        for (int identifier = 1; identifier <= 12; identifier++) {
            lines.add(pdfRow(identifier, "Product-" + identifier + "-" + "X".repeat(430), identifier));
        }

        service.parseFile(asPdfUpload(List.of(lines), "split-numbered-page.pdf"));

        assertThat(capturedChunks.get()).hasSizeGreaterThan(1);
        assertThat(capturedChunks.get()).flatExtracting(DocumentChunk::sourceRecordIdentifiers)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(capturedChunks.get().stream()
                .mapToInt(DocumentChunk::candidateRecordCount).sum()).isEqualTo(12);
    }

    @Test
    void pdfWithoutExplicitCodeLabelMarksAiCodesForSuppression() throws Exception {
        service.parseFile(asPdfUpload(List.of(List.of(
                "Urun Adi: M3 Vida Somun Seti",
                "Miktar: 3")), "no-code-label.pdf"));

        assertThat(capturedChunks.get()).singleElement().satisfies(chunk -> {
            assertThat(chunk.explicitProductCodeFieldAbsent()).isTrue();
            assertThat(chunk.content()).doesNotContain("Urun Kodu");
        });
    }

    @Test
    void pdfWithExplicitCodeLabelPreservesCodeFieldAuthority() throws Exception {
        service.parseFile(asPdfUpload(List.of(List.of(
                "Urun Kodu: M3-SET",
                "Urun Adi: M3 Vida Somun Seti",
                "Miktar: 3")), "code-label.pdf"));

        assertThat(capturedChunks.get()).singleElement().satisfies(chunk ->
                assertThat(chunk.explicitProductCodeFieldAbsent()).isFalse());
    }

    @Test
    void laterPdfPagesAreNotLostWhenEarlierPagesAreEmpty() throws Exception {
        List<List<String>> pages = List.of(
                List.of(),
                List.of(),
                List.of(pdfRow(1, "ThirdPageProduct", 8), pdfRow(2, "ThirdPageProduct2", 9),
                        pdfRow(3, "ThirdPageProduct3", 10)),
                List.of(pdfRow(1, "FourthPageProduct", 11), pdfRow(2, "FourthPageProduct2", 12),
                        pdfRow(3, "FourthPageProduct3", 13)));

        service.parseFile(asPdfUpload(pages, "later-pages.pdf"));

        assertThat(capturedChunks.get()).extracting(DocumentChunk::pageNumber)
                .containsExactly(3, 4);
        assertThat(capturedChunks.get().get(0).content()).contains("ThirdPageProduct");
        assertThat(capturedChunks.get().get(1).content()).contains("FourthPageProduct");
    }

    @Test
    void nonProductPdfCoverPageRemainsASeparateNonCandidateChunk() throws Exception {
        service.parseFile(asPdfUpload(List.of(
                List.of("INVENTORY CATALOG", "Prepared for the workshop"),
                List.of(pdfRow(1, "ProductA", 2), pdfRow(2, "ProductB", 3),
                        pdfRow(3, "ProductC", 4))), "cover.pdf"));

        assertThat(capturedChunks.get()).extracting(DocumentChunk::pageNumber)
                .containsExactly(1, 2);
        assertThat(capturedChunks.get().get(0).candidateRecordCount()).isZero();
        assertThat(capturedChunks.get().get(1).candidateRecordCount()).isEqualTo(3);
    }

    @Test
    void pdfHeaderPlusTwentyFiveNumberedProductsProducesTwentyFiveReliableCandidates() {
        StringBuilder source = new StringBuilder(
                "2 | Sira No | Urun Kodu | Urun Adi | Teknik Ozellikler | Birim | Miktar | 50\n");
        for (int identifier = 26; identifier <= 50; identifier++) {
            source.append(pdfRow(identifier, "Product-" + identifier, identifier)).append('\n');
        }

        assertThat(service.estimateReliablePdfCandidates(source.toString())).isEqualTo(25);
    }

    @Test
    void repeatedPdfTableHeadersAreExcludedOnEveryPage() throws Exception {
        List<String> pageOne = new ArrayList<>();
        pageOne.add("1 | Sira No | Urun Kodu | Urun Adi | Teknik Ozellikler | Birim | Miktar | 25");
        pageOne.addAll(pdfRows(1, 25));
        List<String> pageTwo = new ArrayList<>();
        pageTwo.add("2 | Sira No | Urun Kodu | Urun Adi | Teknik Ozellikler | Birim | Miktar | 50");
        pageTwo.addAll(pdfRows(26, 50));

        service.parseFile(asPdfUpload(List.of(pageOne, pageTwo), "repeated-headers.pdf"));

        assertThat(capturedChunks.get().stream()
                .filter(chunk -> chunk.pageNumber() == 1)
                .mapToInt(DocumentChunk::candidateRecordCount).sum()).isEqualTo(25);
        assertThat(capturedChunks.get().stream()
                .filter(chunk -> chunk.pageNumber() == 2)
                .mapToInt(DocumentChunk::candidateRecordCount).sum()).isEqualTo(25);
        assertThat(capturedChunks.get().stream()
                .filter(chunk -> chunk.pageNumber() == 1)
                .flatMap(chunk -> chunk.sourceRecordIdentifiers().stream()).toList())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 25).boxed().toList());
        assertThat(capturedChunks.get().stream()
                .filter(chunk -> chunk.pageNumber() == 2)
                .flatMap(chunk -> chunk.sourceRecordIdentifiers().stream()).toList())
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(26, 50).boxed().toList());
    }

    @Test
    void pdfNumberedRangeTwentySixThroughFiftyHasExactReliableCount() {
        assertThat(service.estimateReliablePdfCandidates(String.join("\n", pdfRows(26, 50))))
                .isEqualTo(25);
    }

    @Test
    void isolatedPageTitleMatchIsNotAddedToAContiguousRecordRange() {
        StringBuilder source = new StringBuilder("2 | Inventory product table page | 2\n");
        source.append(String.join("\n", pdfRows(26, 50)));

        assertThat(service.estimateReliablePdfCandidates(source.toString())).isEqualTo(25);
    }

    @Test
    void freeFormPdfDoesNotInventAnExactCandidateCount() {
        String source = "Workshop material catalog\n"
                + "LED training set suitable for classroom use\n"
                + "Professional wood and metal paint set\n"
                + "Quantities will be confirmed after delivery";

        assertThat(service.estimateReliablePdfCandidates(source)).isZero();
    }

    @Test
    void technicalSpecificationNumbersAreNotRecordIdentifiers() {
        String source = "12V power support\n5mm tip width\n300 pieces included\n20x15mm dimensions";

        assertThat(service.estimateReliablePdfCandidates(source)).isZero();
    }

    private void addSheet(XSSFWorkbook workbook, String name, int productCount) {
        Sheet sheet = workbook.createSheet(name);
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Sıra No");
        header.createCell(1).setCellValue("Malzeme Adı");
        header.createCell(2).setCellValue("Teknik Özellikler");
        header.createCell(3).setCellValue("Toplam Adet");

        for (int index = 1; index <= productCount; index++) {
            Row row = sheet.createRow(index);
            row.createCell(0).setCellValue(index);
            row.createCell(1).setCellValue("Ürün-" + index);
            row.createCell(2).setCellValue("Uzun ve değişken açıklama " + index);
            row.createCell(3).setCellValue(index);
        }
    }

    private void addInventoryRow(Sheet sheet, int zeroBasedRow, String name, int quantity) {
        Row row = sheet.createRow(zeroBasedRow);
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(quantity);
        row.createCell(2).setCellValue("Adet");
        row.createCell(3).setCellValue(3);
        row.createCell(4).setCellValue("A-01");
    }

    private MockMultipartFile asUpload(XSSFWorkbook workbook, String filename) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return new MockMultipartFile(
                "file",
                filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private MockMultipartFile asPdfUpload(List<List<String>> pages, String filename) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (!lines.isEmpty()) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, 9);
                        stream.newLineAtOffset(36, 760);
                        for (String line : lines) {
                            stream.showText(line);
                            stream.newLineAtOffset(0, -14);
                        }
                        stream.endText();
                    }
                }
            }
            document.save(output);
            return new MockMultipartFile("file", filename, "application/pdf", output.toByteArray());
        }
    }

    private String pdfRow(int sequence, String productName, int quantity) {
        return sequence + " | " + productName
                + " | Technical description 12V 5mm 300 pieces 20x15mm | " + quantity;
    }

    private List<String> pdfRows(int firstIdentifier, int lastIdentifier) {
        List<String> rows = new ArrayList<>();
        for (int identifier = firstIdentifier; identifier <= lastIdentifier; identifier++) {
            rows.add(pdfRow(identifier, "Product-" + identifier, identifier));
        }
        return rows;
    }
}

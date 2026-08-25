package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.dto.UnresolvedSourceRecordDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sequentially extracts inventory products from structure-preserving document
 * chunks through the local Ollama REST API.
 */
@Service
@Slf4j
public class OllamaParsingService {

    private static final String OLLAMA_GENERATE_ENDPOINT = "/api/generate";

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "array",
            "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "sourceRecordId", Map.of(
                                    "type", List.of("string", "null"),
                                    "description", "Exact supplied structured-source record ID; otherwise null."),
                            "productCode", Map.of(
                                    "type", List.of("string", "null"),
                                    "description", "Only an explicit source code or identifier; otherwise null."),
                            "productName", Map.of(
                                    "type", List.of("string", "null"),
                                    "description", "The short material/product name, never its technical description."),
                            "quantity", Map.of(
                                    "type", List.of("integer", "null"),
                                    "description", "The final inventory quantity indicated by quantity/total context.")),
                    "required", List.of(
                            "sourceRecordId", "productCode", "productName", "quantity"),
                    "additionalProperties", false));

    private static final Map<String, Object> NAME_VERIFICATION_SCHEMA = Map.of(
            "type", "array",
            "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "itemIndex", Map.of("type", "integer"),
                            "status", Map.of(
                                    "type", "string",
                                    "enum", List.of("CONFIRMED", "CORRECTED", "REVIEW_REQUIRED")),
                            "productName", Map.of("type", List.of("string", "null")),
                            "reason", Map.of("type", List.of("string", "null"))),
                    "required", List.of("itemIndex", "status", "productName", "reason"),
                    "additionalProperties", false));

    private static final String SYSTEM_PROMPT = """
            You extract inventory products from one fragment of a larger document.
            The document format is not fixed. It may be a table, a multi-row-header
            worksheet, a list, technical prose, or free-form Turkish text.

            First understand the local structure, then extract every actual
            product/material record present in DATA ROWS or TEXT BLOCKS.

            Extract semantic meaning; never map fields merely by column position.

            Return ONLY a JSON array matching this schema:
            [{"sourceRecordId": string|null, "productCode": string|null, "productName": string|null,
              "quantity": integer|null}]

            Rules:
            - PRODUCT NAME is the actual, concise material/product name. Possible
              labels include Malzeme Adı, Ürün Adı, Malzeme, Ürün and Araç Gereç.
            - TECHNICAL DESCRIPTION contains dimensions, materials, voltage,
              package contents, performance, usage details or specifications.
              It MUST NOT become productName and must not be returned separately.
            - PRODUCT CODE is only a code/identifier explicitly present in a
              source field identified as code, SKU, stok kodu or similar.
              Never copy the product name into productCode. If no explicit code
              exists, productCode MUST be null.
              Sıra No, row number, sequence number and list position are NOT
              product codes.
            - Never omit a product merely because its description is long.
            - Never merge two different source records and never invent a product.
            - When a DATA ROW has a SOURCE RECORD ID, copy that exact value into
              sourceRecordId for the product extracted from that row. Never invent,
              edit, infer or reuse an ID from another row. For sources without an
              explicit SOURCE RECORD ID, return sourceRecordId as null.
            - Preserve Turkish product names accurately.
            - One source record may visually span multiple lines.
            - REFERENCE ONLY header/context rows explain structure; do not extract
              them as products unless the same record is present in DATA ROWS.
            - Ignore titles, footnotes, subtotals, signatures, category separators,
              explanatory text, delivery-person rows and empty records.
            - quantity must come only from quantity/order/stock context.
            - When several numeric workshop/category columns exist, use the
              explicitly labelled final/overall total quantity. If both a per-site
              total and a multi-site/grand total exist, use the final grand total.
              If no single intended total is reliable, return null.
            - Numbers in technical specifications are NOT automatically quantities.
              Dimensions, voltage, model numbers, years, cable length, package
              contents, and phrases such as 5 mm, 12 V or 300-piece set must not be
              mistaken for inventory quantity.
            - If quantity cannot be determined reliably, return null.
            - A valid quantity is a positive integer without unit text.
            - Return no Markdown fences, commentary, or text outside the JSON array.
            """;

    private static final String RETRY_INSTRUCTION = """
            The previous extraction could not be validated.
            Re-read the complete fragment carefully. Do not omit product records.
            Return only the required structured JSON array.
            """;

    private static final String PDF_SYSTEM_PROMPT = SYSTEM_PROMPT + """

            PDF-SPECIFIC CONTEXT:
            - You are extracting records from exactly one page fragment of a PDF.
            - The page may contain tables, lists, free-form text, multi-line
              records, headers and technical descriptions.
            - Extract every actual product/material record in this fragment,
              including later records; do not merge separate products.
            - Preserve the readable line order supplied in the source. Do not
              invent columns that are not reliably represented.
            - Do not extract page titles or table headers as products.
            - When PDF LOGICAL RECORDS are supplied, every RECORD block is
              exactly one source product. Return exactly one output per RECORD,
              in the same order. Never merge adjacent RECORD blocks.
            - SOURCE QUANTITY EVIDENCE is a structural value already read from
              that record's explicit quantity/overall-total field. Use it as the
              output quantity for that RECORD; do not replace it with numbers in
              SOURCE RECORD TEXT.
            - SOURCE PRODUCT CODE EVIDENCE is the only authoritative product code
              for that RECORD. Copy an explicit value exactly. When it says NONE,
              productCode must be null; model numbers in product names are not codes.
            - RECORD numbers are diagnostic source positions, not product codes.
            """;

    private static final String NAME_VERIFIER_SYSTEM_PROMPT = """
            You verify only whether an initially extracted productName is the actual
            material/product name shown in the supplied source context.

            Do not re-extract the document. Do not change productCode or quantity.
            Technical descriptions, dimensions, material composition, performance,
            usage details and full explanatory sentences are not product names.

            When an item includes "source field labelled as product/material name",
            that value was read deterministically from a reliable source header such
            as Malzeme Adı. Treat it as the strongest source-grounded evidence. If
            the initial productName differs and the labelled value is non-empty,
            return CORRECTED with that exact labelled wording unless the surrounding
            source row clearly proves the header/value association is ambiguous.

            Return exactly one result for every requested itemIndex:
            - CONFIRMED: the initial name is clearly the actual source product name.
              Preserve it exactly; do not rewrite it with a synonym.
            - CORRECTED: the initial name is clearly wrong and the source explicitly
              provides the actual product/material name. Return that source wording.
            - REVIEW_REQUIRED: the correct name cannot be determined confidently.
              Do not guess; return productName as null.

            reason must be null or one short user-facing sentence, never hidden
            reasoning, chain-of-thought, or a detailed analysis.
            Return only the structured JSON array.
            """;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxRetries;
    private final boolean structuredOutputEnabled;
    private final int parallelRequests;
    private final ThreadLocal<ImportRequestMetrics> activeImportMetrics = new ThreadLocal<>();
    private final ThreadLocal<BulkImportCancellationToken> activeCancellationToken = new ThreadLocal<>();
    private final ProductNameSuspicionDetector productNameSuspicionDetector =
            new ProductNameSuspicionDetector();

    @Autowired
    public OllamaParsingService(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model}") String model,
            @Value("${ollama.timeout-seconds:120}") int timeoutSeconds,
            @Value("${ollama.max-retries:2}") int maxRetries,
            @Value("${ollama.structured-output-enabled:true}") boolean structuredOutputEnabled,
            @Value("${ollama.parallel-requests:2}") int parallelRequests) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("ollama.timeout-seconds pozitif olmalıdır.");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("ollama.max-retries negatif olamaz.");
        }
        if (parallelRequests < 1 || parallelRequests > 4) {
            throw new IllegalArgumentException("ollama.parallel-requests 1 ile 4 arasında olmalıdır.");
        }

        this.restTemplate = restTemplateBuilder
                .rootUri(baseUrl)
                .setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxRetries = maxRetries;
        this.structuredOutputEnabled = structuredOutputEnabled;
        this.parallelRequests = parallelRequests;

        log.info("OllamaParsingService başlatıldı — Base URL: {}, Model: {}, Timeout: {}s, "
                        + "Max retry: {}, Structured output: {}, Parallel requests: {}",
                baseUrl, model, timeoutSeconds, maxRetries, structuredOutputEnabled, parallelRequests);
    }

    OllamaParsingService(
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            String baseUrl,
            String model,
            int timeoutSeconds,
            int maxRetries,
            boolean structuredOutputEnabled) {
        this(restTemplateBuilder, objectMapper, baseUrl, model, timeoutSeconds,
                maxRetries, structuredOutputEnabled, 2);
    }

    /**
     * Backward-compatible single-fragment entry point.
     */
    public List<ProductPreviewDto> parseWithAi(String rawText) {
        DocumentChunk chunk = new DocumentChunk(
                "inline-text", "TEXT", null, 1, null, null, rawText);
        return parseChunks(List.of(chunk));
    }

    /**
     * Parses chunks sequentially and preserves source order. A single permanent
     * chunk failure aborts the complete preview so partial data is never shown as
     * a successful import.
     */
    List<ProductPreviewDto> parseChunks(List<DocumentChunk> chunks) {
        List<ProductPreviewDto> merged = new ArrayList<>();
        Map<String, WorksheetParsingStats> worksheetStats = new LinkedHashMap<>();
        ProductNameVerificationStats verificationStats = new ProductNameVerificationStats();

        for (DocumentChunk chunk : chunks) {
            ChunkParseOutcome outcome = parseChunkWithRetry(chunk);
            List<ProductPreviewDto> chunkResults = outcome.results();
            verificationStats.add(verifySuspiciousProductNames(chunk, chunkResults));
            long validCount = chunkResults.stream().filter(ProductPreviewDto::isValid).count();
            long reviewRequiredCount = chunkResults.stream()
                    .filter(ProductPreviewDto::isReviewRequired)
                    .count();
            long invalidCount = chunkResults.stream()
                    .filter(this::hasHardValidationError)
                    .count();
            if (isPdf(chunk)) {
                log.info("PDF Ollama chunk tamamlandı: page={}, chunk={}, sourceCharacters={}, "
                                + "logicalLines={}, reliableCandidateCount={}, approximateItemMarkers={}, "
                                + "recordBoundaryConfidence={}, sourceLogicalRecords={}, "
                                + "ollamaProducts={}, productsAfterValidation={}, validProducts={}, "
                                + "reviewRequired={}, invalidProducts={}, finalPreviewContribution={}, "
                                + "attemptsUsed={}, retryCount={}",
                        chunk.pageNumber(), chunk.chunkIndex(), chunk.sourceCharacterCount(),
                        chunk.logicalLineCount(), chunk.candidateRecordCount(),
                        chunk.approximateItemMarkerCount(), pdfBoundaryConfidence(chunk),
                        pdfSourceRecordCount(chunk), chunkResults.size(), chunkResults.size(),
                        validCount, reviewRequiredCount, invalidCount, chunkResults.size(),
                        outcome.attemptsUsed(), outcome.attemptsUsed() - 1);
            } else {
                log.info("Ollama chunk tamamlandı: worksheet={}, chunk={}, sourceRows={}-{}, "
                                + "sourceRowCount={}, obviousNumberedRecords={}, extractedProducts={}, "
                                + "validProducts={}",
                        worksheetLabel(chunk), chunk.chunkIndex(), chunk.startRow(), chunk.endRow(),
                        chunk.sourceRowCount(), chunk.candidateRecordCount(), chunkResults.size(), validCount);
            }

            WorksheetParsingStats stats = worksheetStats.computeIfAbsent(
                    worksheetLabel(chunk), ignored -> new WorksheetParsingStats());
            stats.chunkCount++;
            stats.sourceRowCount += chunk.sourceRowCount();
            stats.sourceCharacterCount += chunk.sourceCharacterCount();
            stats.logicalLineCount += chunk.logicalLineCount();
            stats.candidateRecordCount += chunk.candidateRecordCount();
            stats.approximateItemMarkerCount += chunk.approximateItemMarkerCount();
            stats.extractedProductCount += chunkResults.size();
            stats.validProductCount += validCount;
            stats.reviewRequiredCount += reviewRequiredCount;
            stats.invalidProductCount += invalidCount;
            stats.retryCount += outcome.attemptsUsed() - 1;
            if (isPdf(chunk) && chunk.pdfMetadata() != null) {
                stats.recordBoundaryConfidence = chunk.pdfMetadata().boundaryConfidence().name();
            }

            for (ProductPreviewDto item : chunkResults) {
                item.setRowNumber(merged.size() + 1);
                merged.add(item);
            }
        }

        worksheetStats.forEach((worksheet, stats) -> {
            if (worksheet.startsWith("PDF page ")) {
                log.info("PDF sayfa Ollama özeti: page={}, sourceCharacters={}, logicalLines={}, "
                                + "reliableCandidateCount={}, approximateItemMarkers={}, chunks={}, "
                                + "recordBoundaryConfidence={}, "
                                + "productsReturnedByOllama={}, productsAfterMerge={}, "
                                + "productsAfterValidation={}, validProducts={}, reviewRequired={}, "
                                + "invalidProducts={}, finalPreviewContribution={}, retryCount={}",
                        worksheet.substring("PDF page ".length()), stats.sourceCharacterCount,
                        stats.logicalLineCount, stats.candidateRecordCount,
                        stats.approximateItemMarkerCount, stats.chunkCount,
                        stats.recordBoundaryConfidence,
                        stats.extractedProductCount,
                        stats.extractedProductCount, stats.extractedProductCount,
                        stats.validProductCount, stats.reviewRequiredCount,
                        stats.invalidProductCount, stats.extractedProductCount, stats.retryCount);
            } else {
                log.info("Ollama worksheet özeti: worksheet={}, sourceRows={}, "
                                + "obviousNumberedRecords={}, chunks={}, "
                                + "extractedProducts={}, validProducts={}",
                        worksheet, stats.sourceRowCount, stats.candidateRecordCount, stats.chunkCount,
                        stats.extractedProductCount, stats.validProductCount);
            }
        });
        log.info("{} chunk içinden toplam {} ürün satırı ayrıştırıldı", chunks.size(), merged.size());
        log.info("Ürün adı doğrulama özeti: productsExtracted={}, suspicionChecks={}, "
                        + "verifiedCorrect={}, autoCorrected={}, reviewRequired={}",
                merged.size(), verificationStats.suspicionCount,
                verificationStats.confirmedCount, verificationStats.correctedCount,
                verificationStats.reviewRequiredCount);
        return merged;
    }

    /**
     * Keeps successful chunks, recursively narrows semantic failures that have
     * trustworthy record boundaries, and reports only irreducible source gaps.
     * Transport/API outages are never converted into gaps.
     */
    BulkPreviewResponseDto parseChunksRecovering(List<DocumentChunk> chunks) {
        return parseChunksRecovering(chunks, BulkImportCancellationToken.none());
    }

    BulkPreviewResponseDto parseChunksRecovering(
            List<DocumentChunk> chunks,
            BulkImportCancellationToken cancellationToken) {
        long totalStartedNanos = System.nanoTime();
        ImportRequestMetrics requestMetrics = new ImportRequestMetrics();
        RecoveryStats stats = new RecoveryStats();
        int finalProductCount = 0;
        int finalReviewRequiredRows = 0;
        int finalUnresolvedRows = 0;
        try {
            List<ProductPreviewDto> products = new ArrayList<>();
            List<UnresolvedSourceRecordDto> unresolved = new ArrayList<>();
            cancellationToken.throwIfCancelled();
            List<ChunkRecoveryResult> chunkResults = recoverTopLevelChunks(
                    chunks, requestMetrics, cancellationToken);
            for (ChunkRecoveryResult chunkResult : chunkResults) {
                int productOffset = products.size();
                products.addAll(chunkResult.products());
                chunkResult.unresolved().stream()
                        .map(gap -> withInsertionOffset(gap, productOffset))
                        .forEach(unresolved::add);
                stats.add(chunkResult.stats());
            }
            for (int index = 0; index < products.size(); index++) {
                products.get(index).setRowNumber(index + 1);
            }
            cancellationToken.throwIfCancelled();
            finalProductCount = products.size();
            finalReviewRequiredRows = (int) products.stream()
                    .filter(ProductPreviewDto::isReviewRequired)
                    .count();
            finalUnresolvedRows = unresolved.size();

            log.info("Uyarlanabilir ayrıştırma özeti: originalChunks={}, reliableSourceRecords={}, "
                            + "initiallyExtracted={}, successfulParseCalls={}, semanticFailures={}, "
                            + "recursiveSplits={}, recoveredBySplit={}, unresolvedRecords={}, "
                            + "finalProducts={}, parallelRequests={}",
                    chunks.size(), stats.reliableSourceRecords, stats.initiallyExtracted,
                    stats.successfulParseCalls, stats.semanticFailures, stats.recursiveSplits,
                    stats.recoveredBySplit, unresolved.size(), products.size(), parallelRequests);
            return new BulkPreviewResponseDto(null, products, unresolved, unresolved.isEmpty());
        } finally {
            long totalMillis = elapsedMillis(totalStartedNanos);
            log.info("BULK_IMPORT_DIAGNOSTICS|stage=OLLAMA|jobId={}|totalRequests={}|"
                            + "extractionRequests={}|retryRequests={}|recursiveSplits={}|"
                            + "recoveryRequests={}|verifierRequests={}|averageRequestMs={}|"
                            + "maximumRequestMs={}|ollamaRequestMs={}|recoveryRequestMs={}|"
                            + "reconciliationMs={}|reconciliationRecoveryMs={}|"
                            + "nameVerificationMs={}|sourceIdentityValidationMs={}|"
                            + "successfullyReconciledRows={}|reviewRequiredRows={}|"
                            + "unresolvedRows={}|finalProducts={}|totalPipelineMs={}",
                    cancellationToken.jobId(), requestMetrics.totalRequests,
                    requestMetrics.extractionRequests(),
                    requestMetrics.retryRequests, stats.recursiveSplits,
                    requestMetrics.splitRecoveryRequests, requestMetrics.verifierRequests,
                    requestMetrics.averageRequestMillis(),
                    requestMetrics.maximumRequestMillis, requestMetrics.extractionRequestMillis(),
                    requestMetrics.retryRecoveryRequestMillis(),
                    requestMetrics.reconciliationMillis(), requestMetrics.reconciliationRecoveryMillis(),
                    requestMetrics.nameVerificationNanos / 1_000_000,
                    requestMetrics.sourceIdentityValidationNanos / 1_000_000,
                    requestMetrics.successfullyReconciledRows(), finalReviewRequiredRows,
                    finalUnresolvedRows, finalProductCount, totalMillis);
        }
    }

    private List<ChunkRecoveryResult> recoverTopLevelChunks(
            List<DocumentChunk> chunks,
            ImportRequestMetrics requestMetrics,
            BulkImportCancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();
        if (chunks.size() <= 1 || parallelRequests == 1) {
            return chunks.stream()
                    .map(chunk -> {
                        cancellationToken.throwIfCancelled();
                        return recoverTopLevelChunk(chunk, requestMetrics, cancellationToken);
                    })
                    .toList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(parallelRequests, chunks.size()));
        try {
            CompletionService<IndexedChunkRecoveryResult> completions =
                    new ExecutorCompletionService<>(executor);
            for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                cancellationToken.throwIfCancelled();
                DocumentChunk chunk = chunks.get(chunkIndex);
                int resultIndex = chunkIndex;
                Future<IndexedChunkRecoveryResult> future = completions.submit(() -> {
                    try {
                        return new IndexedChunkRecoveryResult(resultIndex,
                                recoverTopLevelChunk(chunk, requestMetrics, cancellationToken));
                    } catch (BulkImportCancelledException exception) {
                        throw exception;
                    } catch (RuntimeException exception) {
                        // Publish failure before this worker can take another queued chunk.
                        cancellationToken.recordFatalFailure(exception);
                        throw exception;
                    }
                });
                cancellationToken.track(future);
            }
            ChunkRecoveryResult[] results = new ChunkRecoveryResult[chunks.size()];
            for (int completedCount = 0; completedCount < chunks.size(); completedCount++) {
                try {
                    IndexedChunkRecoveryResult completed = completions.take().get();
                    results[completed.index()] = completed.result();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    cancellationToken.cancelAfterFailure();
                    throw new OllamaInfrastructureException(
                            "Ollama toplu ayrıştırma işlemi kesildi.", exception);
                } catch (ExecutionException exception) {
                    cancellationToken.cancelAfterFailure();
                    RuntimeException fatalFailure = cancellationToken.fatalFailure();
                    if (fatalFailure != null) {
                        throw fatalFailure;
                    }
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException("Ollama chunk işlemi başarısız oldu.", cause);
                } catch (CancellationException exception) {
                    cancellationToken.throwIfCancelled();
                    throw exception;
                }
            }
            return List.of(results);
        } finally {
            stopExecutor(executor);
        }
    }

    private void stopExecutor(ExecutorService executor) {
        executor.shutdownNow();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ChunkRecoveryResult recoverTopLevelChunk(
            DocumentChunk chunk,
            ImportRequestMetrics requestMetrics,
            BulkImportCancellationToken cancellationToken) {
        List<ProductPreviewDto> products = new ArrayList<>();
        List<UnresolvedSourceRecordDto> unresolved = new ArrayList<>();
        RecoveryStats stats = new RecoveryStats();
        stats.reliableSourceRecords = chunk.candidateRecordCount();
        activeImportMetrics.set(requestMetrics);
        activeCancellationToken.set(cancellationToken);
        try {
            cancellationToken.throwIfCancelled();
            recoverChunk(chunk, products, unresolved, stats, 0);
            return new ChunkRecoveryResult(products, unresolved, stats);
        } finally {
            activeImportMetrics.remove();
            activeCancellationToken.remove();
        }
    }

    private UnresolvedSourceRecordDto withInsertionOffset(
            UnresolvedSourceRecordDto gap,
            int productOffset) {
        return UnresolvedSourceRecordDto.builder()
                .id(gap.getId())
                .sourceType(gap.getSourceType())
                .worksheetName(gap.getWorksheetName())
                .sourceRowStart(gap.getSourceRowStart())
                .sourceRowEnd(gap.getSourceRowEnd())
                .pageNumber(gap.getPageNumber())
                .sourceRecordStart(gap.getSourceRecordStart())
                .sourceRecordEnd(gap.getSourceRecordEnd())
                .insertionIndex(productOffset + gap.getInsertionIndex())
                .sourceText(gap.getSourceText())
                .reason(gap.getReason())
                .build();
    }

    private void recoverChunk(
            DocumentChunk chunk,
            List<ProductPreviewDto> products,
            List<UnresolvedSourceRecordDto> unresolved,
            RecoveryStats stats,
            int depth) {
        activeToken().throwIfCancelled();
        try {
            ChunkParseOutcome outcome = parseChunkWithRetry(chunk, true, depth > 0);
            if (outcome.attemptsUsed() > 1) {
                stats.recordsRequiringRetryOrRecovery.addAll(sourceRecordKeys(chunk));
            }
            long verificationStartedNanos = System.nanoTime();
            verifySuspiciousProductNames(chunk, outcome.results());
            activeMetrics().addNameVerification(System.nanoTime() - verificationStartedNanos);
            products.addAll(outcome.results());
            stats.successfulParseCalls++;
            if (depth == 0) {
                stats.initiallyExtracted += outcome.results().size();
            } else {
                stats.recoveredBySplit += outcome.results().size();
            }
            log.info("Kurtarmalı chunk başarılı: depth={}, products={}, attemptsUsed={}, {}",
                    depth, outcome.results().size(), outcome.attemptsUsed(), chunk.description());
        } catch (OllamaInfrastructureException exception) {
            log.error("Ollama altyapı hatası; kısmi ön izleme oluşturulmayacak: {}",
                    exception.getMessage());
            throw exception;
        } catch (DocumentChunkParsingException exception) {
            activeToken().throwIfCancelled();
            stats.semanticFailures++;
            stats.recordsRequiringRetryOrRecovery.addAll(sourceRecordKeys(chunk));
            activeToken().throwIfCancelled();
            List<DocumentChunk> children = splitReliableChunk(chunk);
            if (!children.isEmpty()) {
                activeToken().throwIfCancelled();
                stats.recursiveSplits++;
                activeToken().recordRecursiveSplit();
                log.warn("Semantik chunk hatası güvenilir kayıt sınırlarında bölünüyor: "
                                + "depth={}, childCount={}, {}",
                        depth, children.size(), chunk.description());
                for (DocumentChunk child : children) {
                    activeToken().throwIfCancelled();
                    recoverChunk(child, products, unresolved, stats, depth + 1);
                }
                return;
            }

            UnresolvedSourceRecordDto gap = toUnresolvedRecord(chunk);
            if (gap == null) {
                throw exception;
            }
            UnresolvedSourceRecordDto locatedGap = UnresolvedSourceRecordDto.builder()
                    .id(gap.getId())
                    .sourceType(gap.getSourceType())
                    .worksheetName(gap.getWorksheetName())
                    .sourceRowStart(gap.getSourceRowStart())
                    .sourceRowEnd(gap.getSourceRowEnd())
                    .pageNumber(gap.getPageNumber())
                    .sourceRecordStart(gap.getSourceRecordStart())
                    .sourceRecordEnd(gap.getSourceRecordEnd())
                    .insertionIndex(products.size())
                    .sourceText(gap.getSourceText())
                    .reason(gap.getReason())
                    .build();
            unresolved.add(locatedGap);
            log.warn("Tek kaynak kaydı çözülemedi; ön izleme boşluğu oluşturuldu: "
                            + "gapId={}, insertionIndex={}, {}",
                    locatedGap.getId(), locatedGap.getInsertionIndex(), chunk.description());
        }
    }

    private List<DocumentChunk> splitReliableChunk(DocumentChunk chunk) {
        if (chunk.excelMetadata() != null) {
            List<ExcelChunkMetadata.SourceRecord> records = chunk.excelMetadata().sourceRecords();
            if (records.size() <= 1) {
                return List.of();
            }
            int middle = records.size() / 2;
            return List.of(
                    DocumentChunk.excelRecords(
                            chunk.sourceDocument(), chunk.worksheetName(), chunk.chunkIndex(),
                            chunk.excelMetadata().headerContext(),
                            chunk.excelMetadata().explicitProductCodeFieldAbsent(),
                            records.subList(0, middle)),
                    DocumentChunk.excelRecords(
                            chunk.sourceDocument(), chunk.worksheetName(), chunk.chunkIndex(),
                            chunk.excelMetadata().headerContext(),
                            chunk.excelMetadata().explicitProductCodeFieldAbsent(),
                            records.subList(middle, records.size())));
        }
        if (chunk.pdfMetadata() != null
                && chunk.pdfMetadata().boundaryConfidence() == PdfRecordSegmenter.Confidence.RELIABLE) {
            List<PdfRecordSegmenter.LogicalRecord> records = chunk.pdfMetadata().sourceRecords();
            if (records.size() <= 1) {
                return List.of();
            }
            int middle = records.size() / 2;
            return List.of(
                    DocumentChunk.pdfRecords(
                            chunk.sourceDocument(), chunk.pageNumber(), chunk.chunkIndex(),
                            chunk.pdfMetadata().headerContext(), records.subList(0, middle),
                            chunk.pdfMetadata().boundaryConfidence(),
                            chunk.pdfMetadata().difficultLayout()),
                    DocumentChunk.pdfRecords(
                            chunk.sourceDocument(), chunk.pageNumber(), chunk.chunkIndex(),
                            chunk.pdfMetadata().headerContext(), records.subList(middle, records.size()),
                            chunk.pdfMetadata().boundaryConfidence(),
                            chunk.pdfMetadata().difficultLayout()));
        }
        return List.of();
    }

    private UnresolvedSourceRecordDto toUnresolvedRecord(DocumentChunk chunk) {
        if (chunk.excelMetadata() != null && chunk.excelMetadata().sourceRecords().size() == 1) {
            ExcelChunkMetadata.SourceRecord record = chunk.excelMetadata().sourceRecords().get(0);
            return UnresolvedSourceRecordDto.builder()
                    .id(record.sourceIdentity())
                    .sourceType("XLSX")
                    .worksheetName(chunk.worksheetName())
                    .sourceRowStart(record.sourceRow())
                    .sourceRowEnd(record.sourceRow())
                    .sourceText(record.sourceText())
                    .reason("Bu Excel satırı tekrarlı denemelerden sonra güvenilir biçimde ayrıştırılamadı.")
                    .build();
        }
        if (chunk.pdfMetadata() != null && chunk.pdfMetadata().sourceRecords().size() == 1) {
            PdfRecordSegmenter.LogicalRecord record = chunk.pdfMetadata().sourceRecords().get(0);
            return UnresolvedSourceRecordDto.builder()
                    .id("pdf:page:" + chunk.pageNumber() + ":record:" + record.sourceRecordId())
                    .sourceType("PDF")
                    .pageNumber(chunk.pageNumber())
                    .sourceRecordStart(record.sourceRecordId())
                    .sourceRecordEnd(record.sourceRecordId())
                    .sourceText(record.sourceText())
                    .reason("Bu PDF kaydı tekrarlı denemelerden sonra güvenilir biçimde ayrıştırılamadı.")
                    .build();
        }
        return null;
    }

    private String worksheetLabel(DocumentChunk chunk) {
        if (isPdf(chunk)) {
            return "PDF page " + chunk.pageNumber();
        }
        return chunk.worksheetName() == null ? chunk.sourceType() : chunk.worksheetName();
    }

    private boolean isPdf(DocumentChunk chunk) {
        return "PDF".equals(chunk.sourceType());
    }

    private ChunkParseOutcome parseChunkWithRetry(DocumentChunk chunk) {
        return parseChunkWithRetry(chunk, false);
    }

    private ChunkParseOutcome parseChunkWithRetry(DocumentChunk chunk, boolean recoveryStrict) {
        return parseChunkWithRetry(chunk, recoveryStrict, false);
    }

    private ChunkParseOutcome parseChunkWithRetry(
            DocumentChunk chunk,
            boolean recoveryStrict,
            boolean splitRecovery) {
        RuntimeException lastFailure = null;
        List<ProductPreviewDto> bestIncompleteResult = null;
        boolean suspiciousLowDensityObserved = false;
        int totalAttempts = maxRetries + 1;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            activeToken().throwIfCancelled();
            try {
                log.info("Ollama chunk ayrıştırma başlatıldı ({}/{}): {}",
                        attempt, totalAttempts, chunk.description());
                String aiResponse = callOllama(chunk, attempt > 1, splitRecovery);
                String cleanedJson = cleanAiResponse(aiResponse);
                List<ProductPreviewDto> results = deserializeResponse(cleanedJson);
                suppressInventedProductCodes(chunk, results);
                validateResults(results);
                long identityStartedNanos = System.nanoTime();
                assignSourceContributions(chunk, results, attempt, recoveryStrict);
                activeToken().throwIfCancelled();
                long reconciliationStartedNanos = System.nanoTime();
                results = reconcileDeterministicExcelRecords(chunk, results);
                activeMetrics().addReconciliation(System.nanoTime() - reconciliationStartedNanos);
                activeMetrics().addSourceIdentityValidation(
                        System.nanoTime() - identityStartedNanos);
                validateSevereCompletenessGap(chunk, results, recoveryStrict);
                validatePdfRecordAlignment(chunk, results);

                boolean reliableCompletenessGap = hasCompletenessGap(chunk, results, recoveryStrict);
                boolean suspiciousLowDensity = hasSuspiciousPdfExtraction(chunk, results);
                if (reliableCompletenessGap || suspiciousLowDensity) {
                    if (bestIncompleteResult == null
                            || distanceFromExpected(chunk, results)
                            < distanceFromExpected(chunk, bestIncompleteResult)) {
                        bestIncompleteResult = results;
                    }
                    suspiciousLowDensityObserved |= suspiciousLowDensity;
                    if (reliableCompletenessGap) {
                        long structurallyValid = results.stream()
                                .filter(result -> !hasHardValidationError(result))
                                .count();
                        log.warn("Ollama chunk completeness/doğrulama farkı nedeniyle yeniden "
                                        + "denenecek ({}/{}): {}{}, {} çıkarılan ürün, "
                                        + "{} yapısal olarak geçerli — {}",
                                attempt, totalAttempts, chunk.candidateRecordCount(), candidateLabel(chunk),
                                results.size(), structurallyValid, chunk.description());
                    } else {
                        log.warn("PDF chunk şüpheli düşük çıkarım yoğunluğu nedeniyle yeniden denenecek "
                                        + "({}/{}): approximateItemMarkers={}, sourceCharacters={}, "
                                        + "logicalLines={}, extractedProducts={} — {}",
                                attempt, totalAttempts, chunk.approximateItemMarkerCount(),
                                chunk.sourceCharacterCount(), chunk.logicalLineCount(),
                                results.size(), chunk.description());
                    }
                    if (attempt < totalAttempts) {
                        activeToken().throwIfCancelled();
                        continue;
                    }
                    break;
                }
                return new ChunkParseOutcome(results, attempt);
            } catch (BulkImportCancelledException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("Ollama chunk ayrıştırma denemesi başarısız ({}/{}): {} — {}",
                        attempt, totalAttempts, chunk.description(), exception.getMessage());
            }
        }

        if (bestIncompleteResult != null) {
            if (lastFailure instanceof OllamaInfrastructureException infrastructureException) {
                throw infrastructureException;
            }
            if (chunk.candidateRecordCount() == 0 && suspiciousLowDensityObserved) {
                throw new DocumentChunkParsingException(
                        "Belgenin bir bölümü retrylerden sonra hâlâ şüpheli derecede az ürün "
                                + "döndürdü: yaklaşık kayıt sinyali="
                                + chunk.approximateItemMarkerCount() + ", çıkarılan ürün="
                                + bestIncompleteResult.size() + " (" + chunk.description()
                                + "). Bu yaklaşık sinyal kesin beklenen sayı olarak kullanılmadı; "
                                + "eksik olabilecek bir ön izleme oluşturulmadı.",
                        lastFailure);
            }
            String mismatchDescription;
            if (!isPdf(chunk)) {
                mismatchDescription = "eksik ayrıştırıldı";
            } else if (bestIncompleteResult.size() != chunk.candidateRecordCount()) {
                mismatchDescription = "kayıt sayısı uyuşmadan ayrıştırıldı";
            } else {
                mismatchDescription = "güvenilir kayıt doğrulaması başarısız kaldı";
            }
            throw new DocumentChunkParsingException(
                    "Belgenin bir bölümü retrylerden sonra hâlâ " + mismatchDescription + ": "
                            + chunk.candidateRecordCount() + candidateLabel(chunk) + " karşılık "
                            + bestIncompleteResult.size() + " ürün döndü (" + chunk.description()
                            + "). Eksik bir ön izleme oluşturulmadı.",
                    lastFailure);
        }

        if (lastFailure instanceof OllamaInfrastructureException infrastructureException) {
            throw infrastructureException;
        }
        throw new DocumentChunkParsingException(
                "Belgenin bir bölümü " + totalAttempts + " denemeden sonra ayrıştırılamadı ("
                        + chunk.description() + "). Eksik bir ön izleme oluşturulmadı.",
                lastFailure);
    }

    private String callOllama(DocumentChunk chunk, boolean retry, boolean splitRecovery) {
        activeToken().throwIfCancelled();
        String prompt = (retry ? RETRY_INSTRUCTION + "\n" : "") + chunk.toPromptFragment();

        return executeOllamaRequest(isPdf(chunk) ? PDF_SYSTEM_PROMPT : SYSTEM_PROMPT,
                prompt, OUTPUT_SCHEMA, null,
                retry ? RequestKind.RETRY
                        : splitRecovery ? RequestKind.SPLIT_RECOVERY : RequestKind.EXTRACTION);
    }

    private String executeOllamaRequest(
            String systemPrompt,
            String prompt,
            Map<String, Object> outputSchema,
            Integer maxOutputTokens,
            RequestKind requestKind) {

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("system", systemPrompt);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0);
        if (maxOutputTokens != null) {
            options.put("num_predict", maxOutputTokens);
        }
        requestBody.put("options", options);
        if (structuredOutputEnabled) {
            requestBody.put("format", outputSchema);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        long requestStartedNanos = System.nanoTime();
        BulkImportCancellationToken cancellationToken = activeToken();
        cancellationToken.throwIfCancelled();
        cancellationToken.recordRequestStarted(requestKind == RequestKind.RETRY);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    OLLAMA_GENERATE_ENDPOINT,
                    HttpMethod.POST,
                    entity,
                    JsonNode.class);
            cancellationToken.throwIfCancelled();

            if (response.getBody() != null && response.getBody().has("response")) {
                String generated = response.getBody().get("response").asText();
                if (generated == null || generated.isBlank()) {
                    throw new RuntimeException("Ollama boş bir response döndürdü.");
                }
                return generated;
            }
            throw new RuntimeException("Ollama yanıtında response alanı bulunamadı.");
        } catch (BulkImportCancelledException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            cancellationToken.throwIfCancelled();
            throw new OllamaInfrastructureException(
                    "Yerel AI servisine erişilemedi veya istek zaman aşımına uğradı.", exception);
        } catch (RestClientResponseException exception) {
            cancellationToken.throwIfCancelled();
            throw new OllamaInfrastructureException(
                    "Ollama API HTTP " + exception.getStatusCode().value()
                            + " hatası döndürdü.", exception);
        } catch (OllamaInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            cancellationToken.throwIfCancelled();
            if (exception.getMessage() != null && exception.getMessage().startsWith("Ollama")) {
                throw exception;
            }
            throw new RuntimeException("Ollama API isteği başarısız oldu: " + exception.getMessage(), exception);
        } finally {
            activeMetrics().record(requestKind, System.nanoTime() - requestStartedNanos);
        }
    }

    private String cleanAiResponse(String response) {
        if (response == null || response.isBlank()) {
            throw new RuntimeException("Ollama boş bir extraction yanıtı döndürdü.");
        }

        String cleaned = response.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        int startIndex = cleaned.indexOf('[');
        int endIndex = cleaned.lastIndexOf(']');
        if (startIndex < 0 || endIndex <= startIndex) {
            throw new RuntimeException("Ollama yanıtında geçerli bir JSON array bulunamadı.");
        }
        return cleaned.substring(startIndex, endIndex + 1);
    }

    private List<ProductPreviewDto> deserializeResponse(String json) {
        try {
            List<Map<String, Object>> rawItems = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {
                    });

            List<ProductPreviewDto> results = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                String productCode = getStringValue(item, "productCode");
                String productName = getStringValue(item, "productName");
                BigDecimal price = getBigDecimalValue(item, "price");
                Object rawQuantity = item.get("quantity");
                String rawQuantityText = rawQuantity == null ? "null" : rawQuantity.toString().trim();
                Integer quantity = getIntegerValue(item, "quantity");

                String parseError = quantity == null
                        ? "Geçersiz miktar (ham değer: '" + rawQuantityText + "')"
                        : null;

                results.add(ProductPreviewDto.builder()
                        .sourceRecordReference(getStringValue(item, "sourceRecordId"))
                        .productCode(productCode)
                        .productName(productName)
                        .quantity(quantity)
                        .importedQuantity(quantity)
                        .rawQuantityText(rawQuantityText)
                        .price(price)
                        .isValid(parseError == null)
                        .errorMessage(parseError)
                        .build());
            }
            return results;
        } catch (Exception exception) {
            throw new RuntimeException(
                    "AI yanıtı JSON olarak ayrıştırılamadı. Yanıt eksik veya bozuk olabilir.", exception);
        }
    }

    private ProductNameVerificationStats verifySuspiciousProductNames(
            DocumentChunk chunk,
            List<ProductPreviewDto> results) {
        boolean sourceCandidatesAligned = !chunk.sourceProductNameCandidates().isEmpty()
                && chunk.sourceProductNameCandidates().size() == results.size();
        List<SuspiciousProductName> suspiciousItems = new ArrayList<>();

        for (int resultIndex = 0; resultIndex < results.size(); resultIndex++) {
            ProductPreviewDto result = results.get(resultIndex);
            String sourceCandidate = sourceCandidateForResult(
                    chunk, result, resultIndex, sourceCandidatesAligned).orElse(null);
            ProductNameSuspicionDetector.Suspicion suspicion =
                    productNameSuspicionDetector.inspect(result, sourceCandidate);
            if (suspicion.suspicious()) {
                suspiciousItems.add(new SuspiciousProductName(
                        suspiciousItems.size() + 1,
                        result,
                        sourceCandidate,
                        suspicion));
            }
        }

        ProductNameVerificationStats stats = new ProductNameVerificationStats();
        stats.suspicionCount = suspiciousItems.size();
        if (suspiciousItems.isEmpty()) {
            return stats;
        }

        List<NameVerificationDecision> decisions = null;
        RuntimeException lastFailure = null;
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                log.info("Ürün adı ikinci aşama doğrulaması başlatıldı ({}/{}): "
                                + "suspiciousRows={}, {}",
                        attempt, totalAttempts, suspiciousItems.size(), chunk.description());
                String response = executeOllamaRequest(
                        NAME_VERIFIER_SYSTEM_PROMPT,
                        buildNameVerificationPrompt(chunk, suspiciousItems, attempt > 1),
                        NAME_VERIFICATION_SCHEMA,
                        512,
                        RequestKind.VERIFIER);
                decisions = deserializeNameVerificationResponse(
                        cleanAiResponse(response), suspiciousItems.size());
                break;
            } catch (BulkImportCancelledException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("Ürün adı ikinci aşama doğrulaması başarısız ({}/{}): {} — {}",
                        attempt, totalAttempts, chunk.description(), exception.getMessage());
            }
        }

        if (decisions == null) {
            String fallback = "Ürün adı doğrulaması tamamlanamadı; manuel kontrol gerekli.";
            suspiciousItems.forEach(item -> markReviewRequired(item.product(), fallback));
            stats.reviewRequiredCount = suspiciousItems.size();
            log.warn("Ürün adı doğrulayıcı retryleri tükendi; {} satır manuel kontrole bırakıldı: {}",
                    suspiciousItems.size(), lastFailure == null ? chunk.description() : lastFailure.getMessage());
            return stats;
        }

        Map<Integer, NameVerificationDecision> decisionsByIndex = new LinkedHashMap<>();
        decisions.forEach(decision -> decisionsByIndex.put(decision.itemIndex(), decision));
        for (SuspiciousProductName item : suspiciousItems) {
            NameVerificationDecision decision = decisionsByIndex.get(item.itemIndex());
            if (hasAuthoritativeSourceName(item)) {
                String sourceName = ProductPreviewValidation.trimToNull(item.sourceCandidate());
                if (sourceName.length() <= ProductPreviewDto.MAX_PRODUCT_NAME_LENGTH) {
                    item.product().setProductName(sourceName);
                    item.product().setReviewRequired(false);
                    item.product().setReviewMessage(null);
                    validateResult(item.product());
                    stats.correctedCount++;
                } else {
                    markReviewRequired(
                            item.product(),
                            "Kaynak ürün adı izin verilen uzunluğu aşıyor; manuel kontrol gerekli.");
                    stats.reviewRequiredCount++;
                }
                continue;
            }
            switch (decision.status()) {
                case CONFIRMED -> {
                    item.product().setReviewRequired(false);
                    item.product().setReviewMessage(null);
                    validateResult(item.product());
                    stats.confirmedCount++;
                }
                case CORRECTED -> {
                    String correctedName = ProductPreviewValidation.trimToNull(decision.productName());
                    if (isAcceptableCorrection(item, correctedName)) {
                        item.product().setProductName(correctedName);
                        item.product().setReviewRequired(false);
                        item.product().setReviewMessage(null);
                        validateResult(item.product());
                        stats.correctedCount++;
                    } else {
                        markReviewRequired(
                                item.product(),
                                "Önerilen ürün adı güvenilir bulunamadı; manuel kontrol gerekli.");
                        stats.reviewRequiredCount++;
                    }
                }
                case REVIEW_REQUIRED -> {
                    markReviewRequired(
                            item.product(),
                            safeReviewMessage(decision.reason(), item.suspicion().userMessage()));
                    stats.reviewRequiredCount++;
                }
            }
        }
        return stats;
    }

    Optional<String> sourceCandidateForResult(
            DocumentChunk chunk,
            ProductPreviewDto result,
            int resultIndex,
            boolean positionallyAligned) {
        if (chunk.excelMetadata() != null
                && result.getContributingSourceRecordIds() != null
                && result.getContributingSourceRecordIds().size() == 1) {
            String contributionId = ProductPreviewValidation.trimToNull(
                    result.getContributingSourceRecordIds().get(0));
            if (contributionId == null) {
                return Optional.empty();
            }
            return chunk.excelMetadata().allSourceRows().stream()
                    .filter(record -> contributionId.equals(record.sourceIdentity()))
                    .findFirst()
                    .flatMap(record -> Optional.ofNullable(
                            ProductPreviewValidation.trimToNull(record.productNameCandidate())));
        }
        return positionallyAligned
                ? Optional.ofNullable(ProductPreviewValidation.trimToNull(
                        chunk.sourceProductNameCandidates().get(resultIndex)))
                : Optional.empty();
    }

    private boolean hasAuthoritativeSourceName(SuspiciousProductName item) {
        return ProductPreviewValidation.trimToNull(item.sourceCandidate()) != null
                && item.suspicion().signals().contains("Kaynak Malzeme Adı alanıyla eşleşmiyor");
    }

    private String buildNameVerificationPrompt(
            DocumentChunk chunk,
            List<SuspiciousProductName> suspiciousItems,
            boolean retry) {
        StringBuilder prompt = new StringBuilder();
        if (retry) {
            prompt.append("The previous verifier response was invalid. Return every requested itemIndex once.\n\n");
        }
        prompt.append(chunk.toPromptFragment())
                .append("\n\nINITIAL SUSPICIOUS EXTRACTIONS TO VERIFY:\n");
        for (SuspiciousProductName item : suspiciousItems) {
            ProductPreviewDto product = item.product();
            prompt.append("ITEM ").append(item.itemIndex()).append(":\n")
                    .append("productCode: ").append(product.getProductCode()).append('\n')
                    .append("productName: ").append(product.getProductName()).append('\n')
                    .append("quantity: ").append(product.getQuantity()).append('\n');
            if (ProductPreviewValidation.trimToNull(item.sourceCandidate()) != null) {
                prompt.append("source field labelled as product/material name: ")
                        .append(item.sourceCandidate()).append('\n');
            }
            prompt.append('\n');
        }
        return prompt.append("Verify only these items. Do not add or omit itemIndex values.").toString();
    }

    private List<NameVerificationDecision> deserializeNameVerificationResponse(
            String json,
            int expectedItemCount) {
        try {
            List<Map<String, Object>> rawItems = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {
                    });
            if (rawItems.size() != expectedItemCount) {
                throw new IllegalArgumentException("Doğrulayıcı beklenen sayıda sonuç döndürmedi.");
            }

            List<Integer> rawIndexes = new ArrayList<>(rawItems.size());
            for (Map<String, Object> rawItem : rawItems) {
                Object rawIndex = rawItem.get("itemIndex");
                if (!(rawIndex instanceof Number number)) {
                    throw new IllegalArgumentException("Doğrulayıcı itemIndex alanı geçersiz.");
                }
                rawIndexes.add(number.intValue());
            }
            Set<Integer> indexSet = new HashSet<>(rawIndexes);
            boolean singleItem = expectedItemCount == 1 && indexSet.size() == 1;
            boolean zeroBased = isCompleteIndexSet(indexSet, 0, expectedItemCount);
            boolean oneBased = isCompleteIndexSet(indexSet, 1, expectedItemCount);
            if (indexSet.size() != expectedItemCount || (!singleItem && !zeroBased && !oneBased)) {
                throw new IllegalArgumentException(
                        "Doğrulayıcı itemIndex kümesi geçersiz: " + indexSet);
            }

            Map<Integer, NameVerificationDecision> indexed = new LinkedHashMap<>();
            for (int position = 0; position < rawItems.size(); position++) {
                Map<String, Object> rawItem = rawItems.get(position);
                int itemIndex = singleItem
                        ? 1
                        : zeroBased ? rawIndexes.get(position) + 1 : rawIndexes.get(position);
                String rawStatus = getStringValue(rawItem, "status");
                NameVerificationStatus status = NameVerificationStatus.valueOf(rawStatus);
                indexed.put(itemIndex, new NameVerificationDecision(
                        itemIndex,
                        status,
                        getStringValue(rawItem, "productName"),
                        getStringValue(rawItem, "reason")));
            }

            for (int itemIndex = 1; itemIndex <= expectedItemCount; itemIndex++) {
                if (!indexed.containsKey(itemIndex)) {
                    throw new IllegalArgumentException("Doğrulayıcı bir itemIndex sonucunu atladı.");
                }
            }
            return new ArrayList<>(indexed.values());
        } catch (Exception exception) {
            throw new RuntimeException(
                    "Ürün adı doğrulayıcı yanıtı geçersiz: " + exception.getMessage(),
                    exception);
        }
    }

    private boolean isCompleteIndexSet(Set<Integer> indexes, int firstIndex, int count) {
        for (int offset = 0; offset < count; offset++) {
            if (!indexes.contains(firstIndex + offset)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAcceptableCorrection(SuspiciousProductName item, String correctedName) {
        if (correctedName == null || correctedName.length() > ProductPreviewDto.MAX_PRODUCT_NAME_LENGTH) {
            return false;
        }
        String originalName = item.product().getProductName();
        item.product().setProductName(correctedName);
        ProductNameSuspicionDetector.Suspicion correctedSuspicion =
                productNameSuspicionDetector.inspect(item.product(), item.sourceCandidate());
        item.product().setProductName(originalName);
        return !correctedSuspicion.suspicious();
    }

    private String safeReviewMessage(String reason, String fallback) {
        String normalized = ProductPreviewValidation.trimToNull(reason);
        if (normalized == null) {
            return fallback;
        }
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }

    private void markReviewRequired(ProductPreviewDto product, String message) {
        product.setReviewRequired(true);
        product.setReviewMessage(message);
        validateResult(product);
    }

    private void validateResults(List<ProductPreviewDto> results) {
        for (ProductPreviewDto dto : results) {
            validateResult(dto);
        }
    }

    private void validateResult(ProductPreviewDto dto) {
        List<String> errors = ProductPreviewValidation.structuralErrors(dto);
        dto.setValid(errors.isEmpty() && !dto.isReviewRequired());
        dto.setErrorMessage(errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void validateSevereCompletenessGap(
            DocumentChunk chunk,
            List<ProductPreviewDto> results,
            boolean recoveryStrict) {
        int obviousRecords = chunk.candidateRecordCount();
        boolean excelEvidence = "XLSX".equals(chunk.sourceType())
                && (obviousRecords >= 5
                || recoveryStrict && chunk.excelMetadata() != null && obviousRecords > 0);
        boolean pdfEvidence = isPdf(chunk) && obviousRecords > 0;
        if (!excelEvidence && !pdfEvidence) {
            return;
        }

        int extractedRecords = effectiveExtractedRecordCount(chunk, results);
        if (extractedRecords == 0 || extractedRecords * 2 < obviousRecords) {
            throw new RuntimeException(
                    "Ollama çıktısı muhafazakâr completeness kontrolünde şüpheli bulundu: "
                            + obviousRecords + candidateLabel(chunk) + " karşılık "
                            + extractedRecords + " ürün döndü.");
        }
    }

    private boolean hasCompletenessGap(
            DocumentChunk chunk,
            List<ProductPreviewDto> results,
            boolean recoveryStrict) {
        boolean excelGap = "XLSX".equals(chunk.sourceType())
                && (chunk.candidateRecordCount() >= 5
                && effectiveExtractedRecordCount(chunk, results) < chunk.candidateRecordCount()
                || recoveryStrict
                && chunk.excelMetadata() != null
                && chunk.candidateRecordCount() > 0
                && (effectiveExtractedRecordCount(chunk, results) != chunk.candidateRecordCount()
                || results.stream().anyMatch(this::hasHardValidationError)));
        boolean pdfGap = isPdf(chunk)
                && chunk.candidateRecordCount() > 0
                && (results.size() != chunk.candidateRecordCount()
                || chunk.pdfMetadata() != null
                && chunk.pdfMetadata().boundaryConfidence()
                == PdfRecordSegmenter.Confidence.RELIABLE
                && results.stream().anyMatch(this::hasHardValidationError));
        return excelGap || pdfGap;
    }

    private int distanceFromExpected(DocumentChunk chunk, List<ProductPreviewDto> results) {
        if (chunk.candidateRecordCount() <= 0) {
            return Integer.MAX_VALUE - results.size();
        }
        return Math.abs(chunk.candidateRecordCount()
                - effectiveExtractedRecordCount(chunk, results));
    }

    private int effectiveExtractedRecordCount(
            DocumentChunk chunk,
            List<ProductPreviewDto> results) {
        if (!"XLSX".equals(chunk.sourceType())) {
            return results.size();
        }
        Set<String> physicalRecords = results.stream()
                .filter(result -> result.getContributingSourceRecordIds() != null)
                .flatMap(result -> result.getContributingSourceRecordIds().stream())
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        boolean hasTrustedExcelRecords = chunk.excelMetadata() != null
                && !chunk.excelMetadata().sourceRecords().isEmpty();
        return hasTrustedExcelRecords ? physicalRecords.size() : results.size();
    }

    private boolean hasSuspiciousPdfExtraction(
            DocumentChunk chunk,
            List<ProductPreviewDto> results) {
        if (!isPdf(chunk) || chunk.candidateRecordCount() > 0) {
            return false;
        }

        int extractedProducts = results.size();
        boolean largeSourceReturnedNothing = extractedProducts == 0
                && chunk.sourceCharacterCount() >= 1_000
                && chunk.logicalLineCount() >= 8;
        boolean repeatedMarkersHaveVeryLowYield = chunk.approximateItemMarkerCount() >= 8
                && extractedProducts * 2 < chunk.approximateItemMarkerCount();
        return largeSourceReturnedNothing || repeatedMarkersHaveVeryLowYield;
    }

    private boolean hasHardValidationError(ProductPreviewDto product) {
        return ProductPreviewValidation.trimToNull(product.getErrorMessage()) != null;
    }

    private String candidateLabel(DocumentChunk chunk) {
        return isPdf(chunk)
                ? " güvenilir PDF ürün adayına"
                : " açıkça numaralandırılmış satıra";
    }

    private void suppressInventedProductCodes(
            DocumentChunk chunk,
            List<ProductPreviewDto> results) {
        if (isPdf(chunk) && chunk.pdfMetadata() != null
                && chunk.pdfMetadata().sourceRecords().size() == results.size()) {
            int clearedCodes = 0;
            for (int index = 0; index < results.size(); index++) {
                PdfRecordSegmenter.LogicalRecord sourceRecord =
                        chunk.pdfMetadata().sourceRecords().get(index);
                ProductPreviewDto result = results.get(index);
                if (sourceRecord.productCodeAnchor() != null) {
                    result.setProductCode(sourceRecord.productCodeAnchor());
                } else if (sourceRecord.explicitProductCodeAbsent()
                        && ProductPreviewValidation.trimToNull(result.getProductCode()) != null) {
                    result.setProductCode(null);
                    clearedCodes++;
                }
            }
            if (clearedCodes > 0) {
                log.warn("PDF logical record kaynaklarında açık kod bulunmadığı için {} AI kod "
                                + "değeri temizlendi: {}",
                        clearedCodes, chunk.description());
            }
            return;
        }
        if (!chunk.explicitProductCodeFieldAbsent()) {
            return;
        }

        long clearedCodes = results.stream()
                .filter(result -> ProductPreviewValidation.trimToNull(result.getProductCode()) != null)
                .peek(result -> result.setProductCode(null))
                .count();
        if (clearedCodes > 0) {
            log.warn("Kaynak başlığında ürün kodu alanı bulunmadığı için {} AI kod değeri temizlendi: {}",
                    clearedCodes, chunk.description());
        }
    }

    private void validatePdfRecordAlignment(
            DocumentChunk chunk,
            List<ProductPreviewDto> results) {
        if (!isPdf(chunk) || chunk.pdfMetadata() == null
                || chunk.pdfMetadata().boundaryConfidence()
                != PdfRecordSegmenter.Confidence.RELIABLE
                || results.size() != chunk.pdfMetadata().sourceRecords().size()) {
            return;
        }

        List<PdfRecordSegmenter.LogicalRecord> sourceRecords =
                chunk.pdfMetadata().sourceRecords();
        Map<String, Integer> seenOutputNames = new LinkedHashMap<>();
        for (int index = 0; index < sourceRecords.size(); index++) {
            PdfRecordSegmenter.LogicalRecord source = sourceRecords.get(index);
            ProductPreviewDto output = results.get(index);
            stripMatchingPdfRecordPrefix(output, source.sourceRecordId());
            String outputName = normalizeForAlignment(output.getProductName());
            String sourceName = normalizeForAlignment(source.productNameAnchor());

            if (sourceName != null && !namesStructurallyMatch(sourceName, outputName)) {
                int otherSourceIndex = matchingSourceIndex(sourceRecords, outputName, index);
                String detail = otherSourceIndex >= 0
                        ? "çıktı adı başka bir RECORD ile eşleşiyor (sourceRecord="
                                + sourceRecords.get(otherSourceIndex).sourceRecordId() + ")"
                        : "çıktı adı güvenilir kaynak ad sınırıyla eşleşmiyor";
                throw new RuntimeException("PDF record hizalama kontrolü başarısız: record="
                        + source.sourceRecordId() + ", " + detail);
            }
            if (sourceName != null) {
                output.setProductName(source.productNameAnchor());
                validateResult(output);
                outputName = sourceName;
            }

            if (source.quantityAnchor() != null && output.getQuantity() != null
                    && !source.quantityAnchor().equals(output.getQuantity())) {
                int otherQuantityIndex = matchingQuantityIndex(
                        sourceRecords, output.getQuantity(), index);
                if (otherQuantityIndex >= 0) {
                    throw new RuntimeException("PDF record alan kayması algılandı: record="
                            + source.sourceRecordId() + " miktarı sourceRecord="
                            + sourceRecords.get(otherQuantityIndex).sourceRecordId()
                            + " miktarıyla eşleşiyor");
                }
            }

            if (outputName != null) {
                Integer earlier = seenOutputNames.putIfAbsent(outputName, index);
                if (earlier != null) {
                    String earlierSource = normalizeForAlignment(
                            sourceRecords.get(earlier).productNameAnchor());
                    if (earlierSource != null && sourceName != null
                            && !earlierSource.equals(sourceName)) {
                        throw new RuntimeException("PDF record hizalama kontrolü başarısız: farklı "
                                + "kaynak kayıtlar aynı ürün adı çıktısını üretti");
                    }
                }
            }
        }
    }

    private void stripMatchingPdfRecordPrefix(
            ProductPreviewDto output,
            int sourceRecordId) {
        String productName = ProductPreviewValidation.trimToNull(output.getProductName());
        if (productName == null) {
            return;
        }
        String withoutPrefix = productName.replaceFirst(
                "^\\s*" + sourceRecordId + "\\s*[.)\\-:]?\\s+", "");
        if (!withoutPrefix.equals(productName) && !withoutPrefix.isBlank()) {
            output.setProductName(withoutPrefix.trim());
            validateResult(output);
        }
    }

    private int matchingSourceIndex(
            List<PdfRecordSegmenter.LogicalRecord> records,
            String outputName,
            int currentIndex) {
        if (outputName == null) {
            return -1;
        }
        for (int index = 0; index < records.size(); index++) {
            if (index == currentIndex) {
                continue;
            }
            String candidate = normalizeForAlignment(records.get(index).productNameAnchor());
            if (candidate != null && namesStructurallyMatch(candidate, outputName)) {
                return index;
            }
        }
        return -1;
    }

    private int matchingQuantityIndex(
            List<PdfRecordSegmenter.LogicalRecord> records,
            Integer outputQuantity,
            int currentIndex) {
        for (int index = 0; index < records.size(); index++) {
            if (index != currentIndex
                    && outputQuantity.equals(records.get(index).quantityAnchor())) {
                return index;
            }
        }
        return -1;
    }

    private boolean namesStructurallyMatch(String sourceName, String outputName) {
        if (sourceName == null || outputName == null) {
            return false;
        }
        return sourceName.equals(outputName)
                || sourceName.length() >= 4 && outputName.contains(sourceName)
                || outputName.length() >= 4 && sourceName.contains(outputName);
    }

    /**
     * Attaches stable source identities after parsing. If an AI response repeats one
     * Excel row, both DTOs receive the same identity and downstream consolidation
     * can count that row once without weakening legitimate multi-row consolidation.
     */
    private void assignSourceContributions(
            DocumentChunk chunk,
            List<ProductPreviewDto> results,
            int attempt,
            boolean recoveryPipeline) {
        if (results.isEmpty()) {
            return;
        }
        if (chunk.excelMetadata() != null) {
            assignExcelSourceContributions(chunk, results, attempt, recoveryPipeline);
            return;
        }
        if (chunk.pdfMetadata() != null
                && chunk.pdfMetadata().boundaryConfidence()
                == PdfRecordSegmenter.Confidence.RELIABLE
                && chunk.pdfMetadata().sourceRecords().size() == results.size()) {
            for (int index = 0; index < results.size(); index++) {
                int recordId = chunk.pdfMetadata().sourceRecords().get(index).sourceRecordId();
                results.get(index).setContributingSourceRecordIds(List.of(
                        "pdf:page:" + chunk.pageNumber() + ":record:" + recordId));
            }
        }
    }

    private void assignExcelSourceContributions(
            DocumentChunk chunk,
            List<ProductPreviewDto> results,
            int attempt,
            boolean recoveryPipeline) {
        List<ExcelChunkMetadata.SourceRecord> sourceRows =
                chunk.excelMetadata().allSourceRows();
        if (sourceRows.isEmpty()) {
            for (ProductPreviewDto result : results) {
                result.setSourceIdentityReviewRequired(true);
                markReviewRequired(result,
                        "Kaynak Excel satırı tek ve güvenilir biçimde belirlenemedi.");
                log.warn("Excel kaynak kimliği belirlenemedi; güvenilir fiziksel tablo satırı yok: "
                                + "worksheet={}, parsedName={}, parsedQuantity={}, chunk={}, attempt={}, pipeline={}",
                        chunk.worksheetName(), result.getProductName(), result.getQuantity(),
                        chunk.chunkIndex(), attempt,
                        recoveryPipeline ? "RECOVERY" : "NORMAL");
            }
            return;
        }

        for (ProductPreviewDto result : results) {
            String suppliedReference = ProductPreviewValidation.trimToNull(
                    result.getSourceRecordReference());
            if (suppliedReference != null) {
                List<ExcelChunkMetadata.SourceRecord> referencedRows = sourceRows.stream()
                        .filter(row -> suppliedReference.equals(row.sourceIdentity()))
                        .toList();
                if (referencedRows.size() != 1) {
                    result.setSourceIdentityReviewRequired(true);
                    markReviewRequired(result,
                            "Kaynak Excel satırı tek ve güvenilir biçimde belirlenemedi.");
                    log.warn("Excel kaynak referansı doğrulanamadı: worksheet={}, suppliedReference={}, "
                                    + "parsedName={}, parsedQuantity={}, chunk={}, attempt={}, pipeline={}",
                            chunk.worksheetName(), suppliedReference, result.getProductName(),
                            result.getQuantity(), chunk.chunkIndex(), attempt,
                            recoveryPipeline ? "RECOVERY" : "NORMAL");
                    continue;
                }
                ExcelChunkMetadata.SourceRecord selected = referencedRows.get(0);
                result.setContributingSourceRecordIds(List.of(selected.sourceIdentity()));
                log.info("Excel miktar katkısı güvenilir fiziksel kaynak referansına bağlandı: worksheet={}, "
                                + "physicalRow={}, parsedName={}, parsedQuantity={}, contributionId={}, "
                                + "chunk={}, attempt={}, pipeline={}",
                        chunk.worksheetName(), selected.sourceRow(), result.getProductName(),
                        result.getQuantity(), selected.sourceIdentity(), chunk.chunkIndex(), attempt,
                        recoveryPipeline ? "RECOVERY" : "NORMAL");
                continue;
            }
            List<ExcelChunkMetadata.SourceRecord> candidates = sourceRows.stream()
                    .filter(row -> excelRowMatches(row, result))
                    .toList();
            if (candidates.size() != 1) {
                result.setSourceIdentityReviewRequired(true);
                markReviewRequired(result,
                        "Kaynak Excel satırı tek ve güvenilir biçimde belirlenemedi.");
                log.warn("Excel kaynak kimliği belirsiz: worksheet={}, parsedName={}, parsedQuantity={}, "
                                + "candidateRows={}, chunk={}, attempt={}, pipeline={}",
                        chunk.worksheetName(), result.getProductName(), result.getQuantity(),
                        candidates.stream().map(ExcelChunkMetadata.SourceRecord::sourceRow).toList(),
                        chunk.chunkIndex(), attempt,
                        recoveryPipeline ? "RECOVERY" : "NORMAL");
                continue;
            }

            ExcelChunkMetadata.SourceRecord selected = candidates.get(0);
            String contributionId = selected.sourceIdentity();
            result.setContributingSourceRecordIds(List.of(contributionId));
            log.info("Excel miktar katkısı eski yanıt için kesin alan çapalarıyla hizalandı: "
                            + "worksheet={}, physicalRow={}, parsedName={}, "
                            + "parsedQuantity={}, contributionId={}, chunk={}, attempt={}, pipeline={}",
                    chunk.worksheetName(), selected.sourceRow(), result.getProductName(),
                    result.getQuantity(), contributionId, chunk.chunkIndex(), attempt,
                    recoveryPipeline ? "RECOVERY" : "NORMAL");
        }
    }

    private boolean excelRowMatches(
            ExcelChunkMetadata.SourceRecord source,
            ProductPreviewDto output) {
        String outputName = normalizeForAlignment(output.getProductName());
        if (outputName == null) {
            return false;
        }
        String sourceName = normalizeForAlignment(source.productNameCandidate());
        return sourceName != null
                && sourceName.equals(outputName)
                && source.quantityCandidate() != null
                && source.quantityCandidate().equals(output.getQuantity());
    }

    /**
     * A structured Excel table already provides authoritative physical-row,
     * product-name and quantity anchors. Ollama still processes the batch, but
     * omissions, duplicates and reordered outputs are reconciled against those
     * anchors instead of recursively reprocessing otherwise trustworthy rows.
     * If a source has an explicit product-code field, trusted returned rows are
     * still reconciled, but omitted rows stay on the conservative recovery path
     * because the deterministic metadata does not anchor that code field.
     */
    private List<ProductPreviewDto> reconcileDeterministicExcelRecords(
            DocumentChunk chunk,
            List<ProductPreviewDto> aiResults) {
        if (chunk.excelMetadata() == null
                || chunk.excelMetadata().sourceRecords().isEmpty()
                || chunk.excelMetadata().sourceRecords().stream().anyMatch(record ->
                        ProductPreviewValidation.trimToNull(record.productNameCandidate()) == null
                                || record.quantityCandidate() == null
                                || record.quantityCandidate() < 1)) {
            return aiResults;
        }

        Map<String, ProductPreviewDto> trustedBySource = new LinkedHashMap<>();
        int duplicateRepresentations = 0;
        for (ProductPreviewDto result : aiResults) {
            List<String> contributionIds = result.getContributingSourceRecordIds();
            if (result.isSourceIdentityReviewRequired()
                    || contributionIds == null || contributionIds.size() != 1) {
                continue;
            }
            ProductPreviewDto previous = trustedBySource.putIfAbsent(
                    contributionIds.get(0), result);
            if (previous != null) {
                duplicateRepresentations++;
            }
        }

        List<ProductPreviewDto> reconciled = new ArrayList<>();
        int deterministicRecoveries = 0;
        boolean mayRecoverOmittedRows = chunk.excelMetadata().explicitProductCodeFieldAbsent();
        for (ExcelChunkMetadata.SourceRecord source : chunk.excelMetadata().sourceRecords()) {
            ProductPreviewDto result = trustedBySource.get(source.sourceIdentity());
            if (result == null) {
                if (!mayRecoverOmittedRows) {
                    continue;
                }
                deterministicRecoveries++;
                result = ProductPreviewDto.builder()
                        .sourceRecordReference(source.sourceIdentity())
                        .productCode(null)
                        .productName(source.productNameCandidate())
                        .quantity(source.quantityCandidate())
                        .importedQuantity(source.quantityCandidate())
                        .rawQuantityText(String.valueOf(source.quantityCandidate()))
                        .isValid(true)
                        .contributingSourceRecordIds(List.of(source.sourceIdentity()))
                        .authoritativeSourceProductNames(Map.of(
                                source.sourceIdentity(), source.productNameCandidate()))
                        .build();
            } else {
                // Preserve the exact source spelling and deterministic quantity.
                result.setProductName(source.productNameCandidate());
                result.setQuantity(source.quantityCandidate());
                result.setImportedQuantity(source.quantityCandidate());
                result.setRawQuantityText(String.valueOf(source.quantityCandidate()));
                result.setContributingSourceRecordIds(List.of(source.sourceIdentity()));
                result.setAuthoritativeSourceProductNames(Map.of(
                        source.sourceIdentity(), source.productNameCandidate()));
                result.setSourceIdentityReviewRequired(false);
            }
            validateResult(result);
            reconciled.add(result);
        }

        int untrustedOrExtraOutputs = Math.max(0,
                aiResults.size() - trustedBySource.size() - duplicateRepresentations);
        if (deterministicRecoveries > 0 || duplicateRepresentations > 0
                || untrustedOrExtraOutputs > 0) {
            log.info("Yapılandırılmış Excel batch'i fiziksel satır çapalarına reconcile edildi: "
                            + "worksheet={}, chunk={}, sourceRecords={}, aiOutputs={}, "
                            + "deterministicRecoveries={}, duplicateRepresentations={}, "
                            + "untrustedOrExtraOutputs={}",
                    chunk.worksheetName(), chunk.chunkIndex(), reconciled.size(), aiResults.size(),
                    deterministicRecoveries, duplicateRepresentations, untrustedOrExtraOutputs);
        }
        activeMetrics().addSuccessfullyReconciledRows(reconciled.size());
        return reconciled;
    }

    private String normalizeForAlignment(String value) {
        String normalized = ProductPreviewValidation.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        return Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replaceAll("^(?:urun|malzeme|product|item)(?:\\s+adi)?\\s*:\\s*", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String pdfBoundaryConfidence(DocumentChunk chunk) {
        return chunk.pdfMetadata() == null
                ? "UNKNOWN"
                : chunk.pdfMetadata().boundaryConfidence().name();
    }

    private int pdfSourceRecordCount(DocumentChunk chunk) {
        return chunk.pdfMetadata() == null ? 0 : chunk.pdfMetadata().sourceRecords().size();
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : ProductPreviewValidation.trimToNull(value.toString());
    }

    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Long longValue) {
            return Math.toIntExact(longValue);
        }
        if (value instanceof Double doubleValue) {
            return (int) Math.round(doubleValue);
        }
        if (value instanceof Float floatValue) {
            return Math.round(floatValue);
        }

        String raw = value.toString().trim();
        String digits = raw.replaceAll("[^\\d]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal getBigDecimalValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            String normalized = value.toString().replace(',', '.').replaceAll("[^\\d.-]", "");
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ImportRequestMetrics activeMetrics() {
        ImportRequestMetrics metrics = activeImportMetrics.get();
        return metrics == null ? new ImportRequestMetrics() : metrics;
    }

    private BulkImportCancellationToken activeToken() {
        BulkImportCancellationToken token = activeCancellationToken.get();
        return token == null ? BulkImportCancellationToken.none() : token;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private Set<String> sourceRecordKeys(DocumentChunk chunk) {
        if (chunk.excelMetadata() != null) {
            return chunk.excelMetadata().sourceRecords().stream()
                    .map(ExcelChunkMetadata.SourceRecord::sourceIdentity)
                    .filter(identifer -> identifer != null && !identifer.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (chunk.pdfMetadata() != null) {
            return chunk.pdfMetadata().sourceRecords().stream()
                    .map(record -> "pdf:page:" + chunk.pageNumber()
                            + ":record:" + record.sourceRecordId())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        return Set.of();
    }

    private enum RequestKind {
        EXTRACTION,
        RETRY,
        SPLIT_RECOVERY,
        VERIFIER
    }

    private static final class ImportRequestMetrics {
        private int totalRequests;
        private int initialExtractionRequests;
        private int retryRequests;
        private int splitRecoveryRequests;
        private int verifierRequests;
        private long totalRequestNanos;
        private long initialExtractionRequestNanos;
        private long retryRequestNanos;
        private long splitRecoveryRequestNanos;
        private long verifierRequestNanos;
        private long maximumRequestMillis;
        private long nameVerificationNanos;
        private long sourceIdentityValidationNanos;
        private long reconciliationNanos;
        private int successfullyReconciledRows;

        private synchronized void record(RequestKind kind, long durationNanos) {
            totalRequests++;
            totalRequestNanos += durationNanos;
            maximumRequestMillis = Math.max(maximumRequestMillis, durationNanos / 1_000_000);
            switch (kind) {
                case EXTRACTION -> {
                    initialExtractionRequests++;
                    initialExtractionRequestNanos += durationNanos;
                }
                case RETRY -> {
                    retryRequests++;
                    retryRequestNanos += durationNanos;
                }
                case SPLIT_RECOVERY -> {
                    splitRecoveryRequests++;
                    splitRecoveryRequestNanos += durationNanos;
                }
                case VERIFIER -> {
                    verifierRequests++;
                    verifierRequestNanos += durationNanos;
                }
            }
        }

        private synchronized void addNameVerification(long durationNanos) {
            nameVerificationNanos += durationNanos;
        }

        private synchronized void addSourceIdentityValidation(long durationNanos) {
            sourceIdentityValidationNanos += durationNanos;
        }

        private synchronized void addReconciliation(long durationNanos) {
            reconciliationNanos += durationNanos;
        }

        private synchronized void addSuccessfullyReconciledRows(int rowCount) {
            successfullyReconciledRows += rowCount;
        }

        private int extractionRequests() {
            return initialExtractionRequests + retryRequests + splitRecoveryRequests;
        }

        private long extractionRequestMillis() {
            return (initialExtractionRequestNanos + retryRequestNanos
                    + splitRecoveryRequestNanos) / 1_000_000;
        }

        private long retryRecoveryRequestMillis() {
            return (retryRequestNanos + splitRecoveryRequestNanos) / 1_000_000;
        }

        private long averageRequestMillis() {
            return totalRequests == 0 ? 0 : totalRequestNanos / totalRequests / 1_000_000;
        }

        private long reconciliationMillis() {
            return reconciliationNanos / 1_000_000;
        }

        private long reconciliationRecoveryMillis() {
            return reconciliationMillis() + retryRecoveryRequestMillis();
        }

        private int successfullyReconciledRows() {
            return successfullyReconciledRows;
        }
    }

    private static final class WorksheetParsingStats {
        private int chunkCount;
        private int sourceRowCount;
        private int sourceCharacterCount;
        private int logicalLineCount;
        private int candidateRecordCount;
        private int approximateItemMarkerCount;
        private int extractedProductCount;
        private long validProductCount;
        private long reviewRequiredCount;
        private long invalidProductCount;
        private int retryCount;
        private String recordBoundaryConfidence = "UNKNOWN";
    }

    private enum NameVerificationStatus {
        CONFIRMED,
        CORRECTED,
        REVIEW_REQUIRED
    }

    private record NameVerificationDecision(
            int itemIndex,
            NameVerificationStatus status,
            String productName,
            String reason) {
    }

    private record ChunkParseOutcome(
            List<ProductPreviewDto> results,
            int attemptsUsed) {
    }

    private record SuspiciousProductName(
            int itemIndex,
            ProductPreviewDto product,
            String sourceCandidate,
            ProductNameSuspicionDetector.Suspicion suspicion) {
    }

    private static final class ProductNameVerificationStats {
        private int suspicionCount;
        private int confirmedCount;
        private int correctedCount;
        private int reviewRequiredCount;

        private void add(ProductNameVerificationStats other) {
            suspicionCount += other.suspicionCount;
            confirmedCount += other.confirmedCount;
            correctedCount += other.correctedCount;
            reviewRequiredCount += other.reviewRequiredCount;
        }
    }

    private static final class RecoveryStats {
        private int reliableSourceRecords;
        private int initiallyExtracted;
        private int successfulParseCalls;
        private int semanticFailures;
        private int recursiveSplits;
        private int recoveredBySplit;
        private final Set<String> recordsRequiringRetryOrRecovery = new HashSet<>();

        private void add(RecoveryStats other) {
            reliableSourceRecords += other.reliableSourceRecords;
            initiallyExtracted += other.initiallyExtracted;
            successfulParseCalls += other.successfulParseCalls;
            semanticFailures += other.semanticFailures;
            recursiveSplits += other.recursiveSplits;
            recoveredBySplit += other.recoveredBySplit;
            recordsRequiringRetryOrRecovery.addAll(other.recordsRequiringRetryOrRecovery);
        }
    }

    private record ChunkRecoveryResult(
            List<ProductPreviewDto> products,
            List<UnresolvedSourceRecordDto> unresolved,
            RecoveryStats stats) {
    }

    private record IndexedChunkRecoveryResult(int index, ChunkRecoveryResult result) {
    }
}

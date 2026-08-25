package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaRecoverableParsingTest {

    private OllamaParsingService service;
    private ObjectMapper objectMapper;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resetService(0);
    }

    private void resetService(int maxRetries) {
        service = new OllamaParsingService(
                new RestTemplateBuilder(), objectMapper, "http://localhost:11434",
                "test-model", 5, maxRetries, true);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void tenExcelRowsRetryThenSplitFivePlusFiveWithoutLoss() throws Exception {
        resetService(1);
        expect(productsRange(31, 9));
        expect(productsRange(31, 9));
        expect(productsRange(31, 5));
        expect(productsRange(36, 5));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(excelChunk(31, 10)));

        assertThat(preview.getProducts()).hasSize(10);
        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getProductName)
                .containsExactlyElementsOf(productNames(31, 10));
        assertThat(preview.getUnresolvedRecords()).isEmpty();
        server.verify();
    }

    @Test
    void anchoredExcelOmissionIsReconciledWithoutRetryOrRecursiveSplit() throws Exception {
        expect(productsRange(31, 9));
        List<ExcelChunkMetadata.SourceRecord> records = java.util.stream.IntStream
                .range(31, 41)
                .mapToObj(row -> new ExcelChunkMetadata.SourceRecord(
                        row, "ROW " + row + ":\n" + row + " | Ürün-" + row + " | 1",
                        "Ürün-" + row, 1))
                .toList();
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "test.xlsx", "EK-3", 4, "Sıra | Malzeme Adı | Miktar", true, records);

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).hasSize(10);
        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getProductName)
                .containsExactlyElementsOf(productNames(31, 10));
        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getQuantity)
                .containsOnly(1);
        assertThat(preview.getUnresolvedRecords()).isEmpty();
        server.verify();
    }

    @Test
    void trustedExcelReferenceAcceptsFormattingDifferenceAndKeepsAuthoritativeFields() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(Map.of(
                "sourceRecordId", "xlsx:EK-1:row:23",
                "productCode", "BG-1",
                "productName", "Boya_ Guaj",
                "quantity", 99))));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "real-pattern.xlsx", "EK-1", 3,
                "Kod | Malzeme Adı | Miktar", false,
                List.of(excelRecord(23, "Boya Guaj", 15)));

        BulkImportCancellationToken token = new BulkImportCancellationToken(
                "11111111-1111-1111-1111-111111111111", true);
        token.markStarted();
        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk), token);

        assertThat(preview.getProducts()).singleElement().satisfies(item -> {
            assertThat(item.getProductName()).isEqualTo("Boya Guaj");
            assertThat(item.getQuantity()).isEqualTo(15);
            assertThat(item.getProductCode()).isEqualTo("BG-1");
            assertThat(item.getContributingSourceRecordIds())
                    .containsExactly("xlsx:EK-1:row:23");
        });
        assertThat(token.requestsStarted()).isEqualTo(1);
        assertThat(token.retriesStarted()).isZero();
        assertThat(token.recursiveSplits()).isZero();
        server.verify();
    }

    @Test
    void sourceCandidateLookupWithoutMatchingPhysicalRowReturnsEmpty() {
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "missing.xlsx", "Ürünler", 1, "Malzeme Adı | Miktar", false,
                List.of(excelRecord(5, "Kalem", 2)));
        ProductPreviewDto result = ProductPreviewDto.builder()
                .productName("Kalem")
                .quantity(2)
                .contributingSourceRecordIds(List.of("xlsx:Ürünler:row:999"))
                .build();

        assertThat(service.sourceCandidateForResult(chunk, result, 0, false)).isEmpty();
    }

    @Test
    void sourceCandidateLookupTreatsNullableCandidateAsExplicitAbsence() {
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "nullable.xlsx", "Ürünler", 1, "Açıklama | Miktar", false,
                List.of(new ExcelChunkMetadata.SourceRecord(
                        5, "ROW 5:\nTeknik açıklama | 2", null, 2)));
        ProductPreviewDto result = ProductPreviewDto.builder()
                .productName("Teknik açıklama")
                .quantity(2)
                .contributingSourceRecordIds(List.of("xlsx:Ürünler:row:5"))
                .build();

        assertThat(service.sourceCandidateForResult(chunk, result, 0, false)).isEmpty();
    }

    @Test
    void suspiciousNameWithMissingCandidateIsIsolatedToReviewPath() throws Exception {
        String suspiciousName = "Dayanıklı malzemeden üretilmiştir, 12 V ve 20 mm ölçüleri vardır.";
        expect(objectMapper.writeValueAsString(List.of(
                productWithSource("xlsx:Ürünler:row:5", suspiciousName, 2),
                productWithSource("xlsx:Ürünler:row:6", "Kalem", 3))));
        expect("[{\"itemIndex\":1,\"status\":\"REVIEW_REQUIRED\","
                + "\"productName\":null,\"reason\":\"Kaynak ad doğrulanamadı.\"}]");
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "nullable.xlsx", "Ürünler", 1, "Açıklama | Miktar", false,
                List.of(
                        new ExcelChunkMetadata.SourceRecord(
                                5, "ROW 5:\n" + suspiciousName + " | 2", null, 2),
                        excelRecord(6, "Kalem", 3)));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).hasSize(2);
        assertThat(preview.getProducts().get(0).isReviewRequired()).isTrue();
        assertThat(preview.getProducts().get(0).getReviewMessage()).isNotBlank();
        assertThat(preview.getProducts().get(1).isReviewRequired()).isFalse();
        server.verify();
    }

    @Test
    void fatalChunkFailureStopsRunningSiblingAndQueuedRequest() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        CountDownLatch siblingStarted = new CountDownLatch(1);
        CountDownLatch siblingStopped = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        doAnswer(invocation -> {
            requests.incrementAndGet();
            HttpEntity<?> entity = invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            Map<String, Object> requestBody = (Map<String, Object>) entity.getBody();
            String prompt = String.valueOf(requestBody.get("prompt"));
            if (prompt.contains("FAIL")) {
                assertThat(siblingStarted.await(2, TimeUnit.SECONDS)).isTrue();
                throw new ResourceAccessException("fatal test failure");
            }
            if (prompt.contains("BLOCK")) {
                siblingStarted.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException exception) {
                    siblingStopped.countDown();
                    Thread.currentThread().interrupt();
                    throw new ResourceAccessException("sibling interrupted");
                }
            }
            return ResponseEntity.ok(objectMapper.readTree("{\"response\":\"[]\"}"));
        }).when(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class));
        BulkImportCancellationToken token = new BulkImportCancellationToken(
                "11111111-1111-1111-1111-111111111111", true);
        token.markStarted();
        List<DocumentChunk> chunks = List.of(
                new DocumentChunk("fatal.txt", "TEXT", null, 1, null, null, "FAIL"),
                new DocumentChunk("fatal.txt", "TEXT", null, 2, null, null, "BLOCK"),
                new DocumentChunk("fatal.txt", "TEXT", null, 3, null, null, "QUEUED"));

        assertThatThrownBy(() -> service.parseChunksRecovering(chunks, token))
                .isInstanceOf(OllamaInfrastructureException.class);

        assertThat(siblingStopped.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(requests).hasValue(2);
        assertThat(token.isCancelled()).isTrue();
        assertThat(token.tasksCancelled()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tenTrustedExcelRowsWithFormattingDifferencesUseOneNormalRequest() throws Exception {
        List<Map<String, Object>> aiRows = java.util.stream.IntStream.rangeClosed(23, 32)
                .mapToObj(row -> Map.<String, Object>of(
                        "sourceRecordId", "xlsx:EK-1:row:" + row,
                        "productCode", "K-" + row,
                        "productName", "ÜRÜN_" + row,
                        "quantity", 999))
                .toList();
        expect(objectMapper.writeValueAsString(aiRows));
        List<ExcelChunkMetadata.SourceRecord> records = java.util.stream.IntStream
                .rangeClosed(23, 32)
                .mapToObj(row -> excelRecord(row, "Ürün " + row, row))
                .toList();
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "real-pattern.xlsx", "EK-1", 3,
                "Kod | Malzeme Adı | Miktar", false, records);

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).hasSize(10);
        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getProductName)
                .containsExactlyElementsOf(records.stream()
                        .map(ExcelChunkMetadata.SourceRecord::productNameCandidate).toList());
        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getQuantity)
                .containsExactlyElementsOf(records.stream()
                        .map(ExcelChunkMetadata.SourceRecord::quantityCandidate).toList());
        server.verify();
    }

    @Test
    void duplicateTrustedAiRepresentationContributesPhysicalRowOnce() throws Exception {
        Map<String, Object> duplicate = Map.of(
                "sourceRecordId", "xlsx:EK-1:row:23",
                "productCode", "BG-1",
                "productName", "Boya-Guaj",
                "quantity", 15);
        expect(objectMapper.writeValueAsString(List.of(duplicate, duplicate)));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "duplicates.xlsx", "EK-1", 1,
                "Malzeme Adı | Miktar", true,
                List.of(excelRecord(23, "Boya Guaj", 15)));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(15);
            assertThat(item.getContributingSourceRecordIds())
                    .containsExactly("xlsx:EK-1:row:23");
        });
        server.verify();
    }

    @Test
    void cancellationStopsBeforeRetryOrRecursiveSplit() throws Exception {
        resetService(2);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andRespond(request -> {
                    requestStarted.countDown();
                    try {
                        releaseResponse.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(
                            objectMapper.writeValueAsString(Map.of("response", "[]")),
                            MediaType.APPLICATION_JSON).createResponse(request);
                });
        BulkImportCancellationToken token = new BulkImportCancellationToken(
                "11111111-1111-1111-1111-111111111111", true);
        token.markStarted();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<BulkPreviewResponseDto> future = executor.submit(
                () -> service.parseChunksRecovering(List.of(excelChunk(1, 10)), token));

        assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        token.cancel();
        releaseResponse.countDown();

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BulkImportCancelledException.class);
        assertThat(token.requestsStarted()).isEqualTo(1);
        assertThat(token.retriesStarted()).isZero();
        assertThat(token.recursiveSplits()).isZero();
        executor.shutdownNow();
        server.verify();
    }

    @Test
    void failedSecondHalfSplitsAgainIntoTwoPlusThreeWithoutDuplicates() throws Exception {
        expect(productsRange(31, 9));
        expect(productsRange(31, 5));
        expect(productsRange(36, 4));
        expect(productsRange(36, 2));
        expect(productsRange(38, 3));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(excelChunk(31, 10)));

        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getProductName)
                .containsExactlyElementsOf(productNames(31, 10))
                .doesNotHaveDuplicates();
        assertThat(preview.getUnresolvedRecords()).isEmpty();
        server.verify();
    }

    @Test
    void reliablePdfFailureNarrowsToOneGapAndPreservesSuccessfulOrder() throws Exception {
        expect(products("Alpha", "Beta", "Gamma"));
        expect(products("Alpha", "Beta"));
        expect(products("Gamma"));
        expect(products("Gamma"));
        expect("[]");

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(
                reliablePdfChunk("Alpha", "Beta", "Gamma", "Delta")));

        assertThat(preview.getProducts())
                .extracting(ProductPreviewDto::getProductName)
                .containsExactly("Alpha", "Beta", "Gamma");
        assertThat(preview.getProducts())
                .extracting(ProductPreviewDto::getRowNumber)
                .containsExactly(1, 2, 3);
        assertThat(preview.getUnresolvedRecords()).singleElement().satisfies(gap -> {
            assertThat(gap.getPageNumber()).isEqualTo(4);
            assertThat(gap.getSourceRecordStart()).isEqualTo(4);
            assertThat(gap.getSourceRecordEnd()).isEqualTo(4);
            assertThat(gap.getInsertionIndex()).isEqualTo(3);
        });
        assertThat(preview.isComplete()).isFalse();
        server.verify();
    }

    @Test
    void reliableExcelFailureNarrowsByLogicalRowsAndReportsExactSourceRow() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(product("Kalem", 2))));
        expect(objectMapper.writeValueAsString(List.of(product("Kalem", 2))));
        expect("not-json");

        List<ExcelChunkMetadata.SourceRecord> records = List.of(
                new ExcelChunkMetadata.SourceRecord(11, "ROW 11:\n1 | Kalem | 2", "Kalem", 2),
                new ExcelChunkMetadata.SourceRecord(12, "ROW 12:\n2 | Silgi | 3", "Silgi", 3));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "test.xlsx", "EK-1", 1, "Sıra | Malzeme Adı | Miktar", false, records);

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).extracting(ProductPreviewDto::getProductName)
                .containsExactly("Kalem");
        assertThat(preview.getUnresolvedRecords()).singleElement().satisfies(gap -> {
            assertThat(gap.getWorksheetName()).isEqualTo("EK-1");
            assertThat(gap.getSourceRowStart()).isEqualTo(12);
            assertThat(gap.getSourceRowEnd()).isEqualTo(12);
            assertThat(gap.getId()).isEqualTo("xlsx:EK-1:row:12");
        });
        server.verify();
    }

    @Test
    void repeatedAiRecordsFromOneUnnumberedExcelRowShareOneContributionId() throws Exception {
        String repeated = objectMapper.writeValueAsString(List.of(
                Map.of("productCode", "S-1", "productName", "Silikon Tabancası", "quantity", 10),
                Map.of("productCode", "S-1", "productName", "Silikon Tabancası", "quantity", 10)));
        expect(repeated);

        ExcelChunkMetadata.SourceRecord sourceRow = new ExcelChunkMetadata.SourceRecord(
                2,
                "ROW 2:\nSilikon Tabancası | 10 | Adet | 3 | A-01",
                "Silikon Tabancası",
                10);
        DocumentChunk chunk = new DocumentChunk(
                "test.xlsx", "XLSX", "Ürünler", 1, 1, 2,
                2, 0, false,
                "DATA ROWS TO ANALYZE:\nROW 1:\nÜrün | Miktar | Birim | Minimum | Raf\n"
                        + sourceRow.sourceText(),
                List.of(), null, 0, 100, List.of(), 0, null,
                new ExcelChunkMetadata("Ürün | Miktar | Birim | Minimum | Raf", false,
                        List.of(), List.of(sourceRow)));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).hasSize(2);
        assertThat(preview.getProducts())
                .extracting(ProductPreviewDto::getContributingSourceRecordIds)
                .containsExactly(
                        List.of("xlsx:Ürünler:row:2"),
                        List.of("xlsx:Ürünler:row:2"));
        server.verify();
    }

    @Test
    void notesWorksheetOutputWithoutPhysicalTableRecordIsReviewOnly() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(
                product("Silikon Tabancası", 10))));
        DocumentChunk notesChunk = new DocumentChunk(
                "quantity-integrity.xlsx", "XLSX", "Test Notları", 1, 1, 2,
                2, 0, false,
                "DATA ROWS TO ANALYZE:\nROW 1:\nTest Notları\n"
                        + "ROW 2:\nSilikon Tabancası miktarı 10 olmalıdır.",
                List.of(), null, 0, 100, List.of(), 0, null,
                new ExcelChunkMetadata("Test Notları", false, List.of(), List.of()));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(notesChunk));

        assertThat(preview.getProducts()).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(10);
            assertThat(item.getContributingSourceRecordIds()).isNullOrEmpty();
            assertThat(item.isSourceIdentityReviewRequired()).isTrue();
            assertThat(item.isReviewRequired()).isTrue();
        });
        server.verify();
    }

    @Test
    void retryAndReorderedDuplicateOutputsKeepExactPhysicalExcelIdentity() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(
                productWithSource("xlsx:Ürünler:row:6", "Silikon Çubuğu", 25),
                productWithSource("xlsx:Ürünler:row:7", "Beher 250 ml", 12))));

        List<ExcelChunkMetadata.SourceRecord> records = List.of(
                excelRecord(5, "Silikon Tabancası", 10),
                excelRecord(6, "Silikon Çubuğu", 25),
                excelRecord(7, "Beher 250 ml", 12));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "quantity-integrity.xlsx", "Ürünler", 1,
                "Ürün Adı | Miktar | Birim | Minimum Stok | Raf",
                true, records);

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).hasSize(3);
        assertThat(preview.getProducts()).extracting(item ->
                        item.getProductName() + "=" + item.getQuantity() + "="
                                + item.getContributingSourceRecordIds().get(0))
                .containsExactly(
                        "Silikon Tabancası=10=xlsx:Ürünler:row:5",
                        "Silikon Çubuğu=25=xlsx:Ürünler:row:6",
                        "Beher 250 ml=12=xlsx:Ürünler:row:7");
        assertThat(preview.getProducts()).allSatisfy(item ->
                assertThat(item.isSourceIdentityReviewRequired()).isFalse());
        server.verify();
    }

    @Test
    void reorderedCanonicalRowsWithDifferentPhysicalQuantitiesKeepDistinctIdentities() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(
                productWithSource("xlsx:Ürünler:row:9", "PİL_YUVARLAK", 5),
                productWithSource("xlsx:Ürünler:row:8", "Pil Yuvarlak", 10))));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "same-name.xlsx", "Ürünler", 1, "Ürün Adı | Miktar", true,
                List.of(
                        excelRecord(8, "Pil Yuvarlak", 10),
                        excelRecord(9, "PİL_YUVARLAK", 5)));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).extracting(item ->
                        item.getProductName() + "=" + item.getQuantity() + "="
                                + item.getContributingSourceRecordIds().get(0))
                .containsExactly(
                        "Pil Yuvarlak=10=xlsx:Ürünler:row:8",
                        "PİL_YUVARLAK=5=xlsx:Ürünler:row:9");
        server.verify();
    }

    @Test
    void mismatchedAiSourceReferenceIsIsolatedInsteadOfPositionallyReassigned() throws Exception {
        expect(objectMapper.writeValueAsString(List.of(
                productWithSource("xlsx:Ürünler:row:6", "Silikon Tabancası", 10))));
        DocumentChunk chunk = DocumentChunk.excelRecords(
                "identity-mismatch.xlsx", "Ürünler", 1, "Ürün Adı | Miktar", false,
                List.of(excelRecord(5, "Silikon Tabancası", 10)));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(List.of(chunk));

        assertThat(preview.getProducts()).isEmpty();
        assertThat(preview.getUnresolvedRecords()).singleElement().satisfies(gap -> {
            assertThat(gap.getId()).isEqualTo("xlsx:Ürünler:row:5");
            assertThat(gap.getWorksheetName()).isEqualTo("Ürünler");
            assertThat(gap.getSourceText()).contains("Silikon Tabancası", "10");
        });
        assertThat(preview.isComplete()).isFalse();
        server.verify();
    }

    @Test
    void oneMalformedRecordIsolatedToOneGapInsteadOfBlanketReview() throws Exception {
        expect(productsWithMalformed(1, 9, "Ürün-10", 999));
        expect(productsRange(1, 5));
        expect(productsWithMalformed(6, 4, "Ürün-10", 999));
        expect(productsRange(6, 2));
        expect(productsWithMalformed(8, 2, "Ürün-10", 999));
        expect(productsRange(8, 1));
        expect(productsWithMalformed(9, 1, "Ürün-10", 999));
        expect(productsRange(9, 1));
        expect(productsWithMalformed(10, 0, "Ürün-10", 999));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(
                List.of(excelChunk(1, 10)));

        assertThat(preview.getProducts()).hasSize(9);
        assertThat(preview.getProducts())
                .allSatisfy(item -> assertThat(item.isReviewRequired()).isFalse());
        assertThat(preview.getUnresolvedRecords()).singleElement().satisfies(gap -> {
            assertThat(gap.getId()).isEqualTo("xlsx:EK-3:row:10");
            assertThat(gap.getSourceRowStart()).isEqualTo(10);
            assertThat(gap.getSourceRowEnd()).isEqualTo(10);
        });
        server.verify();
    }

    @Test
    void representativeMultiSheetExcelKeepsTrustedRowsOutOfNotesReview() throws Exception {
        expect(products("Alpha", "Beta"));
        expect(products("Gamma", "Delta"));
        expect(products("Alpha"));
        DocumentChunk firstSheet = DocumentChunk.excelRecords(
                "multi.xlsx", "EK-1", 1, "Ürün Adı | Miktar", true,
                List.of(excelRecord(5, "Alpha", 1), excelRecord(6, "Beta", 2)));
        DocumentChunk secondSheet = DocumentChunk.excelRecords(
                "multi.xlsx", "EK-2", 1, "Ürün Adı | Miktar", true,
                List.of(excelRecord(5, "Gamma", 3), excelRecord(6, "Delta", 4)));
        DocumentChunk notes = new DocumentChunk(
                "multi.xlsx", "XLSX", "Test Notları", 1, 1, 1,
                1, 0, false, "ROW 1: Alpha kontrol notu", List.of(), null,
                0, 20, List.of(), 0, null,
                new ExcelChunkMetadata("Test Notları", false, List.of(), List.of()));

        BulkPreviewResponseDto preview = service.parseChunksRecovering(
                List.of(firstSheet, secondSheet, notes));

        assertThat(preview.getProducts()).filteredOn(item -> !item.isReviewRequired())
                .extracting(ProductPreviewDto::getProductName)
                .containsExactly("Alpha", "Beta", "Gamma", "Delta");
        assertThat(preview.getProducts()).filteredOn(ProductPreviewDto::isReviewRequired)
                .singleElement()
                .satisfies(item -> assertThat(item.isSourceIdentityReviewRequired()).isTrue());
        server.verify();
    }

    @Test
    void ollamaOutageFailsWholeDocumentInsteadOfCreatingUnresolvedGap() {
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andRespond(request -> {
                    throw new ResourceAccessException("connection refused");
                });

        assertThatThrownBy(() -> service.parseChunksRecovering(List.of(
                reliablePdfChunk("Alpha"))))
                .isInstanceOf(OllamaInfrastructureException.class)
                .hasMessageContaining("erişilemedi");
        server.verify();
    }

    private DocumentChunk reliablePdfChunk(String... names) {
        List<PdfRecordSegmenter.LogicalRecord> records = java.util.stream.IntStream
                .range(0, names.length)
                .mapToObj(index -> new PdfRecordSegmenter.LogicalRecord(
                        index + 1,
                        "Ürün Adı: " + names[index] + "\nMiktar: " + (index + 1),
                        names[index], null, index + 1, true,
                        PdfRecordSegmenter.StartKind.EXPLICIT_LABEL))
                .toList();
        return DocumentChunk.pdfRecords(
                "test.pdf", 4, 1, "Header", records,
                PdfRecordSegmenter.Confidence.RELIABLE, true);
    }

    private DocumentChunk excelChunk(int firstRow, int count) {
        List<ExcelChunkMetadata.SourceRecord> records = java.util.stream.IntStream
                .range(firstRow, firstRow + count)
                .mapToObj(row -> new ExcelChunkMetadata.SourceRecord(
                        row, "ROW " + row + ":\n" + row + " | Ürün-" + row + " | 1",
                        "Ürün-" + row, 1))
                .toList();
        return DocumentChunk.excelRecords(
                "test.xlsx", "EK-3", 4, "Sıra | Malzeme Adı | Miktar", false, records);
    }

    private List<String> productNames(int firstRow, int count) {
        return java.util.stream.IntStream.range(firstRow, firstRow + count)
                .mapToObj(row -> "Ürün-" + row)
                .toList();
    }

    private String productsRange(int firstRow, int count) throws Exception {
        List<Map<String, Object>> items = java.util.stream.IntStream
                .range(firstRow, firstRow + count)
                .mapToObj(row -> Map.<String, Object>of(
                        "productCode", "C-" + row,
                        "productName", "Ürün-" + row,
                        "quantity", 1))
                .toList();
        return objectMapper.writeValueAsString(items);
    }

    private String productsWithMalformed(
            int firstGoodRow,
            int goodCount,
            String malformedName,
            int malformedQuantity) throws Exception {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int row = firstGoodRow; row < firstGoodRow + goodCount; row++) {
            items.add(Map.of(
                    "productCode", "C-" + row,
                    "productName", "Ürün-" + row,
                    "quantity", 1));
        }
        items.add(product(malformedName, malformedQuantity));
        return objectMapper.writeValueAsString(items);
    }

    private String products(String... names) throws Exception {
        List<Map<String, Object>> items = java.util.stream.IntStream.range(0, names.length)
                .mapToObj(index -> Map.<String, Object>of(
                        "productCode", "C-" + (index + 1),
                        "productName", names[index],
                        "quantity", switch (names[index]) {
                            case "Alpha" -> 1;
                            case "Beta" -> 2;
                            case "Gamma" -> 3;
                            case "Delta" -> 4;
                            default -> index + 1;
                        }))
                .toList();
        return objectMapper.writeValueAsString(items);
    }

    private Map<String, Object> product(String name, int quantity) {
        return Map.of("productCode", "", "productName", name, "quantity", quantity);
    }

    private Map<String, Object> productWithSource(
            String sourceRecordId,
            String name,
            int quantity) {
        return Map.of(
                "sourceRecordId", sourceRecordId,
                "productCode", "",
                "productName", name,
                "quantity", quantity);
    }

    private ExcelChunkMetadata.SourceRecord excelRecord(int row, String name, int quantity) {
        return new ExcelChunkMetadata.SourceRecord(
                row,
                "ROW " + row + ":\n" + name + " | " + quantity + " | Adet | 3 | A-01",
                name,
                quantity);
    }

    private void expect(String generated) throws Exception {
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(Map.of("response", generated)),
                        MediaType.APPLICATION_JSON));
    }
}

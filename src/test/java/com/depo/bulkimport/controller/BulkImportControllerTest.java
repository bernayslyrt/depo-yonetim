package com.depo.bulkimport.controller;

import com.depo.bulkimport.dto.BulkConfirmRequestDto;
import com.depo.bulkimport.dto.BulkMatchPreviewRequestDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.service.BulkProductService;
import com.depo.bulkimport.service.BulkPreviewSessionService;
import com.depo.bulkimport.service.BulkImportCancellationToken;
import com.depo.bulkimport.service.BulkImportCancelledException;
import com.depo.bulkimport.service.BulkImportJobService;
import com.depo.bulkimport.service.DocumentParsingService;
import com.depo.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkImportControllerTest {

    @Mock
    private DocumentParsingService documentParsingService;

    @Mock
    private BulkProductService bulkProductService;

    @Mock
    private BulkPreviewSessionService bulkPreviewSessionService;

    @Mock
    private BulkImportJobService bulkImportJobService;

    @Mock
    private BulkImportCancellationToken cancellationToken;

    @InjectMocks
    private BulkImportController controller;

    @Test
    void cancelledParseCreatesNoUsablePreviewSession() {
        String jobId = "11111111-1111-1111-1111-111111111111";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xlsx", "application/octet-stream", new byte[]{1});
        when(bulkImportJobService.start(jobId)).thenReturn(cancellationToken);
        when(documentParsingService.parseFileWithRecovery(file, cancellationToken))
                .thenThrow(new BulkImportCancelledException(jobId));

        ResponseEntity<ApiResponse<com.depo.bulkimport.dto.BulkPreviewResponseDto>> response =
                controller.parsePreview(file, jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(bulkPreviewSessionService, never()).register(any());
        verify(bulkImportJobService).finish(cancellationToken);
    }

    @Test
    void unexpectedParseFailureReturnsGenericNonNullMessageAndFailsJob() {
        String jobId = "11111111-1111-1111-1111-111111111111";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xlsx", "application/octet-stream", new byte[]{1});
        when(bulkImportJobService.start(jobId)).thenReturn(cancellationToken);
        when(documentParsingService.parseFileWithRecovery(file, cancellationToken))
                .thenThrow(new NullPointerException());

        ResponseEntity<ApiResponse<com.depo.bulkimport.dto.BulkPreviewResponseDto>> response =
                controller.parsePreview(file, jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Belge işlenirken beklenmeyen bir hata oluştu.")
                .doesNotContain("null");
        verify(bulkImportJobService).fail(cancellationToken);
        verify(bulkPreviewSessionService).invalidate((String) null);
        verify(bulkImportJobService).finish(cancellationToken);
    }

    @Test
    void explicitCancelInvalidatesPreviewBoundToOnlyThatJob() {
        String jobId = "11111111-1111-1111-1111-111111111111";
        when(bulkImportJobService.cancel(jobId))
                .thenReturn(new BulkImportJobService.CancelResult(true, "preview-1"));

        ResponseEntity<ApiResponse<String>> response = controller.cancelImport(jobId, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(bulkPreviewSessionService).invalidate("preview-1");
    }

    @Test
    void confirmValidationFailureReturnsUserFriendlyBadRequest() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(1)
                .productName("A".repeat(256))
                .quantity(1)
                .isValid(true)
                .build();
        BulkConfirmRequestDto request = BulkConfirmRequestDto.builder()
                .previewId("preview-1")
                .source("T3")
                .items(List.of(item))
                .build();
        when(bulkPreviewSessionService.beginConfirmation("preview-1", request.getItems()))
                .thenReturn(request.getItems());
        when(bulkProductService.confirmBulkImport(anyList(), eq("T3")))
                .thenThrow(new IllegalArgumentException("Ürün adı en fazla 255 karakter olabilir."));

        ResponseEntity<ApiResponse<String>> response = controller.confirmBulkImport(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("255");
    }

    @Test
    void unresolvedPreviewCannotBypassBackendConfirmationGuard() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(2).isValid(true).build();
        BulkConfirmRequestDto request = BulkConfirmRequestDto.builder()
                .previewId("preview-with-gap")
                .source("T3")
                .items(List.of(item))
                .build();
        when(bulkPreviewSessionService.beginConfirmation(eq("preview-with-gap"), anyList()))
                .thenThrow(new IllegalArgumentException(
                        "Tüm çözülemeyen kaynak kayıtları manuel ürünle tamamlanmadan onay verilemez."));

        ResponseEntity<ApiResponse<String>> response = controller.confirmBulkImport(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).contains("tamamlanmadan onay verilemez");
        verify(bulkProductService, never()).confirmBulkImport(anyList(), eq("T3"));
    }

    @Test
    void confirmationUsesServerRestoredRowsInsteadOfClientQuantity() {
        ProductPreviewDto clientItem = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(20).isValid(true).build();
        ProductPreviewDto restoredItem = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(10).importedQuantity(10)
                .isValid(true).build();
        BulkConfirmRequestDto request = BulkConfirmRequestDto.builder()
                .previewId("preview-1").source("T3").items(List.of(clientItem)).build();
        when(bulkPreviewSessionService.beginConfirmation("preview-1", request.getItems()))
                .thenReturn(List.of(restoredItem));
        when(bulkProductService.confirmBulkImport(List.of(restoredItem), "T3"))
                .thenReturn(new BulkProductService.BulkImportResult(1, 0, "batch-1"));

        ResponseEntity<ApiResponse<String>> response = controller.confirmBulkImport(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(bulkProductService).confirmBulkImport(List.of(restoredItem), "T3");
        verify(bulkPreviewSessionService).completeConfirmation("preview-1");
    }

    @Test
    void matchPreviewReevaluatesRowsWithSelectedSourceWithoutParsingAgain() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(2).isValid(true).build();
        ProductPreviewDto matched = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(2).isValid(true)
                .matchStatus("Mevcut ürün").resolutionType("EXISTING").build();
        BulkMatchPreviewRequestDto request = BulkMatchPreviewRequestDto.builder()
                .previewId("preview-1").source("T3").items(List.of(item)).build();
        when(bulkPreviewSessionService.restoreForMatching("preview-1", request.getItems()))
                .thenReturn(request.getItems());
        when(bulkProductService.preparePreview(anyList(), eq("T3"))).thenReturn(List.of(matched));

        ResponseEntity<ApiResponse<List<ProductPreviewDto>>> response = controller.matchPreview(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData().get(0).getMatchStatus()).isEqualTo("Mevcut ürün");
        verify(bulkPreviewSessionService).restoreForMatching("preview-1", request.getItems());
        verify(documentParsingService, never()).parseFileWithRecovery(any());
    }

    @Test
    void matchPreviewRejectsChangedImmutableQuantityAsBadRequest() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(1).productName("Kalem").quantity(20).isValid(true).build();
        BulkMatchPreviewRequestDto request = BulkMatchPreviewRequestDto.builder()
                .previewId("preview-1").source("T3").items(List.of(item)).build();
        when(bulkPreviewSessionService.restoreForMatching("preview-1", request.getItems()))
                .thenThrow(new IllegalArgumentException("İçe aktarılan miktar değiştirilemez."));

        ResponseEntity<ApiResponse<List<ProductPreviewDto>>> response =
                controller.matchPreview(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).contains("miktar");
        verify(bulkProductService, never()).preparePreview(anyList(), eq("T3"));
    }
}

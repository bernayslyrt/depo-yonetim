package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.dto.UnresolvedSourceRecordDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkPreviewSessionServiceTest {

    @Test
    void invalidateMakesCancelledPreviewUnusable() {
        BulkPreviewSessionService service = new BulkPreviewSessionService();
        BulkPreviewResponseDto registered = service.register(BulkPreviewResponseDto.builder()
                .products(List.of(validProduct(null)))
                .unresolvedRecords(List.of())
                .complete(true)
                .build());

        service.invalidate(registered.getPreviewId());

        assertThatThrownBy(() -> service.restoreForMatching(
                registered.getPreviewId(), registered.getProducts()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bulunamadı");
    }

    private final BulkPreviewSessionService service = new BulkPreviewSessionService();

    @Test
    void confirmationIsBlockedUntilEveryGapHasExactlyOneManualProduct() {
        BulkPreviewResponseDto registered = service.register(previewWithGap("gap-1"));
        ProductPreviewDto ordinary = registered.getProducts().get(0);

        assertThatThrownBy(() -> service.beginConfirmation(
                registered.getPreviewId(), List.of(ordinary)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Eksik kayıt sayısı: 1");

        ProductPreviewDto manual = validProduct("gap-1");
        service.beginConfirmation(registered.getPreviewId(), List.of(ordinary, manual));
        service.completeConfirmation(registered.getPreviewId());

        assertThatThrownBy(() -> service.beginConfirmation(
                registered.getPreviewId(), List.of(ordinary, manual)))
                .hasMessageContaining("bulunamadı veya süresi doldu");
    }

    @Test
    void duplicateOrUnknownGapMappingIsRejected() {
        BulkPreviewResponseDto registered = service.register(previewWithGap("gap-1"));

        assertThatThrownBy(() -> service.beginConfirmation(
                registered.getPreviewId(), List.of(validProduct("unknown"))))
                .hasMessageContaining("geçersiz");
        assertThatThrownBy(() -> service.beginConfirmation(
                registered.getPreviewId(), List.of(validProduct("gap-1"), validProduct("gap-1"))))
                .hasMessageContaining("birden fazla");
    }

    @Test
    void completePreviewStillRequiresItsServerSession() {
        BulkPreviewResponseDto registered = service.register(
                new BulkPreviewResponseDto(null, List.of(validProduct(null)), List.of(), true));
        assertThat(registered.getPreviewId()).isNotBlank();
        assertThatThrownBy(() -> service.beginConfirmation(null, registered.getProducts()))
                .hasMessageContaining("oturumu eksik");
    }

    @Test
    void matchingRejectsChangedQuantityAndUnknownPreviewIdentity() {
        ProductPreviewDto parsed = validProduct(null);
        parsed.setQuantity(10);
        parsed.setContributingSourceRecordIds(List.of("xlsx:Ürünler:row:5"));
        BulkPreviewResponseDto registered = service.register(
                new BulkPreviewResponseDto(null, List.of(parsed), List.of(), true));
        ProductPreviewDto clientRow = registered.getProducts().get(0);

        clientRow.setQuantity(20);
        assertThatThrownBy(() -> service.restoreForMatching(
                registered.getPreviewId(), List.of(clientRow)))
                .hasMessageContaining("miktar");

        clientRow.setQuantity(10);
        clientRow.setPreviewItemIds(List.of("forged"));
        assertThatThrownBy(() -> service.restoreForMatching(
                registered.getPreviewId(), List.of(clientRow)))
                .hasMessageContaining("Bilinmeyen");
    }

    @Test
    void regroupedPreviewTokensRestoreExactQuantityAndContributionUnion() {
        ProductPreviewDto first = validProduct(null);
        first.setQuantity(10);
        first.setContributingSourceRecordIds(List.of("xlsx:Ürünler:row:5"));
        ProductPreviewDto second = validProduct(null);
        second.setQuantity(5);
        second.setContributingSourceRecordIds(List.of("xlsx:Ürünler:row:6"));
        BulkPreviewResponseDto registered = service.register(
                new BulkPreviewResponseDto(null, List.of(first, second), List.of(), true));

        ProductPreviewDto regrouped = validProduct(null);
        regrouped.setQuantity(15);
        regrouped.setPreviewItemIds(List.of(
                registered.getProducts().get(0).getPreviewItemIds().get(0),
                registered.getProducts().get(1).getPreviewItemIds().get(0)));
        regrouped.setContributingSourceRecordIds(List.of("forged"));

        ProductPreviewDto restored = service.restoreForMatching(
                registered.getPreviewId(), List.of(regrouped)).get(0);

        assertThat(restored.getQuantity()).isEqualTo(15);
        assertThat(restored.getImportedQuantity()).isEqualTo(15);
        assertThat(restored.getContributingSourceRecordIds())
                .containsExactly("xlsx:Ürünler:row:5", "xlsx:Ürünler:row:6");
    }

    @Test
    void untrustedRowWithoutPhysicalContributionRemainsExplicitlyCorrectable() {
        ProductPreviewDto untrusted = ProductPreviewDto.builder()
                .rowNumber(1).productName("Serbest metin ürünü").quantity(null)
                .isValid(false).build();
        BulkPreviewResponseDto registered = service.register(
                new BulkPreviewResponseDto(null, List.of(untrusted), List.of(), true));
        ProductPreviewDto corrected = registered.getProducts().get(0);
        corrected.setQuantity(3);
        corrected.setValid(true);

        ProductPreviewDto restored = service.restoreForMatching(
                registered.getPreviewId(), List.of(corrected)).get(0);

        assertThat(restored.getQuantity()).isEqualTo(3);
        assertThat(restored.getImportedQuantity()).isEqualTo(3);
    }

    private BulkPreviewResponseDto previewWithGap(String id) {
        return new BulkPreviewResponseDto(
                null,
                List.of(validProduct(null)),
                List.of(UnresolvedSourceRecordDto.builder()
                        .id(id).sourceType("XLSX").worksheetName("EK-1")
                        .sourceRowStart(12).sourceRowEnd(12).build()),
                false);
    }

    private ProductPreviewDto validProduct(String resolvedGapId) {
        return ProductPreviewDto.builder()
                .rowNumber(1)
                .productName("Kalem")
                .quantity(2)
                .isValid(true)
                .resolvedSourceRecordId(resolvedGapId)
                .build();
    }
}

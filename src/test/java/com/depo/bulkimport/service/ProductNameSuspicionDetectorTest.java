package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductNameSuspicionDetectorTest {

    private final ProductNameSuspicionDetector detector = new ProductNameSuspicionDetector();

    @Test
    void clearProductNameMatchingSourceIsNotSuspicious() {
        ProductPreviewDto product = product("Spatula Seti");

        assertThat(detector.inspect(product, "Spatula Seti").suspicious()).isFalse();
    }

    @Test
    void explanatoryTechnicalSentenceIsSuspicious() {
        ProductPreviewDto product = product(
                "Seramik ve kil şekillendirme için farklı uç tiplerinden en az 3 parçadan oluşan set olmalıdır.");

        ProductNameSuspicionDetector.Suspicion suspicion = detector.inspect(product, null);

        assertThat(suspicion.suspicious()).isTrue();
        assertThat(suspicion.signals()).contains("Açıklama cümlesi dili içeriyor");
    }

    @Test
    void legitimateLongLabelIsNotRejectedOnlyForLength() {
        ProductPreviewDto product = product(
                "Çok Amaçlı Ahşap ve Metal Yüzeylerde Kullanılabilen Profesyonel Boya Seti");

        assertThat(detector.inspect(product, null).suspicious()).isFalse();
    }

    @Test
    void mismatchWithReliableMaterialNameColumnIsSuspicious() {
        ProductPreviewDto product = product("3 Boyutlu Desen Çizim Cetveli, plastik");

        ProductNameSuspicionDetector.Suspicion suspicion = detector.inspect(product, "Spirograph");

        assertThat(suspicion.suspicious()).isTrue();
        assertThat(suspicion.userMessage()).contains("Malzeme Adı");
    }

    @Test
    void measurementAndPackagingDescriptionsAreSuspiciousWithoutStructuredSourceEvidence() {
        assertThat(detector.inspect(product("4 cm çap, 10 cm uzunluk"), null).suspicious()).isTrue();
        assertThat(detector.inspect(product("50 ml, plastik şişe"), null).suspicious()).isTrue();
        assertThat(detector.inspect(product("80'li paket içeriği"), null).suspicious()).isTrue();
    }

    private ProductPreviewDto product(String name) {
        return ProductPreviewDto.builder()
                .productName(name)
                .quantity(1)
                .isValid(true)
                .build();
    }
}

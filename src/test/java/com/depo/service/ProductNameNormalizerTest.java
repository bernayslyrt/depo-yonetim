package com.depo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProductNameNormalizerTest {

    @Test
    void treatsSeparatorsRepeatedWhitespaceAndTurkishCaseAsEquivalent() {
        String expected = ProductNameNormalizer.normalize("Pil_Yuvarlak");

        assertEquals(expected, ProductNameNormalizer.normalize("  Pil   Yuvarlak  "));
        assertEquals(expected, ProductNameNormalizer.normalize("pil_yuvarlak"));
        assertEquals(expected, ProductNameNormalizer.normalize("PİL YUVARLAK"));
        assertEquals(expected, ProductNameNormalizer.normalize("Pil-Yuvarlak"));
    }

    @Test
    void retainsMeaningfulProductNameDifferences() {
        assertNotEquals(ProductNameNormalizer.normalize("Pil Yuvası 4xAA"),
                ProductNameNormalizer.normalize("Pil Yuvası 2xAA"));
        assertNotEquals(ProductNameNormalizer.normalize("M3 Vida"),
                ProductNameNormalizer.normalize("M4 Vida"));
        assertNotEquals(ProductNameNormalizer.normalize("Kablo 1m"),
                ProductNameNormalizer.normalize("Kablo 2m"));
    }
}

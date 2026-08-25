package com.depo.service;

import java.util.Locale;

/**
 * Produces the deliberately conservative product-name key used for exact matching.
 * It does not perform fuzzy or semantic matching.
 */
public final class ProductNameNormalizer {

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private ProductNameNormalizer() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().replaceAll("[_\\-\\s]+", " ");
        return normalized.isEmpty() ? null : normalized.toLowerCase(TURKISH);
    }
}

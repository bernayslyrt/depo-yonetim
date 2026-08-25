package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared structural safeguards for AI preview output and confirm requests. */
final class ProductPreviewValidation {

    private ProductPreviewValidation() {
    }

    static List<String> structuralErrors(ProductPreviewDto dto) {
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            errors.add("Ürün satırı boş olamaz");
            return errors;
        }

        String productName = trimToNull(dto.getProductName());
        String productCode = trimToNull(dto.getProductCode());

        if (productName == null) {
            errors.add("Ürün adı boş olamaz");
        } else {
            if (productName.length() > ProductPreviewDto.MAX_PRODUCT_NAME_LENGTH) {
                errors.add("Ürün adı en fazla " + ProductPreviewDto.MAX_PRODUCT_NAME_LENGTH
                        + " karakter olabilir (alınan: " + productName.length() + ")");
            }
        }

        if (productCode != null) {
            if (productCode.length() > ProductPreviewDto.MAX_PRODUCT_CODE_LENGTH) {
                errors.add("Ürün kodu en fazla " + ProductPreviewDto.MAX_PRODUCT_CODE_LENGTH
                        + " karakter olabilir");
            }
            if (productCode.chars().anyMatch(Character::isISOControl)) {
                errors.add("Ürün kodu satır sonu veya kontrol karakteri içeremez");
            }
            if (productName != null
                    && normalizeWhitespace(productCode).equalsIgnoreCase(
                            normalizeWhitespace(productName))) {
                errors.add("Ürün kodu, ürün adından kopyalanamaz");
            }
        }

        if (dto.getQuantity() == null) {
            errors.add("Geçersiz miktar: güvenilir bir miktar belirlenemedi");
        } else if (dto.getQuantity() <= 0) {
            errors.add("Miktar 0'dan büyük olmalıdır (alınan: " + dto.getQuantity() + ")");
        }

        if (dto.getPrice() != null && dto.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("Fiyat 0'dan küçük olamaz");
        }
        return errors;
    }

    static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeWhitespace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

}

package com.depo.bulkimport.dto;

import lombok.Builder;
import lombok.Value;

/** Minimal existing-product data needed for an explicit bulk-import choice. */
@Value
@Builder
public class ProductMatchCandidateDto {
    Long productId;
    String productName;
    String source;
    String unit;
    String category;
    String shelfLocation;
    Integer currentStock;
}

package com.depo.bulkimport.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Recoverable bulk-import preview. No data in this DTO is persisted. */
@Value
@Builder
public class BulkPreviewResponseDto {
    String previewId;
    List<ProductPreviewDto> products;
    List<UnresolvedSourceRecordDto> unresolvedRecords;
    boolean complete;

    public BulkPreviewResponseDto(
            String previewId,
            List<ProductPreviewDto> products,
            List<UnresolvedSourceRecordDto> unresolvedRecords,
            boolean complete) {
        this.previewId = previewId;
        this.products = products == null ? List.of() : List.copyOf(products);
        this.unresolvedRecords = unresolvedRecords == null ? List.of() : List.copyOf(unresolvedRecords);
        this.complete = complete;
    }

    public BulkPreviewResponseDto withPreviewId(String id) {
        return new BulkPreviewResponseDto(id, products, unresolvedRecords, complete);
    }
}

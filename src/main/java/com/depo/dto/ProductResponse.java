package com.depo.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private Long id;
    private String code;
    private String name;
    private CategoryResponse category;
    private Integer quantity;
    private String unit;
    private Integer minStockLevel;
    private String shelfLocation;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

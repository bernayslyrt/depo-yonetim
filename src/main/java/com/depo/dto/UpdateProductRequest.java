package com.depo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProductRequest {

    private String code;

    @NotBlank(message = "Ürün adı boş olamaz.")
    private String name;

    private Long categoryId;

    @NotNull(message = "Miktar boş olamaz.")
    @Min(value = 0, message = "Miktar 0'dan küçük olamaz.")
    private Integer quantity;

    private String unit;

    @Min(value = 0, message = "Minimum stok seviyesi 0'dan küçük olamaz.")
    private Integer minStockLevel;

    private String shelfLocation;

    private String source;
}

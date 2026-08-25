package com.depo.bulkimport.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Re-evaluates name matches after source selection or a preview edit. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkMatchPreviewRequestDto {
    @NotBlank
    private String previewId;

    @NotBlank
    @Pattern(regexp = "Belediye|Tubitak|T3", message = "Geçersiz ürün kaynağı.")
    private String source;

    @NotEmpty
    @Valid
    private List<ProductPreviewDto> items;
}

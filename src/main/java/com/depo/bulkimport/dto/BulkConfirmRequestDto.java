package com.depo.bulkimport.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

import java.util.List;

/**
 * Kullanıcının ön yüzde düzenleyip onayladığı nihai ürün listesini taşıyan istek DTO'su.
 * Frontend, parse-preview sonucunda dönen listeyi düzenledikten sonra bu DTO ile onay gönderir.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkConfirmRequestDto {

    /** Server-side preview session used to enforce unresolved-gap completion. */
    @NotBlank(message = "Ön izleme oturumu eksik. Dosyayı yeniden inceleyin.")
    private String previewId;

    /** Bu aktarımdaki tüm yeni ürünlere atanacak kaynak */
    @NotBlank(message = "Ürün kaynağı seçilmelidir.")
    @Pattern(regexp = "Belediye|Tubitak|T3", message = "Geçersiz ürün kaynağı.")
    private String source;

    /** Onaylanan ürün listesi — en az bir eleman içermelidir */
    @NotEmpty(message = "Onaylanacak ürün listesi boş olamaz.")
    @Valid
    private List<ProductPreviewDto> items;
}

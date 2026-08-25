package com.depo.dto;

import com.depo.enums.IslemTipi;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IslemGecmisiResponse {

    private Long id;
    private String kullaniciAdi;
    private String kullaniciFullName;
    private IslemTipi islemTipi;
    private String urunAdi;
    private Integer miktar;
    private String aciklama;
    private String recipientName;
    private LocalDateTime tarihSaat;
}

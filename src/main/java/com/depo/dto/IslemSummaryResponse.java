package com.depo.dto;

import com.depo.enums.IslemTipi;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

/**
 * İşlem geçmişi özet yanıtı — hem tekil işlemler hem de toplu (batch) işlemler
 * için kullanılan birleşik DTO.
 *
 * <p>
 * isBatch = true ise bu satır bir toplu işlem özetidir:
 * batchId, toplamUrun ve toplamMiktar dolu, urunAdi ise null'dır.
 * </p>
 *
 * <p>
 * isBatch = false ise bu satır tekil bir işlemdir:
 * batchId null'dır ve urunAdi, miktar dolu olabilir.
 * </p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IslemSummaryResponse {

    /** Tekil işlemler için kayıt ID'si; toplu işlemlerde null. */
    private Long id;

    /** Toplu işlem grubu UUID'si; tekil işlemlerde null. */
    private String batchId;

    /** true → toplu işlem özeti, false → tekil işlem. */
    @JsonProperty("isBatch")
    private boolean isBatch;

    /** Toplu işlemin stok etkisinin geri alınıp alınmadığını gösterir. */
    @JsonProperty("isCancelled")
    private boolean isCancelled;

    private String kullaniciAdi;
    private String kullaniciFullName;

    private IslemTipi islemTipi;

    /** Tekil işlemlerde ürün adı; toplu işlemlerde null. */
    private String urunAdi;

    /** Tekil işlemlerde miktar; toplu işlemlerde toplamMiktar kullanılır. */
    private Integer miktar;

    /** Tekil işlem açıklaması veya toplu işlemde paylaşılan açıklama. */
    private String aciklama;

    /** Tekil işlem teslim alanı veya toplu işlemde paylaşılan teslim alan. */
    private String recipientName;

    /** Toplu işlemlerdeki ürün (kayıt) sayısı; tekil işlemlerde 0. */
    private int toplamUrun;

    /** İşlem tarih/saati. */
    private LocalDateTime tarihSaat;
}

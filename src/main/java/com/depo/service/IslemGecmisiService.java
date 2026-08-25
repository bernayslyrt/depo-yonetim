package com.depo.service;

import com.depo.dto.IslemGecmisiResponse;
import com.depo.dto.IslemSummaryResponse;
import com.depo.enums.IslemTipi;

import java.util.List;

public interface IslemGecmisiService {

    /**
     * Rol bazlı işlem geçmişi listesi.
     * ADMIN: Tüm kayıtlar, STAFF: Sadece kendi kayıtları.
     */
    List<IslemGecmisiResponse> getIslemGecmisi();

    /**
     * Birleşik özet listesi: batch işlemler tek satıra çöküp
     * toplamUrun ile gösterilir; tekil işlemler olduğu gibi gösterilir.
     * Tarih sırasına göre tersten sıralı döner.
     */
    List<IslemSummaryResponse> getSummary();

    /**
     * Belirli bir toplu işlemin (batchId) detay kayıtlarını döner.
     * Rol kontrolü: ADMIN her batchId'yi görebilir, PERSONEL sadece kendi
     * batchId'sini.
     */
    List<IslemGecmisiResponse> getByBatchId(String batchId);

    /**
     * Bir toplu işlemin stok etkisini tersine çevirir ve audit kayıtlarını iptal
     * edilmiş olarak işaretler.
     *
     * @return geri alınan işlem kaydı sayısı
     */
    int rollbackBatch(String batchId);

    /**
     * Yardımcı metod — stok işlemleri, PDF yükleme vb. sonrasında
     * işlem geçmişine kayıt eklemek için kullanılır. (batchId yok = tekil işlem)
     *
     * @param islemTipi     İşlem tipi (STOK_GIRIS, STOK_CIKIS, PDF_YUKLEME)
     * @param urunAdi       İşlem yapılan ürünün adı
     * @param miktar        Miktar (stok işlemlerinde)
     * @param aciklama      Açıklama metni
     * @param recipientName Teslim alan kişi adı (çıkış işlemlerinde)
     */
    void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName);

    /**
     * Toplu işlemler için aşırı yüklenmiş logEkle — batchId aynı toplu işlemin
     * tüm kayıtlarını birbirine bağlar.
     *
     * @param batchId Toplu işlem UUID'si (null ise tekil kayıt oluşturulur)
     */
    void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName,
            String batchId);

    /**
     * Ürün kimliğini de audit kaydına bağlayan toplu işlem log metodu.
     */
    void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName,
            String batchId, Long productId);
}

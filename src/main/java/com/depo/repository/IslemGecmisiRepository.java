package com.depo.repository;

import com.depo.entity.IslemGecmisi;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IslemGecmisiRepository extends JpaRepository<IslemGecmisi, Long> {

       /**
        * Tüm işlem geçmişini tarih sırasına göre tersten getirir (ADMIN için).
        */
       List<IslemGecmisi> findAllByOrderByTarihSaatDesc();

       /**
        * Belirli bir kullanıcının işlem geçmişini tarih sırasına göre tersten getirir
        * (PERSONEL için).
        */
       List<IslemGecmisi> findByUserIdOrderByTarihSaatDesc(Long userId);

       /**
        * Belirli bir toplu işleme ait tüm kayıtları getirir (detay modalı için).
        */
       List<IslemGecmisi> findByBatchIdOrderByTarihSaatAsc(String batchId);

       /**
        * Belirli bir kullanıcıya ait, belirli bir toplu işlemin kayıtlarını getirir
        * (PERSONEL detay).
        */
       List<IslemGecmisi> findByBatchIdAndUserIdOrderByTarihSaatAsc(String batchId, Long userId);

       /**
        * Bir batch'i geri alma boyunca kilitler. Ters kronolojik sıra, aynı ürünün
        * batch içinde birden fazla hareket görmesi halinde orijinal adımların güvenli
        * biçimde tersine uygulanmasını sağlar.
        */
       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("select i from IslemGecmisi i where i.batchId = :batchId " +
                     "order by i.tarihSaat desc, i.id desc")
       List<IslemGecmisi> findByBatchIdForRollback(@Param("batchId") String batchId);

       /**
        * Batch kayıtlarını (batchId != null) gruplandırarak özet bilgilerini getirir.
        * Her grup için: batchId, max(islemTipi), tarihSaat (en eski), toplam kayıt
        * sayısı,
        * kullanıcı, teslim alan, açıklama, min(islemTipi).
        * max ve min islemTipi karşılaştırması ile karma (mixed) batch'ler tespit
        * edilir.
        * ADMIN için tüm kullanıcılar. islemTipi artık GROUP BY'dan çıkarıldı — her
        * batchId
        * tek bir grup oluşturur.
        */
       @Query("select i.batchId, max(i.islemTipi), min(i.tarihSaat), count(i), i.user, " +
                     "max(i.recipientName), max(i.aciklama), min(i.islemTipi), " +
                     "max(case when i.isCancelled = true then 1 else 0 end) " +
                     "from IslemGecmisi i where i.batchId is not null " +
                     "group by i.batchId, i.user " +
                     "order by min(i.tarihSaat) desc")
       List<Object[]> findBatchSummariesAllUsers();

       /**
        * Batch kayıtlarını (batchId != null) gruplandırarak özet bilgilerini getirir.
        * Belirli bir kullanıcı için (PERSONEL).
        */
       @Query("select i.batchId, max(i.islemTipi), min(i.tarihSaat), count(i), i.user, " +
                     "max(i.recipientName), max(i.aciklama), min(i.islemTipi), " +
                     "max(case when i.isCancelled = true then 1 else 0 end) " +
                     "from IslemGecmisi i where i.batchId is not null and i.user.id = :userId " +
                     "group by i.batchId, i.user " +
                     "order by min(i.tarihSaat) desc")
       List<Object[]> findBatchSummariesByUser(Long userId);
}

package com.depo.entity;

import com.depo.enums.IslemTipi;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "islem_gecmisi", indexes = {
                @Index(name = "idx_islem_batch_id", columnList = "batch_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IslemGecmisi {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @ManyToOne(fetch = FetchType.EAGER)
        @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_islem_user", foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL"))
        private User user;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private IslemTipi islemTipi;

        @Column
        private String urunAdi;

        @Column
        private Integer miktar;

        @Column
        private String aciklama;

        @Column
        private String recipientName;

        /**
         * Ürün yeniden adlandırılsa bile geri almanın doğru stok kaydını bulmasını
         * sağlayan kalıcı referans. Eski audit kayıtlarıyla uyumluluk için nullable.
         */
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "product_id", foreignKey = @ForeignKey(
                name = "fk_islem_product",
                foreignKeyDefinition = "FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL"
        ))
        private Product product;

        /**
         * Toplu işlemleri gruplamak için kullanılan UUID.
         * Aynı toplu işlemden gelen tüm kayıtlar aynı batchId'ye sahip olur.
         * Tekil işlemlerde null'dır.
         */
        @Column(name = "batch_id", length = 36)
        private String batchId;

        /**
         * Kaydın ait olduğu toplu işlemin geri alınıp alınmadığını gösterir.
         * Audit kaydı silinmez; geri alma sonrasında batch içindeki tüm kayıtlar
         * iptal edilmiş olarak işaretlenir.
         */
        @Column(nullable = false)
        @Builder.Default
        private boolean isCancelled = false;

        @CreationTimestamp
        @Column(updatable = false)
        private LocalDateTime tarihSaat;
}

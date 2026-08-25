package com.depo.entity;

import com.depo.enums.MovementType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(
            name = "fk_movement_product",
            foreignKeyDefinition = "FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE"
    ))
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType movementType;

    @Column(nullable = false)
    private Integer quantity;

    @Column
    private String recipientName;

    @Column
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "created_by", foreignKey = @ForeignKey(
            name = "fk_movement_user",
            foreignKeyDefinition = "FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL"
    ))
    private User createdBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isCancelled = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}

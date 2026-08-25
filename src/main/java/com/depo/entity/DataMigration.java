package com.depo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persistent marker for one-time startup data migrations.
 */
@Entity
@Table(name = "data_migrations")
@Getter
@NoArgsConstructor
public class DataMigration {

    @Id
    @Column(name = "migration_key", length = 100, nullable = false)
    private String key;

    @CreationTimestamp
    @Column(name = "applied_at", nullable = false, updatable = false)
    private LocalDateTime appliedAt;

    public DataMigration(String key) {
        this.key = key;
    }
}

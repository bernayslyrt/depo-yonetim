package com.depo.bulkimport.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Belgeden okunan ham ürün verisini ön izleme amacıyla frontend'e döndürmek için kullanılır.
 * Henüz veritabanına yazılmamış, kullanıcı onayı bekleyen satırları temsil eder.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPreviewDto {

    public static final int MAX_PRODUCT_NAME_LENGTH = 255;
    public static final int MAX_PRODUCT_CODE_LENGTH = 255;

    /** Belgedeki satır numarası (1-indexed) */
    private Integer rowNumber;

    /** Ürün kodu */
    @Size(max = MAX_PRODUCT_CODE_LENGTH, message = "Ürün kodu en fazla 255 karakter olabilir.")
    private String productCode;

    /** Ürün adı */
    @NotBlank(message = "Ürün adı boş olamaz.")
    @Size(max = MAX_PRODUCT_NAME_LENGTH, message = "Ürün adı en fazla 255 karakter olabilir.")
    private String productName;

    /** Ayrıştırılmış miktar (tam sayı). Geçersizse null olabilir. */
    @NotNull(message = "Miktar boş olamaz.")
    @Min(value = 1, message = "Miktar en az 1 olmalıdır.")
    private Integer quantity;

    /** Server-authoritative imported quantity represented by this logical row. */
    private Integer importedQuantity;

    /**
     * LLM'nin quantity alanı için döndürdüğü ham değer (örn. "4 bidon", "null").
     * isValid=false olduğunda frontend bu alanı hata detayı olarak gösterebilir.
     */
    private String rawQuantityText;

    /** Birim fiyat */
    private BigDecimal price;

    /** Doğrulama sonucu — false ise errorMessage dolu olur */
    private boolean isValid;

    /** Doğrulama hata mesajı (varsa) */
    private String errorMessage;

    /** Ürün adı semantik olarak belirsizse kullanıcı incelemesi gerekir. */
    private boolean reviewRequired;

    /** Ön izleme ekranında gösterilecek kısa inceleme nedeni. */
    private String reviewMessage;

    /** Name-based database match state shown in the bulk-import preview. */
    private String matchStatus;

    /** Exact canonical name computed by the backend; never supplied by AI. */
    private String canonicalName;

    /** Existing group total when matchStatus is Mevcut ürün. */
    private Integer existingStock;

    /** Projected group total when matchStatus is Mevcut ürün. */
    private Integer projectedStock;

    /** User-facing labels for unsafe historical duplicate-group attributes. */
    private List<String> conflictFields;

    /** Concise backend-generated explanation of the actual conflicting attributes. */
    private String conflictMessage;

    /** Compact, non-technical descriptions of historical rows involved in a conflict. */
    private List<String> matchedProductSummaries;

    /** Backend-provided existing records that may be selected for this import row. */
    private List<ProductMatchCandidateDto> matchCandidates;

    /** EXISTING or NEW when resolved; null while a canonical conflict is unresolved. */
    private String resolutionType;

    /** Explicit existing product choice. IDs are never used as the primary UI label. */
    private Long selectedProductId;

    /** Snapshot token used to detect changed candidate groups at confirmation. */
    private String matchFingerprint;

    /** Distinguishes name-match review state from document/name-quality review state. */
    private boolean matchReviewRequired;

    /** Preserves document/name-quality review while a separate match conflict is resolved. */
    private boolean documentReviewRequired;

    private String documentReviewMessage;

    /** Set only when a user manually supplies the product for an unresolved source gap. */
    private String resolvedSourceRecordId;

    /** All source-gap IDs represented after same-import canonical consolidation. */
    private List<String> resolvedSourceRecordIds;

    /**
     * Stable source rows/records whose quantities are represented by this logical item.
     * Used only to guarantee that rematching and consolidation count each source record once.
     */
    private List<String> contributingSourceRecordIds;

    /**
     * Server-captured product-name cells for trusted structured Excel contributions.
     * The keys are physical source IDs; values are never supplied by Ollama.
     */
    private Map<String, String> authoritativeSourceProductNames;

    /**
     * Opaque server-issued identities of preview rows represented after consolidation.
     * They bind immutable quantity/provenance snapshots across rematching and confirmation.
     */
    private List<String> previewItemIds;

    /** True when an Excel output could not be tied to one exact physical source row. */
    private boolean sourceIdentityReviewRequired;

    /** AI-echoed marker used only to align against server-created source metadata. */
    @JsonIgnore
    private String sourceRecordReference;
}

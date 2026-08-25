package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.dto.ProductMatchCandidateDto;
import com.depo.entity.Product;
import com.depo.enums.IslemTipi;
import com.depo.repository.ProductRepository;
import com.depo.repository.UserRepository;
import com.depo.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.depo.service.IslemGecmisiService;
import com.depo.service.ProductNameMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;

/**
 * Kullanıcının onayladığı toplu ürün listesini veritabanına işleyen servis.
 *
 * <p>İş mantığı:</p>
 * <ul>
 *   <li>normalize ad + seçilen kaynak tek ürüne düşerse → stoğu artırılır</li>
 *   <li>kanonik eşleşme yoksa → seçilen kaynakla yeni ürün oluşturulur</li>
 *   <li>kaynak filtresi sıfır veya birden fazla aday bırakırsa → açık kullanıcı seçimi istenir</li>
 * </ul>
 *
 * <p>Her toplu içe aktarım işlemi için tek bir {@code batchId} (UUID) üretilir ve
 * o işlemdeki tüm {@link com.depo.entity.IslemGecmisi} kayıtlarına atanır.
 * Bu sayede frontend'de kayıtlar tek özet satıra çökülebilir.</p>
 *
 * <p>Her işlem için {@link IslemGecmisiService} üzerinden aktif kullanıcı bilgisiyle
 * ilişkilendirilmiş audit log kaydı oluşturulur. Mevcut rol tabanlı görünürlük
 * (ADMIN tüm logları görür, PERSONEL sadece kendininkileri) korunur.</p>
 *
 * <p>Tüm işlem tek bir transaction içinde çalışır; herhangi bir hata olursa
 * otomatik rollback yapılır ({@code @Transactional}).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkProductService {

    private static final Set<String> ALLOWED_SOURCES = Set.of("Belediye", "Tubitak", "T3");

    private final ProductRepository productRepository;
    private final IslemGecmisiService islemGecmisiService;
    private final UserRepository userRepository;
    private final ProductNameMatchService productNameMatchService;

    /** Consolidates exact canonical-name duplicates before a source is selected. */
    public List<ProductPreviewDto> preparePreview(List<ProductPreviewDto> items) {
        long startedNanos = System.nanoTime();
        List<ProductPreviewDto> result = consolidate(items, false);
        log.info("Toplu ön izleme konsolidasyonu: inputRows={}, logicalRows={}, durationMs={}",
                items == null ? 0 : items.size(), result.size(), elapsedMillis(startedNanos));
        return result;
    }

    /** Re-evaluates consolidated rows using the source selected by the user. */
    public List<ProductPreviewDto> preparePreview(List<ProductPreviewDto> items, String source) {
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("Geçersiz ürün kaynağı: " + source);
        }
        long totalStartedNanos = System.nanoTime();
        long consolidationStartedNanos = System.nanoTime();
        List<ProductPreviewDto> consolidated = consolidate(items, false);
        long consolidationMillis = elapsedMillis(consolidationStartedNanos);
        long matchingStartedNanos = System.nanoTime();
        productNameMatchService.withMatchSnapshot(() -> {
            for (ProductPreviewDto item : consolidated) {
                if (ProductPreviewValidation.trimToNull(item.getProductName()) == null
                        || item.getQuantity() == null || item.getQuantity() < 1) {
                    continue;
                }
                applySourceMatch(item, source);
            }
            return null;
        });
        log.info("Kaynak-duyarlı toplu eşleştirme zamanlaması: inputRows={}, logicalRows={}, "
                        + "consolidationMs={}, dbMatchingMs={}, totalMs={}",
                items == null ? 0 : items.size(), consolidated.size(), consolidationMillis,
                elapsedMillis(matchingStartedNanos), elapsedMillis(totalStartedNanos));
        return consolidated;
    }

    private void applySourceMatch(ProductPreviewDto item, String source) {
        if (item.isReviewRequired() && !item.isMatchReviewRequired()) {
            item.setDocumentReviewRequired(true);
            item.setDocumentReviewMessage(item.getReviewMessage());
        }
        clearPreviousMatchState(item);
        ProductNameMatchService.SourceMatchResolution resolution =
                productNameMatchService.resolveForSource(item.getProductName(), source);
        List<Product> all = resolution.products();
        List<Product> selectedSource = resolution.selectedSourceProducts();
        item.setMatchCandidates(all.stream().map(this::candidate).toList());
        item.setMatchedProductSummaries(all.stream().map(this::historicalProductSummary).toList());
        item.setMatchFingerprint(matchFingerprint(item.getProductName(), source, all));
        item.setConflictFields(resolution.groupResolution().conflicts().stream()
                .map(ProductNameMatchService.ConflictDetail::field).toList());
        item.setConflictMessage(resolution.groupResolution().reviewReason());

        if (all.isEmpty()) {
            markNew(item);
        } else if (selectedSource.size() == 1) {
            markExisting(item, selectedSource.get(0));
        } else {
            item.setMatchStatus("Kontrol gerekli");
            item.setMatchReviewRequired(true);
            item.setReviewRequired(true);
            if (selectedSource.isEmpty()) {
                item.setReviewMessage("Bu ürün adıyla mevcut kayıtlar bulundu ancak seçilen kaynak "
                        + source + " ile eşleşen kayıt yok.");
            } else {
                item.setReviewMessage("Seçilen kaynak " + source + " için "
                        + selectedSource.size() + " mevcut kayıt bulundu; hedef kayıt seçilmelidir.");
            }
        }
    }

    private void clearPreviousMatchState(ProductPreviewDto item) {
        if (item.isMatchReviewRequired()) {
            item.setReviewRequired(item.isDocumentReviewRequired());
            item.setReviewMessage(item.getDocumentReviewMessage());
        }
        item.setMatchReviewRequired(false);
        item.setResolutionType(null);
        item.setSelectedProductId(null);
        item.setExistingStock(null);
        item.setProjectedStock(null);
    }

    private void markExisting(ProductPreviewDto item, Product product) {
        item.setMatchStatus("Mevcut ürün");
        item.setResolutionType("EXISTING");
        item.setSelectedProductId(product.getId());
        item.setExistingStock(product.getQuantity());
        item.setProjectedStock(product.getQuantity() + item.getQuantity());
    }

    private void markNew(ProductPreviewDto item) {
        item.setMatchStatus("Yeni ürün");
        item.setResolutionType("NEW");
        item.setSelectedProductId(null);
    }

    /**
     * Onaylanan ürün listesini veritabanına toplu olarak işler.
     * Tüm kayıtlar aynı batchId UUID'ye sahip olur — bu sayede
     * işlem geçmişi sayfasında tek özet satıra çökülebilir.
     *
     * @param items onaylanan ProductPreviewDto listesi
     * @param source yeni oluşturulan tüm ürünlere atanacak kaynak
     * @return işlem özet bilgisi (eklenen ve güncellenen sayıları) ile batchId
     * @throws RuntimeException herhangi bir DB hatası (otomatik rollback)
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE)
    public BulkImportResult confirmBulkImport(List<ProductPreviewDto> items, String source) {
        return productNameMatchService.withNameMatchLock(() ->
                productNameMatchService.withMatchSnapshot(() -> confirmBulkImportLocked(items, source)));
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private BulkImportResult confirmBulkImportLocked(List<ProductPreviewDto> items, String source) {
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("Geçersiz ürün kaynağı: " + source);
        }

        int createdCount = 0;
        int updatedCount = 0;

        // Bu toplu işlem için tek bir batchId üret
        String batchId = UUID.randomUUID().toString();
        log.info("Toplu içe aktarım başlatıldı. BatchId: {}", batchId);

        // Aktif kullanıcıyı SecurityContextHolder üzerinden al (istek bazlı kullanıcı bilgisi)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User aktifKullanici = null;
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String username = auth.getName();
            aktifKullanici = userRepository.findByUsername(username).orElse(null);
            if (aktifKullanici != null) {
                log.info("Toplu içe aktarımı yapan kullanıcı: {}", aktifKullanici.getUsername());
            }
        }
        for (ProductPreviewDto item : consolidate(items, true)) {
            String productCode = ProductPreviewValidation.trimToNull(item.getProductCode());
            String productName = ProductPreviewValidation.trimToNull(item.getProductName());
            ProductNameMatchService.SourceMatchResolution resolution =
                    productNameMatchService.resolveForSource(productName, source);
            String liveFingerprint = matchFingerprint(productName, source, resolution.products());
            if (item.getMatchFingerprint() != null
                    && !Objects.equals(item.getMatchFingerprint(), liveFingerprint)) {
                throw new IllegalArgumentException("Satır " + item.getRowNumber()
                        + ": mevcut ürün eşleşmeleri değişti; ön izlemeyi yeniden kontrol edin.");
            }
            Product target = confirmedTarget(item, resolution);
            boolean createNew = confirmedNewProduct(item, resolution);

            if (target != null) {
                // ── Mevcut ürün: stoğu artır ──
                Product product = target;
                int previousStock = product.getQuantity();
                product.setQuantity(previousStock + item.getQuantity());
                productRepository.save(product);

                log.info("Stok güncellendi: {} [{}] → {} + {} = {}",
                        product.getName(), product.getCode(),
                        previousStock, item.getQuantity(), product.getQuantity());

                // İşlem geçmişi kaydı — batchId ile birlikte (stok güncelleme)
                islemGecmisiService.logEkle(
                    IslemTipi.PDF_YUKLEME,
                    product.getName(),
                    item.getQuantity(),
                    String.format("Toplu İçe Aktarım - Miktar: %d", item.getQuantity()),
                    null,
                    batchId,
                    product.getId()
                );

                updatedCount++;
            } else if (createNew) {
                // ── Yeni ürün: kayıt oluştur ──
                Product newProduct = Product.builder()
                        .code(productCode)
                        .name(productName)
                        .quantity(item.getQuantity())
                        .source(source)
                        .build();
                productRepository.save(newProduct);

                log.info("Yeni ürün oluşturuldu: {} [{}], Miktar: {}, Kaynak: {}",
                        newProduct.getName(), newProduct.getCode(), newProduct.getQuantity(), source);

                // İşlem geçmişi kaydı — batchId ile birlikte (yeni ürün oluşturma)
                islemGecmisiService.logEkle(
                    IslemTipi.PDF_YUKLEME,
                    newProduct.getName(),
                    newProduct.getQuantity(),
                    String.format("Toplu İçe Aktarım - Miktar: %d", newProduct.getQuantity()),
                    null,
                    batchId,
                    newProduct.getId()
                );

                createdCount++;
            } else {
                throw new IllegalArgumentException("Satır " + item.getRowNumber()
                        + ": ürün eşleşme çakışması çözülmeden onay verilemez.");
            }
        }

        log.info("Toplu içe aktarım tamamlandı. BatchId: {}, Yeni: {}, Güncellenen: {}",
                batchId, createdCount, updatedCount);
        return new BulkImportResult(createdCount, updatedCount, batchId);
    }

    private Product confirmedTarget(
            ProductPreviewDto item,
            ProductNameMatchService.SourceMatchResolution resolution) {
        List<Product> selectedSource = resolution.selectedSourceProducts();
        if (selectedSource.size() == 1) {
            Product automaticTarget = selectedSource.get(0);
            if (item.getSelectedProductId() != null
                    && !item.getSelectedProductId().equals(automaticTarget.getId())) {
                throw new IllegalArgumentException("Satır " + item.getRowNumber()
                        + ": seçilen mevcut ürün artık kaynak eşleşmesiyle uyumlu değil.");
            }
            return automaticTarget;
        }
        if (!"EXISTING".equals(item.getResolutionType()) || item.getSelectedProductId() == null) {
            return null;
        }
        requireManualSnapshot(item);
        List<Product> allowed = selectedSource.isEmpty() ? resolution.products() : selectedSource;
        return allowed.stream()
                .filter(product -> item.getSelectedProductId().equals(product.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Satır " + item.getRowNumber()
                        + ": seçilen mevcut ürün bu normalize ad ve kaynak için artık geçerli değil."));
    }

    private boolean confirmedNewProduct(
            ProductPreviewDto item,
            ProductNameMatchService.SourceMatchResolution resolution) {
        if (resolution.products().isEmpty()) {
            return true;
        }
        if (!"NEW".equals(item.getResolutionType())) {
            return false;
        }
        if (!resolution.selectedSourceProducts().isEmpty()) {
            throw new IllegalArgumentException("Satır " + item.getRowNumber()
                    + ": seçilen kaynakta mevcut ürün varken yeni kayıt oluşturulamaz.");
        }
        requireManualSnapshot(item);
        return true;
    }

    private void requireManualSnapshot(ProductPreviewDto item) {
        if (item.getMatchFingerprint() == null || item.getMatchFingerprint().isBlank()) {
            throw new IllegalArgumentException("Satır " + item.getRowNumber()
                    + ": manuel eşleşme seçimi güncel bir ön izlemeye dayanmıyor.");
        }
    }

    private List<ProductPreviewDto> consolidate(List<ProductPreviewDto> items, boolean validate) {
        Map<String, ProductPreviewDto> byCanonicalName = new LinkedHashMap<>();
        List<ProductPreviewDto> passthrough = new ArrayList<>();
        for (ProductPreviewDto item : items) {
            if (item != null) {
                log.info("Konsolidasyon öncesi miktar DTO'su: row={}, name={}, quantity={}, "
                                + "contributionIds={}, sourceIdentityReview={}",
                        item.getRowNumber(), item.getProductName(), item.getQuantity(),
                        item.getContributingSourceRecordIds(), item.isSourceIdentityReviewRequired());
            }
            if (item == null) {
                passthrough.add(null);
                continue;
            }
            ProductPreviewDto copy = copyOf(item);
            String authoritativeCanonicalName = restoreAuthoritativeStructuredName(copy);
            if (validate) {
                validateConfirmedItem(copy);
            }
            if (copy.isSourceIdentityReviewRequired()) {
                applyDerivedImportState(copy, productNameMatchService.normalize(
                        ProductPreviewValidation.trimToNull(copy.getProductName())));
                passthrough.add(copy);
                continue;
            }
            String canonicalName = authoritativeCanonicalName != null
                    ? authoritativeCanonicalName
                    : productNameMatchService.normalize(
                            ProductPreviewValidation.trimToNull(copy.getProductName()));
            if (canonicalName == null || copy.getQuantity() == null) {
                applyDerivedImportState(copy, canonicalName);
                passthrough.add(copy);
                continue;
            }
            applyDerivedImportState(copy, canonicalName);
            ensureResolvedSourceIds(copy);
            ProductPreviewDto first = byCanonicalName.putIfAbsent(canonicalName, copy);
            if (first != null) {
                boolean repeatedSourceContribution = sameSourceContribution(first, copy);
                if (!repeatedSourceContribution) {
                    rejectPartialSourceOverlap(first, copy);
                    first.setQuantity(first.getQuantity() + copy.getQuantity());
                    first.setImportedQuantity(first.getQuantity());
                } else {
                    log.warn("Tekrarlanan kaynak miktar katkısı yok sayıldı: ürün={}, kaynakKayıtları={}, miktar={}",
                            copy.getProductName(), sourceContributionIds(copy), copy.getQuantity());
                }
                mergeContributingSourceIds(first, copy);
                mergeAuthoritativeSourceNames(first, copy);
                mergePreviewItemIds(first, copy);
                List<String> combinedIds = new ArrayList<>(first.getResolvedSourceRecordIds());
                copy.getResolvedSourceRecordIds().stream()
                        .filter(id -> !combinedIds.contains(id))
                        .forEach(combinedIds::add);
                first.setResolvedSourceRecordIds(List.copyOf(combinedIds));
                log.info("Aynı içe aktarımdaki güvenilir satırlar konsolide edildi: "
                                + "canonicalName={}, sourceIds={}, authoritativeNames={}",
                        canonicalName, sourceContributionIds(first),
                        first.getAuthoritativeSourceProductNames());
            }
        }
        List<ProductPreviewDto> result = new ArrayList<>(byCanonicalName.values());
        result.addAll(passthrough);
        validateStructuredProvenance(items, result);
        logConsolidationDiagnostics(items, result);
        return result;
    }

    /** Diagnostic-only trace for manual verification of same-import consolidation. */
    private void logConsolidationDiagnostics(
            List<ProductPreviewDto> input,
            List<ProductPreviewDto> consolidated) {
        Map<String, SourceContributionDiagnostic> sourceDetails = new LinkedHashMap<>();
        if (input != null) {
            input.stream().filter(Objects::nonNull).forEach(item -> {
                Set<String> sourceIds = sourceContributionIds(item);
                Map<String, String> authoritativeNames = cleanAuthoritativeSourceNames(item);
                sourceIds.forEach(sourceId -> sourceDetails.putIfAbsent(sourceId,
                        new SourceContributionDiagnostic(
                                sourceId,
                                authoritativeNames.get(sourceId),
                                sourceIds.size() == 1 ? item.getQuantity() : null)));
            });
        }

        int multiSourceGroups = 0;
        int rowsReducedByConsolidation = 0;
        for (ProductPreviewDto item : consolidated) {
            if (item == null) {
                continue;
            }
            Set<String> sourceIds = sourceContributionIds(item);
            if (sourceIds.size() <= 1) {
                continue;
            }
            multiSourceGroups++;
            rowsReducedByConsolidation += sourceIds.size() - 1;
            Map<String, String> authoritativeNames = cleanAuthoritativeSourceNames(item);
            String sources = sourceIds.stream()
                    .map(sourceId -> formatSourceContribution(
                            sourceDetails.get(sourceId), authoritativeNames.get(sourceId)))
                    .collect(java.util.stream.Collectors.joining(",\n  "));
            log.info("CONSOLIDATION_GROUP\nname={}\nsources=[\n  {}\n]\nfinalQuantity={}",
                    item.getCanonicalName() == null ? item.getProductName() : item.getCanonicalName(),
                    sources, item.getQuantity());
        }
        log.info("CONSOLIDATION_SUMMARY|physicalContributionCount={}|logicalPreviewCount={}"
                        + "|multiSourceConsolidationGroups={}|rowsReducedByConsolidation={}",
                sourceDetails.size(), consolidated.size(), multiSourceGroups, rowsReducedByConsolidation);
    }

    private String formatSourceContribution(
            SourceContributionDiagnostic source,
            String consolidatedAuthoritativeName) {
        if (source == null) {
            return "unknown source";
        }
        String location = formatSourceLocation(source.sourceId());
        String name = source.authoritativeProductName() == null
                ? consolidatedAuthoritativeName
                : source.authoritativeProductName();
        return location + ": \"" + (name == null ? "<missing>" : name) + "\" qty="
                + (source.quantity() == null ? "<already-consolidated>" : source.quantity());
    }

    private String formatSourceLocation(String sourceId) {
        if (sourceId != null && sourceId.startsWith("xlsx:")) {
            int rowMarker = sourceId.lastIndexOf(":row:");
            if (rowMarker > "xlsx:".length()) {
                return sourceId.substring("xlsx:".length(), rowMarker) + " row "
                        + sourceId.substring(rowMarker + ":row:".length());
            }
        }
        return sourceId == null ? "<missing source>" : sourceId;
    }

    /**
     * Enforces the structured-Excel ordering invariant before canonical grouping:
     * physical source cell -> preview name -> canonical name -> consolidation.
     */
    private String restoreAuthoritativeStructuredName(ProductPreviewDto item) {
        Map<String, String> names = cleanAuthoritativeSourceNames(item);
        if (names.isEmpty()) {
            item.setAuthoritativeSourceProductNames(Map.of());
            return null;
        }

        Set<String> contributionIds = sourceContributionIds(item);
        if (!contributionIds.containsAll(names.keySet())) {
            throw new IllegalArgumentException(
                    "Güvenilir Excel ürün adı kaynak satırıyla eşleşmiyor; ön izlemeyi yeniden oluşturun.");
        }
        Map<String, String> canonicalBySource = new LinkedHashMap<>();
        names.forEach((sourceId, sourceName) -> canonicalBySource.put(
                sourceId, productNameMatchService.normalize(sourceName)));
        Set<String> canonicalNames = new LinkedHashSet<>(canonicalBySource.values());
        canonicalNames.remove(null);
        if (canonicalNames.size() != 1) {
            throw new IllegalArgumentException(
                    "Farklı güvenilir Excel ürün adları tek ön izleme satırında birleştirilemez.");
        }

        item.setAuthoritativeSourceProductNames(Map.copyOf(names));
        item.setProductName(names.values().iterator().next());
        return canonicalNames.iterator().next();
    }

    private Map<String, String> cleanAuthoritativeSourceNames(ProductPreviewDto item) {
        if (item.getAuthoritativeSourceProductNames() == null) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        item.getAuthoritativeSourceProductNames().forEach((sourceId, sourceName) -> {
            String cleanName = ProductPreviewValidation.trimToNull(sourceName);
            if (sourceId != null && !sourceId.isBlank() && cleanName != null) {
                names.put(sourceId, cleanName);
            }
        });
        return names;
    }

    private void validateStructuredProvenance(
            List<ProductPreviewDto> input,
            List<ProductPreviewDto> consolidated) {
        Set<String> trustedInputIds = authoritativeSourceIds(input);
        if (trustedInputIds.isEmpty()) {
            return;
        }
        Set<String> representedIds = authoritativeSourceIds(consolidated);
        long representedOccurrences = consolidated.stream()
                .filter(Objects::nonNull)
                .map(this::cleanAuthoritativeSourceNames)
                .mapToLong(names -> names.size())
                .sum();
        if (!trustedInputIds.equals(representedIds)
                || representedOccurrences != representedIds.size()) {
            throw new IllegalArgumentException(
                    "Güvenilir Excel kaynak satırları konsolidasyon sırasında kayboldu; "
                            + "ön izlemeyi yeniden oluşturun.");
        }
        log.info("Yapılandırılmış Excel kaynak doğrulaması: trustedPhysicalIds={}, "
                        + "representedIds={}, logicalGroups={}",
                trustedInputIds.size(), representedIds.size(),
                consolidated.stream()
                        .filter(Objects::nonNull)
                        .filter(item -> item.getAuthoritativeSourceProductNames() != null
                                && !item.getAuthoritativeSourceProductNames().isEmpty())
                        .count());
    }

    private Set<String> authoritativeSourceIds(List<ProductPreviewDto> items) {
        Set<String> ids = new LinkedHashSet<>();
        if (items == null) {
            return ids;
        }
        items.stream().filter(Objects::nonNull)
                .map(this::cleanAuthoritativeSourceNames)
                .map(Map::keySet)
                .forEach(ids::addAll);
        return ids;
    }

    private record SourceContributionDiagnostic(
            String sourceId,
            String authoritativeProductName,
            Integer quantity) {
    }

    private void applyDerivedImportState(ProductPreviewDto item, String canonicalName) {
        item.setCanonicalName(canonicalName);
        item.setImportedQuantity(item.getQuantity());
    }

    private ProductPreviewDto copyOf(ProductPreviewDto item) {
        if (item == null) {
            return null;
        }
        return ProductPreviewDto.builder()
                .rowNumber(item.getRowNumber())
                .productCode(item.getProductCode())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .importedQuantity(item.getImportedQuantity())
                .rawQuantityText(item.getRawQuantityText())
                .price(item.getPrice())
                .isValid(item.isValid())
                .errorMessage(item.getErrorMessage())
                .reviewRequired(item.isReviewRequired())
                .reviewMessage(item.getReviewMessage())
                .matchStatus(item.getMatchStatus())
                .canonicalName(item.getCanonicalName())
                .existingStock(item.getExistingStock())
                .projectedStock(item.getProjectedStock())
                .conflictFields(item.getConflictFields())
                .conflictMessage(item.getConflictMessage())
                .matchedProductSummaries(item.getMatchedProductSummaries())
                .matchCandidates(item.getMatchCandidates())
                .resolutionType(item.getResolutionType())
                .selectedProductId(item.getSelectedProductId())
                .matchFingerprint(item.getMatchFingerprint())
                .matchReviewRequired(item.isMatchReviewRequired())
                .documentReviewRequired(item.isDocumentReviewRequired())
                .documentReviewMessage(item.getDocumentReviewMessage())
                .resolvedSourceRecordId(item.getResolvedSourceRecordId())
                .resolvedSourceRecordIds(item.getResolvedSourceRecordIds())
                .contributingSourceRecordIds(item.getContributingSourceRecordIds())
                .authoritativeSourceProductNames(item.getAuthoritativeSourceProductNames())
                .previewItemIds(item.getPreviewItemIds())
                .sourceIdentityReviewRequired(item.isSourceIdentityReviewRequired())
                .build();
    }

    private boolean sameSourceContribution(
            ProductPreviewDto first,
            ProductPreviewDto copy) {
        Set<String> firstIds = sourceContributionIds(first);
        Set<String> copyIds = sourceContributionIds(copy);
        return !firstIds.isEmpty() && !copyIds.isEmpty() && firstIds.containsAll(copyIds);
    }

    private void rejectPartialSourceOverlap(
            ProductPreviewDto first,
            ProductPreviewDto copy) {
        Set<String> firstIds = sourceContributionIds(first);
        Set<String> copyIds = sourceContributionIds(copy);
        if (firstIds.isEmpty() || copyIds.isEmpty()) {
            return;
        }
        Set<String> overlap = new LinkedHashSet<>(firstIds);
        overlap.retainAll(copyIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aynı kaynak satırı birden fazla miktar katkısında bulundu; ön izlemeyi yeniden oluşturun.");
        }
    }

    private void mergeContributingSourceIds(
            ProductPreviewDto target,
            ProductPreviewDto source) {
        Set<String> combined = sourceContributionIds(target);
        combined.addAll(sourceContributionIds(source));
        target.setContributingSourceRecordIds(List.copyOf(combined));
    }

    private void mergeAuthoritativeSourceNames(
            ProductPreviewDto target,
            ProductPreviewDto source) {
        Map<String, String> combined = new LinkedHashMap<>(cleanAuthoritativeSourceNames(target));
        cleanAuthoritativeSourceNames(source).forEach((sourceId, sourceName) -> {
            String previous = combined.putIfAbsent(sourceId, sourceName);
            if (previous != null && !previous.equals(sourceName)) {
                throw new IllegalArgumentException(
                        "Aynı güvenilir Excel satırı için çelişkili ürün adları bulundu.");
            }
        });
        target.setAuthoritativeSourceProductNames(Map.copyOf(combined));
    }

    private void mergePreviewItemIds(
            ProductPreviewDto target,
            ProductPreviewDto source) {
        Set<String> combined = new LinkedHashSet<>();
        if (target.getPreviewItemIds() != null) {
            target.getPreviewItemIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(combined::add);
        }
        if (source.getPreviewItemIds() != null) {
            source.getPreviewItemIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(combined::add);
        }
        target.setPreviewItemIds(List.copyOf(combined));
    }

    private Set<String> sourceContributionIds(ProductPreviewDto item) {
        Set<String> ids = new LinkedHashSet<>();
        if (item.getContributingSourceRecordIds() != null) {
            item.getContributingSourceRecordIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
        }
        if (item.getResolvedSourceRecordIds() != null) {
            item.getResolvedSourceRecordIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
        }
        if (item.getResolvedSourceRecordId() != null
                && !item.getResolvedSourceRecordId().isBlank()) {
            ids.add(item.getResolvedSourceRecordId());
        }
        return ids;
    }

    private void ensureResolvedSourceIds(ProductPreviewDto item) {
        List<String> ids = new ArrayList<>();
        if (item.getResolvedSourceRecordIds() != null) {
            item.getResolvedSourceRecordIds().stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (item.getResolvedSourceRecordId() != null && !ids.contains(item.getResolvedSourceRecordId())) {
            ids.add(item.getResolvedSourceRecordId());
        }
        item.setResolvedSourceRecordIds(List.copyOf(ids));
    }

    private ProductMatchCandidateDto candidate(Product product) {
        return ProductMatchCandidateDto.builder()
                .productId(product.getId())
                .productName(product.getName())
                .source(product.getSource())
                .unit(product.getUnit())
                .category(product.getCategory() == null ? null : product.getCategory().getName())
                .shelfLocation(product.getShelfLocation())
                .currentStock(product.getQuantity())
                .build();
    }

    private String matchFingerprint(String productName, String source, List<Product> products) {
        StringBuilder material = new StringBuilder()
                .append(productNameMatchService.normalize(productName)).append('|')
                .append(source).append('|');
        products.stream()
                .sorted(Comparator.comparing(Product::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(product -> material
                        .append(product.getId()).append(':')
                        .append(product.getName()).append(':')
                        .append(product.getSource()).append(':')
                        .append(product.getUnit()).append(':')
                        .append(product.getCategory() == null ? null : product.getCategory().getId()).append(':')
                        .append(product.getMinStockLevel()).append(':')
                        .append(product.getShelfLocation()).append(':')
                        .append(product.getQuantity()).append('|'));
        return UUID.nameUUIDFromBytes(material.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String historicalProductSummary(Product product) {
        String source = ProductPreviewValidation.trimToNull(product.getSource());
        String unit = ProductPreviewValidation.trimToNull(product.getUnit());
        return product.getName() + " — " + (source == null ? "Kaynak belirtilmemiş" : source)
                + " — " + (unit == null ? "Birim belirtilmemiş" : unit)
                + " — stok " + product.getQuantity();
    }

    private void validateConfirmedItem(ProductPreviewDto item) {
        if (item == null) {
            throw new IllegalArgumentException("Onaylanan ürün satırı boş olamaz.");
        }
        List<String> structuralErrors = ProductPreviewValidation.structuralErrors(item);
        if (!structuralErrors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber() + ": " + String.join("; ", structuralErrors) + ".");
        }
        if (item.getImportedQuantity() != null
                && !Objects.equals(item.getImportedQuantity(), item.getQuantity())) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber()
                            + ": içe aktarılan miktar ön izleme sonrasında değiştirilemez.");
        }
        String liveCanonicalName = productNameMatchService.normalize(
                ProductPreviewValidation.trimToNull(item.getProductName()));
        if (item.getCanonicalName() != null
                && !Objects.equals(item.getCanonicalName(), liveCanonicalName)) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber()
                            + ": ürün adı değişti; eşleşmeleri yeniden değerlendirin.");
        }
        if (item.isMatchReviewRequired()) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber() + ": ürün eşleşme çakışması çözülmeden onay verilemez.");
        }
        if (item.isReviewRequired()) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber()
                            + ": ürün adı manuel olarak kontrol edilip düzeltilmelidir.");
        }
        if (!item.isValid()) {
            throw new IllegalArgumentException(
                    "Satır " + item.getRowNumber() + ": geçersiz ön izleme satırı onaylanamaz.");
        }
    }

    /**
     * Toplu içe aktarım sonuç bilgisini taşıyan immutable record.
     *
     * @param createdCount yeni oluşturulan ürün sayısı
     * @param updatedCount stoğu güncellenen ürün sayısı
     * @param batchId      bu toplu işlemin UUID'si
     */
    public record BulkImportResult(int createdCount, int updatedCount, String batchId) {

        /** Toplam işlenen ürün sayısı */
        public int totalProcessed() {
            return createdCount + updatedCount;
        }

        /** Kullanıcıya gösterilecek özet mesaj */
        public String toSummaryMessage() {
            return String.format(
                    "Toplu içe aktarım başarılı. %d yeni ürün eklendi, %d mevcut ürünün stoğu güncellendi. Toplam: %d",
                    createdCount, updatedCount, totalProcessed()
            );
        }
    }
}

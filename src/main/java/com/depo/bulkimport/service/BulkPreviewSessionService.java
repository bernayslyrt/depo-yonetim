package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.dto.UnresolvedSourceRecordDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short-lived, in-memory integrity guard between preview and confirmation. */
@Service
@Slf4j
public class BulkPreviewSessionService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private final Map<String, PreviewSession> sessions = new HashMap<>();

    public synchronized BulkPreviewResponseDto register(BulkPreviewResponseDto preview) {
        long startedNanos = System.nanoTime();
        purgeExpired();
        String previewId = UUID.randomUUID().toString();
        Set<String> gapIds = preview.getUnresolvedRecords().stream()
                .map(UnresolvedSourceRecordDto::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, ImmutableItemSnapshot> itemSnapshots = new HashMap<>();
        for (ProductPreviewDto item : preview.getProducts()) {
            String itemId = UUID.randomUUID().toString();
            item.setPreviewItemIds(List.of(itemId));
            item.setImportedQuantity(item.getQuantity());
            itemSnapshots.put(itemId, snapshot(item));
        }
        sessions.put(previewId, new PreviewSession(
                Instant.now().plus(SESSION_TTL), gapIds, itemSnapshots));
        log.info("Toplu içe aktarım ön izleme oturumu açıldı: previewId={}, "
                        + "products={}, unresolvedRecords={}, complete={}, durationMs={}",
                previewId, preview.getProducts().size(), gapIds.size(), preview.isComplete(),
                (System.nanoTime() - startedNanos) / 1_000_000);
        return preview.withPreviewId(previewId);
    }

    /** Restores immutable import state before source-aware matching is recomputed. */
    public synchronized List<ProductPreviewDto> restoreForMatching(
            String previewId,
            List<ProductPreviewDto> items) {
        purgeExpired();
        PreviewSession session = requireActive(previewId);
        if (session.confirming) {
            throw new IllegalArgumentException("Bu ön izleme için onay işlemi devam ediyor.");
        }
        return restoreImmutableState(session, items);
    }

    /** Locks the session and returns server-restored rows for confirmation. */
    public synchronized List<ProductPreviewDto> beginConfirmation(
            String previewId,
            List<ProductPreviewDto> items) {
        purgeExpired();
        PreviewSession session = requireActive(previewId);
        if (session.confirming) {
            throw new IllegalArgumentException("Bu ön izleme için onay işlemi zaten devam ediyor.");
        }

        List<ProductPreviewDto> restoredItems = restoreImmutableState(session, items);

        Map<String, Integer> suppliedGapCounts = new HashMap<>();
        for (ProductPreviewDto item : restoredItems) {
            if (item == null) {
                continue;
            }
            Set<String> itemGapIds = new HashSet<>();
            if (item.getResolvedSourceRecordId() != null && !item.getResolvedSourceRecordId().isBlank()) {
                itemGapIds.add(item.getResolvedSourceRecordId());
            }
            if (item.getResolvedSourceRecordIds() != null) {
                item.getResolvedSourceRecordIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .forEach(itemGapIds::add);
            }
            itemGapIds.forEach(id -> suppliedGapCounts.merge(id, 1, Integer::sum));
        }
        Set<String> suppliedGapIds = new HashSet<>(suppliedGapCounts.keySet());
        Set<String> unexpected = new HashSet<>(suppliedGapIds);
        unexpected.removeAll(session.unresolvedGapIds);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("Bilinmeyen çözümlenmiş kayıt kimliği gönderildi.");
        }
        Set<String> missing = new HashSet<>(session.unresolvedGapIds);
        missing.removeAll(suppliedGapIds);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tüm çözülemeyen kaynak kayıtları manuel ürünle tamamlanmadan onay verilemez. "
                            + "Eksik kayıt sayısı: " + missing.size());
        }
        boolean duplicate = suppliedGapCounts.values().stream().anyMatch(count -> count != 1);
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Her çözülemeyen kaynak kaydı tam olarak bir manuel ürünle eşleştirilmelidir.");
        }
        session.confirming = true;
        return restoredItems;
    }

    private List<ProductPreviewDto> restoreImmutableState(
            PreviewSession session,
            List<ProductPreviewDto> items) {
        if (items == null) {
            throw new IllegalArgumentException("Ön izleme ürün listesi eksik.");
        }
        Set<String> suppliedItemIds = new HashSet<>();
        List<ProductPreviewDto> restored = new ArrayList<>(items.size());
        for (ProductPreviewDto item : items) {
            if (item == null) {
                restored.add(null);
                continue;
            }
            List<String> itemIds = cleanIds(item.getPreviewItemIds());
            if (itemIds.isEmpty()) {
                itemIds = registerManualGapItem(session, item);
            }

            Integer quantity = 0;
            Set<String> contributionIds = new LinkedHashSet<>();
            Map<String, String> authoritativeSourceProductNames = new LinkedHashMap<>();
            Set<String> resolvedGapIds = new LinkedHashSet<>();
            boolean sourceIdentityReviewRequired = false;
            boolean everyQuantityLocked = true;
            for (String itemId : itemIds) {
                if (!suppliedItemIds.add(itemId)) {
                    throw new IllegalArgumentException(
                            "Aynı ön izleme satırı birden fazla kez gönderildi.");
                }
                ImmutableItemSnapshot snapshot = session.itemSnapshots.get(itemId);
                if (snapshot == null) {
                    throw new IllegalArgumentException(
                            "Bilinmeyen veya süresi dolmuş ön izleme satırı gönderildi.");
                }
                quantity = quantity == null || snapshot.importedQuantity == null
                        ? null
                        : Math.addExact(quantity, snapshot.importedQuantity);
                contributionIds.addAll(snapshot.contributionIds);
                snapshot.authoritativeSourceProductNames.forEach((sourceId, sourceName) -> {
                    String previous = authoritativeSourceProductNames.putIfAbsent(sourceId, sourceName);
                    if (previous != null && !previous.equals(sourceName)) {
                        throw new IllegalArgumentException(
                                "Aynı kaynak satırı için çelişkili ürün adları bulundu.");
                    }
                });
                resolvedGapIds.addAll(snapshot.resolvedGapIds);
                sourceIdentityReviewRequired |= snapshot.sourceIdentityReviewRequired;
                everyQuantityLocked &= snapshot.quantityLocked;
            }
            if (!everyQuantityLocked && itemIds.size() == 1) {
                quantity = item.getQuantity();
            } else if (!Objects.equals(item.getQuantity(), quantity)) {
                throw new IllegalArgumentException(
                        "İçe aktarılan miktar ön izleme sonrasında değiştirilemez; dosyayı yeniden inceleyin.");
            }
            item.setPreviewItemIds(List.copyOf(itemIds));
            item.setQuantity(quantity);
            item.setImportedQuantity(quantity);
            item.setContributingSourceRecordIds(List.copyOf(contributionIds));
            item.setAuthoritativeSourceProductNames(Map.copyOf(authoritativeSourceProductNames));
            item.setResolvedSourceRecordId(null);
            item.setResolvedSourceRecordIds(List.copyOf(resolvedGapIds));
            item.setSourceIdentityReviewRequired(sourceIdentityReviewRequired);
            restored.add(item);
        }
        return restored;
    }

    private List<String> registerManualGapItem(
            PreviewSession session,
            ProductPreviewDto item) {
        Set<String> gapIds = itemGapIds(item);
        if (gapIds.size() != 1 || !session.unresolvedGapIds.containsAll(gapIds)) {
            throw new IllegalArgumentException(
                    "Ön izleme satır kimliği eksik veya manuel kaynak kaydı geçersiz.");
        }
        String gapId = gapIds.iterator().next();
        String itemId = session.manualItemIds.computeIfAbsent(
                gapId, ignored -> "manual:" + UUID.randomUUID());
        session.itemSnapshots.computeIfAbsent(itemId, ignored -> snapshot(item));
        return List.of(itemId);
    }

    private ImmutableItemSnapshot snapshot(ProductPreviewDto item) {
        List<String> contributionIds = cleanIds(item.getContributingSourceRecordIds());
        List<String> resolvedGapIds = List.copyOf(itemGapIds(item));
        return new ImmutableItemSnapshot(
                item.getQuantity(),
                List.copyOf(contributionIds),
                immutableAuthoritativeNames(item),
                resolvedGapIds,
                item.isSourceIdentityReviewRequired(),
                !contributionIds.isEmpty() || !resolvedGapIds.isEmpty());
    }

    private Map<String, String> immutableAuthoritativeNames(ProductPreviewDto item) {
        if (item.getAuthoritativeSourceProductNames() == null
                || item.getAuthoritativeSourceProductNames().isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        item.getAuthoritativeSourceProductNames().forEach((sourceId, sourceName) -> {
            if (sourceId != null && !sourceId.isBlank()
                    && ProductPreviewValidation.trimToNull(sourceName) != null) {
                names.put(sourceId, sourceName);
            }
        });
        return Map.copyOf(names);
    }

    private Set<String> itemGapIds(ProductPreviewDto item) {
        Set<String> ids = new LinkedHashSet<>();
        if (item.getResolvedSourceRecordId() != null
                && !item.getResolvedSourceRecordId().isBlank()) {
            ids.add(item.getResolvedSourceRecordId());
        }
        ids.addAll(cleanIds(item.getResolvedSourceRecordIds()));
        return ids;
    }

    private List<String> cleanIds(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    public synchronized void assertActive(String previewId) {
        purgeExpired();
        requireActive(previewId);
    }

    private PreviewSession requireActive(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            throw new IllegalArgumentException(
                    "Ön izleme oturumu eksik. Dosyayı yeniden inceleyin.");
        }
        PreviewSession session = sessions.get(previewId);
        if (session == null) {
            throw new IllegalArgumentException(
                    "Ön izleme oturumu bulunamadı veya süresi doldu. Dosyayı yeniden inceleyin.");
        }
        return session;
    }

    public synchronized void completeConfirmation(String previewId) {
        sessions.remove(previewId);
        log.info("Toplu içe aktarım ön izleme oturumu tüketildi: previewId={}", previewId);
    }

    /** Removes one preview when its import job is explicitly cancelled. */
    public synchronized void invalidate(String previewId) {
        if (previewId != null && !previewId.isBlank() && sessions.remove(previewId) != null) {
            log.info("İptal edilen toplu içe aktarım ön izleme oturumu kaldırıldı: previewId={}",
                    previewId);
        }
    }

    public synchronized void releaseConfirmation(String previewId) {
        PreviewSession session = sessions.get(previewId);
        if (session != null) {
            session.confirming = false;
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    private static final class PreviewSession {
        private final Instant expiresAt;
        private final Set<String> unresolvedGapIds;
        private final Map<String, ImmutableItemSnapshot> itemSnapshots;
        private final Map<String, String> manualItemIds = new HashMap<>();
        private boolean confirming;

        private PreviewSession(
                Instant expiresAt,
                Set<String> unresolvedGapIds,
                Map<String, ImmutableItemSnapshot> itemSnapshots) {
            this.expiresAt = expiresAt;
            this.unresolvedGapIds = Set.copyOf(unresolvedGapIds);
            this.itemSnapshots = new HashMap<>(itemSnapshots);
        }
    }

    private record ImmutableItemSnapshot(
            Integer importedQuantity,
            List<String> contributionIds,
            Map<String, String> authoritativeSourceProductNames,
            List<String> resolvedGapIds,
            boolean sourceIdentityReviewRequired,
            boolean quantityLocked) {
    }
}

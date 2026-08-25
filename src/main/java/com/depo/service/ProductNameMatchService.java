package com.depo.service;

import com.depo.entity.Product;
import com.depo.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Exact canonical-name lookup plus an in-process lock for name-based creation. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductNameMatchService {

    private final ProductRepository productRepository;
    private final Object importAndCreationLock = new Object();
    private final ThreadLocal<Map<String, List<Product>>> matchSnapshot = new ThreadLocal<>();

    public String normalize(String name) {
        return ProductNameNormalizer.normalize(name);
    }

    public List<Product> findMatches(String name) {
        String canonicalName = normalize(name);
        if (canonicalName == null) {
            return List.of();
        }
        Map<String, List<Product>> activeSnapshot = matchSnapshot.get();
        if (activeSnapshot != null) {
            return activeSnapshot.getOrDefault(canonicalName, List.of());
        }
        // Canonicalization is intentionally performed in Java: database collations
        // are not consistently Turkish-safe across installations.
        return productRepository.findAll().stream()
                .filter(product -> canonicalName.equals(normalize(product.getName())))
                .toList();
    }

    /**
     * Evaluates a canonical-name group without changing any historical rows.
     * A multi-row group is safe only when every non-name attribute that affects
     * inventory handling is identical. Product code is deliberately excluded:
     * it is not a reliable identity key in this application.
     */
    public MatchResolution resolve(String name) {
        return resolveProducts(findMatches(name));
    }

    public SourceMatchResolution resolveForSource(String name, String source) {
        List<Product> products = findMatches(name);
        List<Product> selectedSourceProducts = products.stream()
                .filter(product -> Objects.equals(product.getSource(), source))
                .toList();
        return new SourceMatchResolution(
                products,
                selectedSourceProducts,
                resolveProducts(products));
    }

    private MatchResolution resolveProducts(List<Product> matches) {
        if (matches.isEmpty()) {
            return new MatchResolution(matches, true, null, 0, null, List.of());
        }
        List<ConflictDetail> conflicts = findConflicts(matches);
        if (!conflicts.isEmpty()) {
            return new MatchResolution(matches, false, null, 0, describeConflicts(conflicts), conflicts);
        }
        Product target = matches.stream()
                .min(Comparator.comparing(Product::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow();
        int totalStock = matches.stream().map(Product::getQuantity).reduce(0, Math::addExact);
        return new MatchResolution(matches, true, target, totalStock, null, List.of());
    }

    private List<ConflictDetail> findConflicts(List<Product> products) {
        List<ConflictDetail> conflicts = new ArrayList<>();
        addConflict(conflicts, "Kategori", products,
                product -> product.getCategory() == null ? null : product.getCategory().getId(),
                product -> product.getCategory() == null ? "Belirtilmemiş" : product.getCategory().getName());
        addConflict(conflicts, "Kaynak", products, Product::getSource, Product::getSource);
        addConflict(conflicts, "Birim", products, Product::getUnit, Product::getUnit);
        addConflict(conflicts, "Minimum stok", products, Product::getMinStockLevel,
                product -> product.getMinStockLevel() == null ? null : String.valueOf(product.getMinStockLevel()));
        addConflict(conflicts, "Raf konumu", products, Product::getShelfLocation, Product::getShelfLocation);
        return List.copyOf(conflicts);
    }

    private void addConflict(
            List<ConflictDetail> conflicts,
            String field,
            List<Product> products,
            Function<Product, ?> identityExtractor,
            Function<Product, String> displayExtractor) {
        long distinctIdentities = products.stream().map(identityExtractor).distinct().count();
        if (distinctIdentities <= 1) {
            return;
        }
        List<String> values = products.stream()
                .map(displayExtractor)
                .map(value -> value == null || value.isBlank() ? "Belirtilmemiş" : value)
                .distinct()
                .toList();
        conflicts.add(new ConflictDetail(field, values));
    }

    private String describeConflicts(List<ConflictDetail> conflicts) {
        if (conflicts.size() == 1) {
            ConflictDetail conflict = conflicts.get(0);
            return conflict.field() + " farklı: " + String.join(" / ", conflict.values());
        }
        List<String> fields = conflicts.stream().map(ConflictDetail::field).toList();
        if (fields.size() == 2) {
            return fields.get(0) + " ve " + fields.get(1) + " farklı.";
        }
        return String.join(", ", fields.subList(0, fields.size() - 1))
                + " ve " + fields.get(fields.size() - 1) + " farklı.";
    }

    public record MatchResolution(
            List<Product> products,
            boolean safe,
            Product target,
            int totalStock,
            String reviewReason,
            List<ConflictDetail> conflicts) {
    }

    public record ConflictDetail(String field, List<String> values) {
    }

    public record SourceMatchResolution(
            List<Product> products,
            List<Product> selectedSourceProducts,
            MatchResolution groupResolution) {
    }

    public <T> T withNameMatchLock(Supplier<T> operation) {
        synchronized (importAndCreationLock) {
            return operation.get();
        }
    }

    /**
     * Loads the product table once for one preview/rematch/confirmation operation.
     * The snapshot is request-thread scoped and canonicalized in Java, preserving
     * Turkish-safe matching while preventing one full-table query per import row.
     */
    public <T> T withMatchSnapshot(Supplier<T> operation) {
        if (matchSnapshot.get() != null) {
            return operation.get();
        }
        long startedNanos = System.nanoTime();
        List<Product> products = productRepository.findAll();
        Map<String, List<Product>> indexed = products.stream()
                .filter(product -> normalize(product.getName()) != null)
                .collect(Collectors.groupingBy(
                        product -> normalize(product.getName()),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        log.info(
                "Kanonik ürün eşleştirme snapshot'ı hazırlandı: products={}, canonicalGroups={}, dbAndIndexMs={}",
                products.size(), indexed.size(), durationMillis);
        matchSnapshot.set(indexed);
        try {
            return operation.get();
        } finally {
            matchSnapshot.remove();
        }
    }
}

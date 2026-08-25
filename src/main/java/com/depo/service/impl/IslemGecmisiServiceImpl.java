package com.depo.service.impl;

import com.depo.dto.IslemGecmisiResponse;
import com.depo.dto.IslemSummaryResponse;
import com.depo.entity.IslemGecmisi;
import com.depo.entity.Product;
import com.depo.entity.User;
import com.depo.enums.IslemTipi;
import com.depo.exception.BadRequestException;
import com.depo.exception.InsufficientStockException;
import com.depo.exception.ResourceNotFoundException;
import com.depo.repository.IslemGecmisiRepository;
import com.depo.repository.ProductRepository;
import com.depo.repository.UserRepository;
import com.depo.service.IslemGecmisiService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IslemGecmisiServiceImpl implements IslemGecmisiService {

    private final IslemGecmisiRepository islemGecmisiRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Override
    public List<IslemGecmisiResponse> getIslemGecmisi() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        // ADMIN ise tüm kayıtları getir, değilse sadece kendi kayıtlarını
        boolean isAdmin = auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        List<IslemGecmisi> islemler;

        if (isAdmin) {
            islemler = islemGecmisiRepository.findAllByOrderByTarihSaatDesc();
        } else {
            User user = userRepository.findByUsername(username).orElseThrow();
            islemler = islemGecmisiRepository.findByUserIdOrderByTarihSaatDesc(user.getId());
        }

        return islemler.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<IslemSummaryResponse> getSummary() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        boolean isAdmin = auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        List<IslemSummaryResponse> result = new ArrayList<>();

        if (isAdmin) {
            // 1) Tekil (batchId = null) kayıtları ekle
            islemGecmisiRepository.findAllByOrderByTarihSaatDesc().stream()
                    .filter(i -> i.getBatchId() == null)
                    .map(this::mapToSummary)
                    .forEach(result::add);

            // 2) Batch özetlerini ekle
            islemGecmisiRepository.findBatchSummariesAllUsers()
                    .stream()
                    .map(this::mapBatchRowToSummary)
                    .forEach(result::add);
        } else {
            User user = userRepository.findByUsername(username).orElseThrow();
            Long userId = user.getId();

            // 1) Tekil (batchId = null) kayıtları ekle
            islemGecmisiRepository.findByUserIdOrderByTarihSaatDesc(userId).stream()
                    .filter(i -> i.getBatchId() == null)
                    .map(this::mapToSummary)
                    .forEach(result::add);

            // 2) Batch özetlerini ekle
            islemGecmisiRepository.findBatchSummariesByUser(userId)
                    .stream()
                    .map(this::mapBatchRowToSummary)
                    .forEach(result::add);
        }

        // Tarih sırasına göre tersten sırala
        result.sort(Comparator.comparing(IslemSummaryResponse::getTarihSaat).reversed());
        return result;
    }

    @Override
    public List<IslemGecmisiResponse> getByBatchId(String batchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

        List<IslemGecmisi> rows;

        if (isAdmin) {
            rows = islemGecmisiRepository.findByBatchIdOrderByTarihSaatAsc(batchId);
        } else {
            String username = auth.getName();
            User user = userRepository.findByUsername(username).orElseThrow();
            rows = islemGecmisiRepository.findByBatchIdAndUserIdOrderByTarihSaatAsc(batchId, user.getId());
        }

        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public int rollbackBatch(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new BadRequestException("Batch ID boş olamaz.");
        }

        List<IslemGecmisi> rows = islemGecmisiRepository.findByBatchIdForRollback(batchId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Toplu işlem bulunamadı. Batch ID: " + batchId);
        }

        if (rows.stream().anyMatch(IslemGecmisi::isCancelled)) {
            throw new BadRequestException("Bu toplu işlem daha önce geri alınmış. Batch ID: " + batchId);
        }

        // Aynı ürün bir batch içinde birden fazla kez geçebilir. Ürünleri bir kez
        // kilitleyip bütün ters hareketleri aynı managed entity üzerinde uygula.
        Map<String, Product> productsByName = new LinkedHashMap<>();
        Map<Long, Product> productsById = new LinkedHashMap<>();

        for (IslemGecmisi row : rows) {
            int amount = validateAmount(row);
            Product product = findProductForRollback(row, productsById, productsByName);

            switch (row.getIslemTipi()) {
                case STOK_GIRIS, PDF_YUKLEME -> reverseIncomingMovement(product, amount);
                case STOK_CIKIS -> reverseOutgoingMovement(product, amount);
                default -> throw new BadRequestException(
                        "Geri alınamayan işlem tipi: " + row.getIslemTipi());
            }
        }

        productRepository.saveAll(productsById.isEmpty() ? productsByName.values() : productsById.values());
        rows.forEach(row -> row.setCancelled(true));
        islemGecmisiRepository.saveAll(rows);
        return rows.size();
    }

    private int validateAmount(IslemGecmisi row) {
        if (row.getMiktar() == null || row.getMiktar() <= 0) {
            throw new BadRequestException(
                    "Geçersiz işlem miktarı. İşlem geçmişi ID: " + row.getId());
        }
        if (row.getUrunAdi() == null || row.getUrunAdi().isBlank()) {
            throw new BadRequestException(
                    "İşlem kaydında ürün adı bulunmuyor. İşlem geçmişi ID: " + row.getId());
        }
        return row.getMiktar();
    }

    private Product findUniqueProductForRollback(String productName) {
        List<Product> matches = productRepository.findAllByNameForUpdate(productName);
        if (matches.isEmpty()) {
            throw new BadRequestException(
                    "Geri alınacak ürün bulunamadı: " + productName);
        }
        if (matches.size() > 1) {
            throw new BadRequestException(
                    "Ürün adı birden fazla kayıtla eşleştiği için işlem güvenle geri alınamıyor: "
                            + productName);
        }
        return matches.get(0);
    }

    private Product findProductForRollback(
            IslemGecmisi row,
            Map<Long, Product> productsById,
            Map<String, Product> productsByName) {
        if (row.getProduct() != null) {
            Long productId = row.getProduct().getId();
            Product cached = productsById.get(productId);
            if (cached != null) {
                return cached;
            }
            Product product = productRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new BadRequestException(
                            "Geri alınacak ürün bulunamadı. Ürün ID: " + productId));
            productsById.put(productId, product);
            return product;
        }

        Product cached = productsByName.get(row.getUrunAdi());
        if (cached != null) {
            return cached;
        }
        Product product = findUniqueProductForRollback(row.getUrunAdi());
        productsByName.put(row.getUrunAdi(), product);
        productsById.put(product.getId(), product);
        return product;
    }

    private void reverseIncomingMovement(Product product, int amount) {
        int newQuantity = product.getQuantity() - amount;
        if (newQuantity < 0) {
            throw new InsufficientStockException(
                    "Geri alma işlemi stoku negatife düşürür. Ürün: " + product.getName()
                            + ", mevcut: " + product.getQuantity() + ", geri alınacak: " + amount);
        }
        product.setQuantity(newQuantity);
    }

    private void reverseOutgoingMovement(Product product, int amount) {
        long newQuantity = (long) product.getQuantity() + amount;
        if (newQuantity > Integer.MAX_VALUE) {
            throw new BadRequestException(
                    "Geri alma işlemi stok üst sınırını aşıyor. Ürün: " + product.getName());
        }
        product.setQuantity((int) newQuantity);
    }

    @Override
    public void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName) {
        logEkle(islemTipi, urunAdi, miktar, aciklama, recipientName, null);
    }

    @Override
    public void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName,
            String batchId) {
        logEkle(islemTipi, urunAdi, miktar, aciklama, recipientName, batchId, null);
    }

    @Override
    public void logEkle(IslemTipi islemTipi, String urunAdi, Integer miktar, String aciklama, String recipientName,
            String batchId, Long productId) {
        User user = getAktifKullanici();
        Product product = productId == null
                ? null
                : productRepository.findById(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("Ürün", productId));

        IslemGecmisi log = IslemGecmisi.builder()
                .user(user)
                .product(product)
                .islemTipi(islemTipi)
                .urunAdi(urunAdi)
                .miktar(miktar)
                .aciklama(aciklama)
                .recipientName(recipientName)
                .batchId(batchId)
                .build();

        islemGecmisiRepository.save(log);
    }

    /**
     * SecurityContext'ten o anki giriş yapmış kullanıcıyı döner.
     * Kullanıcı yoksa (anonim istek) null döner.
     */
    private User getAktifKullanici() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        String username = auth.getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    private IslemGecmisiResponse mapToResponse(IslemGecmisi islem) {
        return IslemGecmisiResponse.builder()
                .id(islem.getId())
                .kullaniciAdi(islem.getUser() != null ? islem.getUser().getUsername() : null)
                .kullaniciFullName(islem.getUser() != null ? islem.getUser().getFullName() : null)
                .islemTipi(islem.getIslemTipi())
                .urunAdi(islem.getUrunAdi())
                .miktar(islem.getMiktar())
                .aciklama(islem.getAciklama())
                .recipientName(islem.getRecipientName())
                .tarihSaat(islem.getTarihSaat())
                .build();
    }

    /**
     * Tekil (batchId = null) bir IslemGecmisi'ni IslemSummaryResponse'a dönüştürür.
     */
    private IslemSummaryResponse mapToSummary(IslemGecmisi islem) {
        return IslemSummaryResponse.builder()
                .id(islem.getId())
                .batchId(null)
                .isBatch(false)
                .isCancelled(islem.isCancelled())
                .kullaniciAdi(islem.getUser() != null ? islem.getUser().getUsername() : null)
                .kullaniciFullName(islem.getUser() != null ? islem.getUser().getFullName() : null)
                .islemTipi(islem.getIslemTipi())
                .urunAdi(islem.getUrunAdi())
                .miktar(islem.getMiktar())
                .aciklama(islem.getAciklama())
                .recipientName(islem.getRecipientName())
                .toplamUrun(0)
                .tarihSaat(islem.getTarihSaat())
                .build();
    }

    /**
     * Repository'den dönen JPQL Object[] satırını IslemSummaryResponse'a
     * dönüştürür.
     * Sütun sırası: [0]=batchId, [1]=MAX(islemTipi), [2]=MIN(tarihSaat), [3]=COUNT,
     * [4]=user, [5]=MAX(recipientName), [6]=MAX(aciklama), [7]=MIN(islemTipi),
     * [8]=isCancelled
     * Eğer max != min ise batch KARMA_ISLEM olarak işaretlenir.
     */
    private IslemSummaryResponse mapBatchRowToSummary(Object[] row) {
        String batchId = (String) row[0];
        IslemTipi maxIslemTipi = (IslemTipi) row[1];
        LocalDateTime tarih = (LocalDateTime) row[2];
        long count = (Long) row[3];
        User user = (User) row[4];
        String recipientName = (String) row[5];
        String aciklama = (String) row[6];
        IslemTipi minIslemTipi = (IslemTipi) row[7];
        boolean isCancelled = ((Number) row[8]).intValue() > 0;

        // Mixed batch: contains both STOK_GIRIS and STOK_CIKIS entries
        IslemTipi derivedTipi = (maxIslemTipi != minIslemTipi)
                ? IslemTipi.KARMA_ISLEM
                : maxIslemTipi;

        return IslemSummaryResponse.builder()
                .id(null)
                .batchId(batchId)
                .isBatch(true)
                .isCancelled(isCancelled)
                .kullaniciAdi(user != null ? user.getUsername() : null)
                .kullaniciFullName(user != null ? user.getFullName() : null)
                .islemTipi(derivedTipi)
                .urunAdi(null)
                .miktar(null)
                .aciklama(aciklama)
                .recipientName(recipientName)
                .toplamUrun((int) count)
                .tarihSaat(tarih)
                .build();
    }
}

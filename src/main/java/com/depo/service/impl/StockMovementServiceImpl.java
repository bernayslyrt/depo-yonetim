package com.depo.service.impl;

import com.depo.dto.StockMovementRequest;
import com.depo.dto.StockMovementResponse;
import com.depo.entity.Product;
import com.depo.entity.StockMovement;
import com.depo.entity.User;
import com.depo.enums.IslemTipi;
import com.depo.enums.MovementType;
import com.depo.exception.AlreadyCancelledException;
import com.depo.exception.InsufficientStockException;
import com.depo.exception.ResourceNotFoundException;
import com.depo.repository.ProductRepository;
import com.depo.repository.StockMovementRepository;
import com.depo.repository.UserRepository;
import com.depo.service.IslemGecmisiService;
import com.depo.service.StockMovementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockMovementServiceImpl implements StockMovementService {

    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final IslemGecmisiService islemGecmisiService;

    @Override
    public List<StockMovementResponse> getAllMovements() {
        return stockMovementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<StockMovementResponse> getMovementsByProductId(Long productId) {
        // Ürünün var olduğunu doğrula
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Ürün", productId);
        }
        return stockMovementRepository.findByProductIdOrderByCreatedAtDesc(productId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public StockMovementResponse createMovement(StockMovementRequest request) {
        return createMovementInternal(request, null);
    }

    @Override
    @Transactional
    public List<StockMovementResponse> createMovements(List<StockMovementRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Toplu stok hareketi listesi boş olamaz.");
        }

        // One batch ID covers the entire request, even if it contains mixed IN + OUT movements.
        String batchId = UUID.randomUUID().toString();
        return requests.stream()
                .map(request -> createMovementInternal(request, batchId))
                .toList();
    }

    private StockMovementResponse createMovementInternal(StockMovementRequest request, String batchId) {
        // Ürünü bul
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Ürün", request.getProductId()));

        // Stok güncelle
        if (request.getMovementType() == MovementType.IN) {
            // GİRİŞ: Stok miktarını artır
            product.setQuantity(product.getQuantity() + request.getQuantity());
        } else if (request.getMovementType() == MovementType.OUT) {
            // ÇIKIŞ: Stok yeterliliğini kontrol et
            if (product.getQuantity() < request.getQuantity()) {
                throw new InsufficientStockException(product.getQuantity(), request.getQuantity());
            }
            // Stok miktarını düşür
            product.setQuantity(product.getQuantity() - request.getQuantity());
        }

        productRepository.save(product);

        // Hareketi oluştur
        StockMovement movement = StockMovement.builder()
                .product(product)
                .movementType(request.getMovementType())
                .quantity(request.getQuantity())
                .recipientName(request.getRecipientName())
                .description(request.getDescription())
                .build();

        // Hareketi yapan kullanıcıyı JWT SecurityContext'ten al
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            userRepository.findByUsername(auth.getName()).ifPresent(movement::setCreatedBy);
        }

        StockMovement saved = stockMovementRepository.save(movement);

        // İşlem geçmişine log ekle
        IslemTipi islemTipi = (request.getMovementType() == MovementType.IN)
                ? IslemTipi.STOK_GIRIS : IslemTipi.STOK_CIKIS;
        if (batchId == null) {
            islemGecmisiService.logEkle(
                    islemTipi,
                    product.getName(),
                    request.getQuantity(),
                    request.getDescription(),
                    request.getRecipientName()
            );
        } else {
            islemGecmisiService.logEkle(
                    islemTipi,
                    product.getName(),
                    request.getQuantity(),
                    request.getDescription(),
                    request.getRecipientName(),
                    batchId,
                    product.getId()
            );
        }

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public StockMovementResponse cancelStockMovement(Long movementId) {
        // 1. Hareketi bul, yoksa hata fırlat
        StockMovement movement = stockMovementRepository.findById(movementId)
                .orElseThrow(() -> new ResourceNotFoundException("Stok Hareketi", movementId));

        // 2. Zaten iptal edilmişse hata fırlat
        if (Boolean.TRUE.equals(movement.getIsCancelled())) {
            throw new AlreadyCancelledException(movementId);
        }

        // 3. Ürünü bul ve stoğu matematiksel olarak geri al
        Product product = movement.getProduct();

        if (movement.getMovementType() == MovementType.IN) {
            // Giriş hareketi iptal: miktarı geri düş
            int newQty = product.getQuantity() - movement.getQuantity();
            if (newQty < 0) {
                throw new InsufficientStockException(
                        "İptal işlemi stoku negatife düşürür. Mevcut: " + product.getQuantity() +
                        ", İptal edilecek: " + movement.getQuantity());
            }
            product.setQuantity(newQty);
        } else if (movement.getMovementType() == MovementType.OUT) {
            // Çıkış hareketi iptal: miktarı geri ekle
            product.setQuantity(product.getQuantity() + movement.getQuantity());
        }

        productRepository.save(product);

        // 4. Hareketi iptal olarak işaretle
        movement.setIsCancelled(true);
        StockMovement saved = stockMovementRepository.save(movement);
        return mapToResponse(saved);
    }

    private StockMovementResponse mapToResponse(StockMovement movement) {
        return StockMovementResponse.builder()
                .id(movement.getId())
                .productId(movement.getProduct().getId())
                .productName(movement.getProduct().getName())
                .productCode(movement.getProduct().getCode())
                .movementType(movement.getMovementType())
                .quantity(movement.getQuantity())
                .recipientName(movement.getRecipientName())
                .description(movement.getDescription())
                .createdById(movement.getCreatedBy() != null ? movement.getCreatedBy().getId() : null)
                .createdByFullName(movement.getCreatedBy() != null ? movement.getCreatedBy().getFullName() : null)
                .isCancelled(movement.getIsCancelled())
                .createdAt(movement.getCreatedAt())
                .build();
    }
}

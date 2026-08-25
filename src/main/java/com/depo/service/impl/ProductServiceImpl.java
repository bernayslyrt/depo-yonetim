package com.depo.service.impl;

import com.depo.dto.*;
import com.depo.entity.Category;
import com.depo.entity.Product;
import com.depo.exception.DuplicateResourceException;
import com.depo.exception.ResourceNotFoundException;
import com.depo.repository.CategoryRepository;
import com.depo.repository.ProductRepository;
import com.depo.service.ProductService;
import com.depo.service.ProductNameMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductNameMatchService productNameMatchService;

    @Override
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ürün", id));
        return mapToResponse(product);
    }

    @Override
    public List<ProductResponse> searchProducts(String query) {
        return productRepository.searchProducts(query).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProductResponse> getCriticalStockProducts() {
        return productRepository.findCriticalStockProducts().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ProductResponse createProduct(CreateProductRequest request) {
        return productNameMatchService.withNameMatchLock(() -> createProductLocked(request));
    }

    private ProductResponse createProductLocked(CreateProductRequest request) {
        // Boş string code'u null olarak işle (MySQL UNIQUE kısıtlaması için)
        String code = normalizeCode(request.getCode());

        // Eğer code girildiyse ve sistemde zaten varsa hata fırlat
        if (code != null && productRepository.existsByCode(code)) {
            throw new DuplicateResourceException("Bu stok kodu zaten kullanılıyor: " + code);
        }
        if (!productNameMatchService.findMatches(request.getName()).isEmpty()) {
            throw new DuplicateResourceException("Bu ürün adı zaten kullanılıyor: " + request.getName().trim());
        }

        Product product = Product.builder()
                .code(code)
                .name(request.getName())
                .quantity(request.getQuantity())
                .unit(request.getUnit() != null ? request.getUnit() : "Adet")
                .minStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : 5)
                .shelfLocation(request.getShelfLocation())
                .source(request.getSource())
                .build();

        // Kategori varsa ata
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Kategori", request.getCategoryId()));
            product.setCategory(category);
        }

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ürün", id));

        // Boş string code'u null olarak işle
        String code = normalizeCode(request.getCode());

        // Code değiştiyse ve yeni code zaten başka bir üründe kullanılıyorsa hata fırlat
        if (code != null) {
            productRepository.findByCode(code).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw new DuplicateResourceException("Bu stok kodu zaten kullanılıyor: " + code);
                }
            });
        }

        product.setCode(code);
        product.setName(request.getName());
        product.setQuantity(request.getQuantity());
        product.setUnit(request.getUnit() != null ? request.getUnit() : product.getUnit());
        product.setMinStockLevel(request.getMinStockLevel() != null ? request.getMinStockLevel() : product.getMinStockLevel());
        product.setShelfLocation(request.getShelfLocation());
        product.setSource(request.getSource());

        // Kategori güncelle
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Kategori", request.getCategoryId()));
            product.setCategory(category);
        } else {
            product.setCategory(null);
        }

        Product saved = productRepository.save(product);
        return mapToResponse(saved);
    }

    @Override
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Ürün", id);
        }
        productRepository.deleteById(id);
    }

    /**
     * Boş string ("") veya sadece boşluk içeren code değerleri gelirse otomatik bir stok kodu (PRD-...) üretir.
     */
    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "PRD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        return code.trim();
    }

    private ProductResponse mapToResponse(Product product) {
        CategoryResponse categoryResponse = null;
        if (product.getCategory() != null) {
            categoryResponse = CategoryResponse.builder()
                    .id(product.getCategory().getId())
                    .name(product.getCategory().getName())
                    .description(product.getCategory().getDescription())
                    .build();
        }

        return ProductResponse.builder()
                .id(product.getId())
                .code(product.getCode())
                .name(product.getName())
                .category(categoryResponse)
                .quantity(product.getQuantity())
                .unit(product.getUnit())
                .minStockLevel(product.getMinStockLevel())
                .shelfLocation(product.getShelfLocation())
                .source(product.getSource())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}

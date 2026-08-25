package com.depo.service;

import com.depo.dto.CreateProductRequest;
import com.depo.dto.ProductResponse;
import com.depo.dto.UpdateProductRequest;

import java.util.List;

public interface ProductService {

    List<ProductResponse> getAllProducts();

    ProductResponse getProductById(Long id);

    List<ProductResponse> searchProducts(String query);

    List<ProductResponse> getCriticalStockProducts();

    ProductResponse createProduct(CreateProductRequest request);

    ProductResponse updateProduct(Long id, UpdateProductRequest request);

    void deleteProduct(Long id);
}

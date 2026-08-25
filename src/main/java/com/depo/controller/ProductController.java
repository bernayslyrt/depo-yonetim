package com.depo.controller;

import com.depo.dto.*;
import com.depo.service.ProductService;
import com.depo.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ReportService reportService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getAllProducts() {
        List<ProductResponse> products = productService.getAllProducts();
        return ResponseEntity.ok(ApiResponse.success(products, "Ürünler başarıyla listelendi."));
    }

    @GetMapping("/export/excel")
    public ResponseEntity<byte[]> exportProductsToExcel() throws IOException {
        ByteArrayInputStream stream = reportService.exportProductsToExcel();
        byte[] bytes = stream.readAllBytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"guncel_stok_listesi.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportProductsToPdf() throws IOException {
        ByteArrayInputStream stream = reportService.exportProductsToPdf();
        byte[] bytes = stream.readAllBytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"guncel_stok_listesi.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProductById(@PathVariable Long id) {
        ProductResponse product = productService.getProductById(id);
        return ResponseEntity.ok(ApiResponse.success(product, "Ürün başarıyla getirildi."));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> searchProducts(
            @RequestParam String query) {
        List<ProductResponse> products = productService.searchProducts(query);
        return ResponseEntity.ok(ApiResponse.success(products, "Arama sonuçları listelendi."));
    }

    @GetMapping("/critical-stock")
    public ResponseEntity<ApiResponse<List<ProductResponse>>> getCriticalStockProducts() {
        List<ProductResponse> products = productService.getCriticalStockProducts();
        return ResponseEntity.ok(ApiResponse.success(products, "Kritik stok seviyesindeki ürünler listelendi."));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        ProductResponse created = productService.createProduct(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Ürün başarıyla oluşturuldu."));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductResponse updated = productService.updateProduct(id, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Ürün başarıyla güncellendi."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Ürün başarıyla silindi."));
    }
}

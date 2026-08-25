package com.depo.controller;

import com.depo.dto.ApiResponse;
import com.depo.dto.BulkStockMovementRequest;
import com.depo.dto.StockMovementRequest;
import com.depo.dto.StockMovementResponse;
import com.depo.service.IslemGecmisiService;
import com.depo.service.StockMovementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/movements")
@RequiredArgsConstructor
public class StockMovementController {

    private final StockMovementService stockMovementService;
    private final IslemGecmisiService islemGecmisiService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<StockMovementResponse>>> getAllMovements() {
        List<StockMovementResponse> movements = stockMovementService.getAllMovements();
        return ResponseEntity.ok(ApiResponse.success(movements, "Stok hareketleri başarıyla listelendi."));
    }

    @GetMapping("/product/{productId}")
    public ResponseEntity<ApiResponse<List<StockMovementResponse>>> getMovementsByProductId(
            @PathVariable Long productId) {
        List<StockMovementResponse> movements = stockMovementService.getMovementsByProductId(productId);
        return ResponseEntity.ok(ApiResponse.success(movements, "Ürüne ait stok hareketleri listelendi."));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StockMovementResponse>> createMovement(
            @Valid @RequestBody StockMovementRequest request) {
        StockMovementResponse created = stockMovementService.createMovement(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Stok hareketi başarıyla kaydedildi."));
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<List<StockMovementResponse>>> createMovements(
            @Valid @RequestBody BulkStockMovementRequest request) {
        List<StockMovementResponse> created = stockMovementService.createMovements(request.getItems());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Toplu stok hareketi başarıyla kaydedildi."));
    }

    @PostMapping("/rollback/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Integer>> rollbackBatch(@PathVariable String batchId) {
        int rollbackCount = islemGecmisiService.rollbackBatch(batchId);
        return ResponseEntity.ok(ApiResponse.success(
                rollbackCount,
                "Toplu işlem başarıyla geri alındı."));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<StockMovementResponse>> cancelMovement(@PathVariable Long id) {
        StockMovementResponse cancelled = stockMovementService.cancelStockMovement(id);
        return ResponseEntity.ok(ApiResponse.success(cancelled, "Stok hareketi başarıyla iptal edildi."));
    }
}

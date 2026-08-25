package com.depo.service;

import com.depo.dto.StockMovementRequest;
import com.depo.dto.StockMovementResponse;

import java.util.List;

public interface StockMovementService {

    List<StockMovementResponse> getAllMovements();

    List<StockMovementResponse> getMovementsByProductId(Long productId);

    StockMovementResponse createMovement(StockMovementRequest request);

    List<StockMovementResponse> createMovements(List<StockMovementRequest> requests);

    StockMovementResponse cancelStockMovement(Long movementId);
}

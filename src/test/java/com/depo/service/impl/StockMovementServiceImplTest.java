package com.depo.service.impl;

import com.depo.dto.StockMovementRequest;
import com.depo.entity.Product;
import com.depo.entity.StockMovement;
import com.depo.enums.IslemTipi;
import com.depo.enums.MovementType;
import com.depo.repository.ProductRepository;
import com.depo.repository.StockMovementRepository;
import com.depo.repository.UserRepository;
import com.depo.service.IslemGecmisiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IslemGecmisiService islemGecmisiService;

    @InjectMocks
    private StockMovementServiceImpl stockMovementService;

    @Test
    void usesOneBatchIdForEveryEntryInTheRequest() {
        Product first = product(1L, "Ürün 1", 10);
        Product second = product(2L, "Ürün 2", 20);
        when(productRepository.findById(1L)).thenReturn(Optional.of(first));
        when(productRepository.findById(2L)).thenReturn(Optional.of(second));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stockMovementService.createMovements(List.of(
                movement(1L, MovementType.IN, 3),
                movement(2L, MovementType.IN, 4)
        ));

        ArgumentCaptor<String> batchIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(islemGecmisiService, times(2)).logEkle(
                eq(IslemTipi.STOK_GIRIS),
                anyString(),
                anyInt(),
                any(),
                any(),
                batchIdCaptor.capture(),
                any()
        );

        List<String> batchIds = batchIdCaptor.getAllValues();
        assertEquals(batchIds.get(0), batchIds.get(1));
        UUID.fromString(batchIds.get(0));
        assertEquals(13, first.getQuantity());
        assertEquals(24, second.getQuantity());
    }

    @Test
    void logsBulkExitsWithTheExitOperationType() {
        Product first = product(1L, "Ürün 1", 10);
        Product second = product(2L, "Ürün 2", 20);
        when(productRepository.findById(1L)).thenReturn(Optional.of(first));
        when(productRepository.findById(2L)).thenReturn(Optional.of(second));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stockMovementService.createMovements(List.of(
                movement(1L, MovementType.OUT, 3),
                movement(2L, MovementType.OUT, 4)
        ));

        verify(islemGecmisiService, times(2)).logEkle(
                eq(IslemTipi.STOK_CIKIS),
                anyString(),
                anyInt(),
                any(),
                any(),
                anyString(),
                any()
        );
        assertEquals(7, first.getQuantity());
        assertEquals(16, second.getQuantity());
    }

    @Test
    void supportsMixedMovementTypesInOneBatch() {
        Product first = product(1L, "Ürün 1", 10);
        Product second = product(2L, "Ürün 2", 20);
        when(productRepository.findById(1L)).thenReturn(Optional.of(first));
        when(productRepository.findById(2L)).thenReturn(Optional.of(second));
        when(stockMovementRepository.save(any(StockMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        stockMovementService.createMovements(List.of(
                movement(1L, MovementType.IN, 2),
                movement(2L, MovementType.OUT, 3)
        ));

        assertEquals(12, first.getQuantity());
        assertEquals(17, second.getQuantity());
    }

    private Product product(Long id, String name, int quantity) {
        return Product.builder()
                .id(id)
                .code("P-" + id)
                .name(name)
                .quantity(quantity)
                .build();
    }

    private StockMovementRequest movement(Long productId, MovementType type, int quantity) {
        return StockMovementRequest.builder()
                .productId(productId)
                .movementType(type)
                .quantity(quantity)
                .description("Toplu stok hareketi")
                .build();
    }
}

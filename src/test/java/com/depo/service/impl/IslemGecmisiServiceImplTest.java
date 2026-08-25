package com.depo.service.impl;

import com.depo.dto.IslemSummaryResponse;
import com.depo.entity.IslemGecmisi;
import com.depo.entity.Product;
import com.depo.entity.User;
import com.depo.enums.IslemTipi;
import com.depo.exception.BadRequestException;
import com.depo.repository.IslemGecmisiRepository;
import com.depo.repository.ProductRepository;
import com.depo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IslemGecmisiServiceImplTest {

    @Mock
    private IslemGecmisiRepository islemGecmisiRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private IslemGecmisiServiceImpl service;

    @Test
    void mapsRecipientAndDescriptionFromBatchSummaryQuery() throws Exception {
        User user = User.builder()
                .username("personel")
                .fullName("Depo Personeli")
                .build();
        LocalDateTime date = LocalDateTime.of(2026, 8, 13, 12, 0);
        Object[] queryRow = {
                "3cf62c82-88eb-4aba-8d7f-f8d133690125",
                IslemTipi.STOK_CIKIS,
                date,
                3L,
                user,
                "Ahmet Yılmaz",
                "Atölye teslimatı",
                IslemTipi.STOK_CIKIS,
                1
        };

        Method mapper = IslemGecmisiServiceImpl.class
                .getDeclaredMethod("mapBatchRowToSummary", Object[].class);
        mapper.setAccessible(true);
        IslemSummaryResponse summary =
                (IslemSummaryResponse) mapper.invoke(service, (Object) queryRow);

        assertEquals("Ahmet Yılmaz", summary.getRecipientName());
        assertEquals("Atölye teslimatı", summary.getAciklama());
        assertEquals(3, summary.getToplamUrun());
        assertTrue(summary.isCancelled());
    }

    @Test
    void rollsBackBatchInReverseOrderAndMarksEveryAuditRecordCancelled() {
        String batchId = "3cf62c82-88eb-4aba-8d7f-f8d133690125";
        Product product = Product.builder()
                .id(1L)
                .name("Test Ürünü")
                .quantity(12)
                .build();
        IslemGecmisi exit = history(2L, batchId, IslemTipi.STOK_CIKIS, 3);
        IslemGecmisi entry = history(1L, batchId, IslemTipi.STOK_GIRIS, 5);

        when(islemGecmisiRepository.findByBatchIdForRollback(batchId))
                .thenReturn(List.of(exit, entry));
        when(productRepository.findAllByNameForUpdate("Test Ürünü"))
                .thenReturn(List.of(product));

        int rollbackCount = service.rollbackBatch(batchId);

        assertEquals(2, rollbackCount);
        assertEquals(10, product.getQuantity());
        assertTrue(exit.isCancelled());
        assertTrue(entry.isCancelled());
        verify(productRepository).saveAll(any());
        verify(islemGecmisiRepository).saveAll(List.of(exit, entry));
    }

    @Test
    void rejectsAnAlreadyCancelledBatchWithoutChangingStock() {
        String batchId = "3cf62c82-88eb-4aba-8d7f-f8d133690125";
        IslemGecmisi cancelled = history(1L, batchId, IslemTipi.STOK_GIRIS, 5);
        cancelled.setCancelled(true);
        when(islemGecmisiRepository.findByBatchIdForRollback(batchId))
                .thenReturn(List.of(cancelled));

        assertThrows(BadRequestException.class, () -> service.rollbackBatch(batchId));

        verify(productRepository, never()).findAllByNameForUpdate("Test Ürünü");
        verify(islemGecmisiRepository, never()).saveAll(anyList());
    }

    private IslemGecmisi history(Long id, String batchId, IslemTipi type, int amount) {
        return IslemGecmisi.builder()
                .id(id)
                .batchId(batchId)
                .islemTipi(type)
                .urunAdi("Test Ürünü")
                .miktar(amount)
                .build();
    }
}

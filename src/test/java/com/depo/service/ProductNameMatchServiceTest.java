package com.depo.service;

import com.depo.entity.Category;
import com.depo.entity.Product;
import com.depo.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductNameMatchServiceTest {

    @Test
    void formattingOnlyDuplicateGroupIsSafeAndUsesLowestIdAsTarget() {
        ProductRepository repository = mock(ProductRepository.class);
        Product first = Product.builder().id(8L).name("Pil_Yuvarlak").quantity(51).build();
        Product second = Product.builder().id(3L).name("PİL YUVARLAK").quantity(20).build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        ProductNameMatchService.MatchResolution resolution =
                new ProductNameMatchService(repository).resolve("Pil-Yuvarlak");

        assertTrue(resolution.safe());
        assertEquals(second, resolution.target());
        assertEquals(71, resolution.totalStock());
    }

    @Test
    void categoryDifferenceMakesDuplicateGroupUnsafe() {
        ProductRepository repository = mock(ProductRepository.class);
        Product first = Product.builder().id(1L).name("Pil Yuvarlak").quantity(1)
                .category(Category.builder().id(1L).name("A").build()).build();
        Product second = Product.builder().id(2L).name("Pil_Yuvarlak").quantity(1)
                .category(Category.builder().id(2L).name("B").build()).build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        ProductNameMatchService.MatchResolution resolution =
                new ProductNameMatchService(repository).resolve("Pil Yuvarlak");

        assertFalse(resolution.safe());
        assertEquals(null, resolution.target());
        assertEquals("Kategori farklı: A / B", resolution.reviewReason());
        assertEquals(List.of("Kategori"), resolution.conflicts().stream()
                .map(ProductNameMatchService.ConflictDetail::field).toList());
    }

    @Test
    void sourceDifferenceNamesOnlyTheSourceConflict() {
        ProductRepository repository = mock(ProductRepository.class);
        Product first = Product.builder().id(1L).name("Beher_250 ml").quantity(100)
                .source("Belediye").build();
        Product second = Product.builder().id(2L).name("Beher 250 ml").quantity(236)
                .source("T3").build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        ProductNameMatchService.MatchResolution resolution =
                new ProductNameMatchService(repository).resolve("Beher-250 ml");

        assertFalse(resolution.safe());
        assertEquals("Kaynak farklı: Belediye / T3", resolution.reviewReason());
        assertEquals(List.of("Kaynak"), resolution.conflicts().stream()
                .map(ProductNameMatchService.ConflictDetail::field).toList());
    }

    @Test
    void unitDifferenceNamesOnlyTheUnitConflict() {
        ProductRepository repository = mock(ProductRepository.class);
        Product first = Product.builder().id(1L).name("Kalem_Set").quantity(1).unit("Adet").build();
        Product second = Product.builder().id(2L).name("Kalem Set").quantity(1).unit("Paket").build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        ProductNameMatchService.MatchResolution resolution =
                new ProductNameMatchService(repository).resolve("Kalem Set");

        assertFalse(resolution.safe());
        assertEquals("Birim farklı: Adet / Paket", resolution.reviewReason());
    }

    @Test
    void categoryAndShelfDifferencesAreBothNamed() {
        ProductRepository repository = mock(ProductRepository.class);
        Product first = Product.builder().id(1L).name("Kutu_A").quantity(1).shelfLocation("A1")
                .category(Category.builder().id(1L).name("Cam").build()).build();
        Product second = Product.builder().id(2L).name("Kutu A").quantity(1).shelfLocation("B2")
                .category(Category.builder().id(2L).name("Metal").build()).build();
        when(repository.findAll()).thenReturn(List.of(first, second));

        ProductNameMatchService.MatchResolution resolution =
                new ProductNameMatchService(repository).resolve("Kutu A");

        assertFalse(resolution.safe());
        assertEquals("Kategori ve Raf konumu farklı.", resolution.reviewReason());
        assertEquals(List.of("Kategori", "Raf konumu"), resolution.conflicts().stream()
                .map(ProductNameMatchService.ConflictDetail::field).toList());
    }

    @Test
    void sourceFilteringKeepsCanonicalFormattingVariantsAndReturnsOnlySelectedSource() {
        ProductRepository repository = mock(ProductRepository.class);
        Product municipality = Product.builder().id(1L).name("Silikon_Tabancası")
                .source("Belediye").quantity(15).build();
        Product t3 = Product.builder().id(2L).name("Silikon Tabancası")
                .source("T3").quantity(10).build();
        Product secondMunicipality = Product.builder().id(3L).name("Silikon-Tabancası")
                .source("Belediye").quantity(20).build();
        when(repository.findAll()).thenReturn(List.of(municipality, t3, secondMunicipality));

        ProductNameMatchService service = new ProductNameMatchService(repository);
        ProductNameMatchService.SourceMatchResolution resolution =
                service.resolveForSource("SİLİKON-TABANCASI", "T3");

        assertEquals(3, resolution.products().size());
        assertEquals(List.of(t3), resolution.selectedSourceProducts());
        assertEquals(2, service.resolveForSource("Silikon Tabancası", "Belediye")
                .selectedSourceProducts().size());
        assertEquals(0, service.resolveForSource("Silikon Tabancası", "Tubitak")
                .selectedSourceProducts().size());
    }

    @Test
    void oneMatchingOperationLoadsProductsOnceForManyImportedRows() {
        ProductRepository repository = mock(ProductRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                Product.builder().id(1L).name("Ürün 1").source("T3").quantity(1).build(),
                Product.builder().id(2L).name("Ürün 2").source("T3").quantity(2).build()));
        ProductNameMatchService service = new ProductNameMatchService(repository);

        service.withMatchSnapshot(() -> {
            service.resolveForSource("Ürün 1", "T3");
            service.resolveForSource("Ürün 2", "T3");
            service.resolveForSource("Yeni Ürün", "T3");
            return null;
        });

        verify(repository, times(1)).findAll();
    }
}

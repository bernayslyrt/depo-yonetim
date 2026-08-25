package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.entity.Product;
import com.depo.entity.Category;
import com.depo.enums.IslemTipi;
import com.depo.repository.ProductRepository;
import com.depo.repository.UserRepository;
import com.depo.service.IslemGecmisiService;
import com.depo.service.ProductNameMatchService;
import com.depo.service.ProductNameNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class BulkProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private IslemGecmisiService islemGecmisiService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProductNameMatchService productNameMatchService;

    @InjectMocks
    private BulkProductService bulkProductService;

    @BeforeEach
    void executeNameLockedOperationsInTest() {
        lenient().when(productNameMatchService.withNameMatchLock(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(productNameMatchService.withMatchSnapshot(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(productNameMatchService.resolveForSource(anyString(), anyString())).thenReturn(
                sourceResolution(List.of(), List.of(), safeGroup(List.of())));
    }

    @Test
    void assignsSelectedSourceToEveryNewProduct() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(1)
                .productCode("T3-001")
                .productName("Deneme Ürünü")
                .quantity(12)
                .isValid(true)
                .build();

        BulkProductService.BulkImportResult result =
                bulkProductService.confirmBulkImport(List.of(item), "T3");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        verify(islemGecmisiService).logEkle(
                eq(IslemTipi.PDF_YUKLEME),
                anyString(),
                anyInt(),
                anyString(),
                any(),
                anyString(),
                any()
        );

        assertEquals("T3", productCaptor.getValue().getSource());
        assertEquals(1, result.createdCount());
        assertEquals(0, result.updatedCount());
    }

    @Test
    void rejectsUnsupportedSourceBeforeWritingProducts() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(), "Bilinmeyen")
        );

        assertEquals("Geçersiz ürün kaynağı: Bilinmeyen", exception.getMessage());
    }

    @Test
    void rejectsForgedValidFlagWhenQuantityIsNotPositive() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(7)
                .productCode("BAD-1")
                .productName("Geçersiz Ürün")
                .quantity(0)
                .isValid(true)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(item), "T3"));

        assertEquals("Satır 7: Miktar 0'dan büyük olmalıdır (alınan: 0).", exception.getMessage());
    }

    @Test
    void rejectsInvalidPreviewRowInsteadOfSilentlySkippingIt() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(8)
                .productCode("BAD-2")
                .productName("Düzeltilmemiş Ürün")
                .quantity(3)
                .isValid(false)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(item), "T3"));

        assertEquals("Satır 8: geçersiz ön izleme satırı onaylanamaz.", exception.getMessage());
    }

    @Test
    void rejectsOversizedAiNameBeforeRepositoryInsertion() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(9)
                .productCode(null)
                .productName("Teknik açıklama ".repeat(30))
                .quantity(3)
                .isValid(true)
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(item), "T3"));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("en fazla 255 karakter"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void missingProductCodeRemainsNullInBulkImport() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(10)
                .productCode("   ")
                .productName("Spatula Seti")
                .quantity(4)
                .isValid(true)
                .build();

        bulkProductService.confirmBulkImport(List.of(item), "T3");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, never()).findByCode(any());
        verify(productRepository).save(productCaptor.capture());
        assertEquals(null, productCaptor.getValue().getCode());
    }

    @Test
    void trimsValidatedNameBeforeDatabaseInsertion() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(11)
                .productName("  Spatula Seti  ")
                .quantity(4)
                .isValid(true)
                .build();

        bulkProductService.confirmBulkImport(List.of(item), "T3");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertEquals("Spatula Seti", productCaptor.getValue().getName());
    }

    @Test
    void canonicalNameMatchIncrementsExistingProductAndConsolidatesImportRows() {
        Product existing = Product.builder().id(1L).name("Pil_Yuvarlak").source("T3").quantity(51).build();
        ProductPreviewDto first = ProductPreviewDto.builder()
                .rowNumber(1).productName("Pil Yuvarlak").quantity(10).isValid(true).build();
        ProductPreviewDto second = ProductPreviewDto.builder()
                .rowNumber(2).productName("PİL-YUVARLAK").quantity(15).isValid(true).build();

        when(productNameMatchService.normalize("Pil Yuvarlak")).thenReturn("pil yuvarlak");
        when(productNameMatchService.normalize("PİL-YUVARLAK")).thenReturn("pil yuvarlak");
        when(productNameMatchService.resolveForSource("Pil Yuvarlak", "T3")).thenReturn(
                sourceResolution(List.of(existing), List.of(existing), safeGroup(List.of(existing))));

        BulkProductService.BulkImportResult result =
                bulkProductService.confirmBulkImport(List.of(first, second), "T3");

        assertEquals(76, existing.getQuantity());
        assertEquals(0, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(productRepository).save(existing);
    }

    @Test
    void singleSourceRowQuantityIsNotDoubledWhenParserRepeatsItsDto() {
        ProductPreviewDto first = importRow(1, "Silikon Tabancası", 10,
                "xlsx:Ürünler:row:2");
        ProductPreviewDto repeated = importRow(2, "Silikon Tabancası", 10,
                "xlsx:Ürünler:row:2");
        when(productNameMatchService.normalize(anyString())).thenReturn("silikon tabancası");

        List<ProductPreviewDto> preview = bulkProductService.preparePreview(List.of(first, repeated));

        assertEquals(1, preview.size());
        assertEquals(10, preview.get(0).getQuantity());
        assertEquals(List.of("xlsx:Ürünler:row:2"),
                preview.get(0).getContributingSourceRecordIds());
    }

    @Test
    void sourceReevaluationAndRepeatedSourceChangesKeepSingleRowQuantityStable() {
        ProductPreviewDto parsed = importRow(1, "Silikon Tabancası", 10,
                "xlsx:Ürünler:row:2");
        when(productNameMatchService.normalize(anyString())).thenReturn("silikon tabancası");

        ProductPreviewDto t3 = bulkProductService.preparePreview(List.of(parsed), "T3").get(0);
        ProductPreviewDto belediye = bulkProductService.preparePreview(List.of(t3), "Belediye").get(0);
        ProductPreviewDto tubitak = bulkProductService.preparePreview(List.of(belediye), "Tubitak").get(0);
        ProductPreviewDto t3Again = bulkProductService.preparePreview(List.of(tubitak), "T3").get(0);

        assertEquals(10, t3.getQuantity());
        assertEquals(10, belediye.getQuantity());
        assertEquals(10, tubitak.getQuantity());
        assertEquals(10, t3Again.getQuantity());
        assertEquals(10, t3Again.getImportedQuantity());
        assertEquals("silikon tabancası", t3Again.getCanonicalName());
        assertEquals(List.of("xlsx:Ürünler:row:2"),
                t3Again.getContributingSourceRecordIds());
    }

    @Test
    void genuineSameImportRowsConsolidateToExactSumAcrossRematching() {
        ProductPreviewDto first = importRow(1, "Pil Yuvarlak", 10,
                "xlsx:Ürünler:row:2");
        ProductPreviewDto second = importRow(2, "PİL_YUVARLAK", 5,
                "xlsx:Ürünler:row:3");
        when(productNameMatchService.normalize(anyString())).thenReturn("pil yuvarlak");

        ProductPreviewDto consolidated = bulkProductService.preparePreview(
                List.of(first, second), "T3").get(0);
        ProductPreviewDto rematched = bulkProductService.preparePreview(
                List.of(consolidated), "Belediye").get(0);

        assertEquals(15, consolidated.getQuantity());
        assertEquals(15, rematched.getQuantity());
        assertEquals(List.of("xlsx:Ürünler:row:2", "xlsx:Ürünler:row:3"),
                rematched.getContributingSourceRecordIds());
    }

    @Test
    void distinctAuthoritativeTuvalNamesCannotCollapseThroughSharedAiDescription() {
        when(productNameMatchService.normalize(anyString())).thenAnswer(invocation ->
                ProductNameNormalizer.normalize(invocation.getArgument(0)));
        ProductPreviewDto first = authoritativeImportRow(
                1, "Beyaz", "Tuval_1", 400, "xlsx:EK-1:row:10");
        ProductPreviewDto second = authoritativeImportRow(
                2, "Beyaz", "Tuval_2", 450, "xlsx:EK-1:row:11");
        ProductPreviewDto third = authoritativeImportRow(
                3, "Beyaz", "Tuval_3", 500, "xlsx:EK-1:row:12");

        List<ProductPreviewDto> preview = bulkProductService.preparePreview(
                List.of(first, second, third));

        assertEquals(3, preview.size());
        assertPreviewQuantity(preview, "Tuval_1", 400);
        assertPreviewQuantity(preview, "Tuval_2", 450);
        assertPreviewQuantity(preview, "Tuval_3", 500);
        assertEquals(3, preview.stream()
                .flatMap(item -> item.getContributingSourceRecordIds().stream())
                .distinct().count());
    }

    @Test
    void sameAuthoritativeProductFormattingVariantsStillConsolidateExactOnce() {
        when(productNameMatchService.normalize(anyString())).thenAnswer(invocation ->
                ProductNameNormalizer.normalize(invocation.getArgument(0)));
        ProductPreviewDto first = authoritativeImportRow(
                1, "yanlış açıklama", "Pil_Yuvarlak", 10, "xlsx:EK-2:row:5");
        ProductPreviewDto second = authoritativeImportRow(
                2, "başka açıklama", "Pil-Yuvarlak", 15, "xlsx:EK-2:row:6");

        ProductPreviewDto preview = bulkProductService.preparePreview(List.of(first, second)).get(0);

        assertEquals("Pil_Yuvarlak", preview.getProductName());
        assertEquals(25, preview.getQuantity());
        assertEquals(List.of("xlsx:EK-2:row:5", "xlsx:EK-2:row:6"),
                preview.getContributingSourceRecordIds());
        assertEquals(2, preview.getAuthoritativeSourceProductNames().size());
    }

    @Test
    void confirmationAppliesConsolidatedSourceQuantityExactlyOnce() {
        ProductPreviewDto first = importRow(1, "Pil Yuvarlak", 10,
                "xlsx:Ürünler:row:2");
        ProductPreviewDto second = importRow(2, "PİL_YUVARLAK", 5,
                "xlsx:Ürünler:row:3");
        ProductPreviewDto repeatedFirst = importRow(3, "Pil-Yuvarlak", 10,
                "xlsx:Ürünler:row:2");
        when(productNameMatchService.normalize(anyString())).thenReturn("pil yuvarlak");

        bulkProductService.confirmBulkImport(
                List.of(first, second, repeatedFirst), "T3");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertEquals(15, productCaptor.getValue().getQuantity());
        verify(islemGecmisiService).logEkle(
                eq(IslemTipi.PDF_YUKLEME),
                eq("Pil Yuvarlak"),
                eq(15),
                eq("Toplu İçe Aktarım - Miktar: 15"),
                eq(null),
                anyString(),
                eq(null));
    }

    @Test
    void reorderedDuplicateFixtureKeepsTenTwentyFiveTwelveThroughPreviewRematchAndConfirm() {
        when(productNameMatchService.normalize(anyString())).thenAnswer(invocation ->
                ProductNameNormalizer.normalize(invocation.getArgument(0)));
        List<ProductPreviewDto> parsed = List.of(
                importRow(1, "Beher 250 ml", 12, "xlsx:Ürünler:row:7"),
                importRow(2, "Silikon Tabancası", 10, "xlsx:Ürünler:row:5"),
                importRow(3, "Silikon Çubuğu", 25, "xlsx:Ürünler:row:6"),
                importRow(4, "Beher 250 ml", 12, "xlsx:Ürünler:row:7"),
                importRow(5, "Silikon Tabancası", 10, "xlsx:Ürünler:row:5"));

        List<ProductPreviewDto> preview = bulkProductService.preparePreview(parsed);
        assertPreviewQuantity(preview, "Silikon Tabancası", 10);
        assertPreviewQuantity(preview, "Silikon Çubuğu", 25);
        assertPreviewQuantity(preview, "Beher 250 ml", 12);

        List<ProductPreviewDto> rematched = bulkProductService.preparePreview(preview, "T3");
        assertPreviewQuantity(rematched, "Silikon Tabancası", 10);
        assertPreviewQuantity(rematched, "Silikon Çubuğu", 25);
        assertPreviewQuantity(rematched, "Beher 250 ml", 12);

        bulkProductService.confirmBulkImport(rematched, "T3");

        ArgumentCaptor<Product> products = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, org.mockito.Mockito.times(3)).save(products.capture());
        assertEquals(10, savedQuantity(products.getAllValues(), "Silikon Tabancası"));
        assertEquals(25, savedQuantity(products.getAllValues(), "Silikon Çubuğu"));
        assertEquals(12, savedQuantity(products.getAllValues(), "Beher 250 ml"));
    }

    @Test
    void singleStructuredRowKeepsTenThroughSessionRematchAndConfirmation() {
        when(productNameMatchService.normalize(anyString())).thenReturn("silikon tabancası");
        BulkPreviewSessionService sessions = new BulkPreviewSessionService();
        ProductPreviewDto parsed = importRow(
                1, "Silikon Tabancası", 10, "xlsx:Ürünler:row:5");
        ProductPreviewDto initial = bulkProductService.preparePreview(List.of(parsed)).get(0);
        com.depo.bulkimport.dto.BulkPreviewResponseDto registered = sessions.register(
                new com.depo.bulkimport.dto.BulkPreviewResponseDto(
                        null, List.of(initial), List.of(), true));

        List<ProductPreviewDto> t3Input = sessions.restoreForMatching(
                registered.getPreviewId(), registered.getProducts());
        List<ProductPreviewDto> t3 = bulkProductService.preparePreview(t3Input, "T3");
        List<ProductPreviewDto> municipalityInput = sessions.restoreForMatching(
                registered.getPreviewId(), t3);
        List<ProductPreviewDto> municipality = bulkProductService.preparePreview(
                municipalityInput, "Belediye");
        List<ProductPreviewDto> confirmationItems = sessions.beginConfirmation(
                registered.getPreviewId(), municipality);

        bulkProductService.confirmBulkImport(confirmationItems, "Belediye");

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertEquals(10, saved.getValue().getQuantity());
        assertEquals(10, confirmationItems.get(0).getQuantity());
        assertEquals(10, confirmationItems.get(0).getImportedQuantity());
        assertEquals(List.of("xlsx:Ürünler:row:5"),
                confirmationItems.get(0).getContributingSourceRecordIds());
    }

    @Test
    void oneSelectedSourceMatchIsAutomaticallyResolvedAndAppliedOnce() {
        Product target = Product.builder().id(1L).name("Pil_Yuvarlak").source("T3").quantity(51).build();
        Product duplicate = Product.builder().id(2L).name("Pil Yuvarlak").source("Belediye").quantity(20).build();
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(3).productName("PİL YUVARLAK").quantity(10).isValid(true).build();
        when(productNameMatchService.normalize("PİL YUVARLAK")).thenReturn("pil yuvarlak");
        when(productNameMatchService.resolveForSource("PİL YUVARLAK", "T3")).thenReturn(
                sourceResolution(List.of(target, duplicate), List.of(target), safeGroup(List.of(target, duplicate))));

        ProductPreviewDto preview = bulkProductService.preparePreview(List.of(item), "T3").get(0);
        BulkProductService.BulkImportResult result = bulkProductService.confirmBulkImport(List.of(item), "T3");

        assertEquals("Mevcut ürün", preview.getMatchStatus());
        assertEquals(51, preview.getExistingStock());
        assertEquals(61, preview.getProjectedStock());
        assertEquals(61, target.getQuantity());
        assertEquals(20, duplicate.getQuantity());
        assertEquals(0, result.createdCount());
        assertEquals(1, result.updatedCount());
        verify(productRepository).save(target);
    }

    @Test
    void conflictingHistoricalDuplicateGroupRequiresManualReviewInsteadOfChoosingAProduct() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(3).productName("Pil Yuvarlak").quantity(1).isValid(true).build();
        when(productNameMatchService.normalize("Pil Yuvarlak")).thenReturn("pil yuvarlak");
        Product first = Product.builder().id(1L).name("Pil Yuvarlak").source("T3").quantity(1)
                .category(Category.builder().id(1L).name("A").build()).build();
        Product second = Product.builder().id(2L).name("Pil_Yuvarlak").source("T3").quantity(1)
                .category(Category.builder().id(2L).name("B").build()).build();
        ProductNameMatchService.MatchResolution conflictingGroup =
                new ProductNameMatchService.MatchResolution(List.of(first, second), false, null, 0,
                        "Kategori farklı: A / B", List.of(
                        new ProductNameMatchService.ConflictDetail("Kategori", List.of("A", "B"))));
        when(productNameMatchService.resolveForSource("Pil Yuvarlak", "T3")).thenReturn(
                sourceResolution(List.of(first, second), List.of(first, second), conflictingGroup));

        ProductPreviewDto preview = bulkProductService.preparePreview(List.of(item), "T3").get(0);
        ProductPreviewDto confirmationItem = ProductPreviewDto.builder()
                .rowNumber(3).productName("Pil Yuvarlak").quantity(1).isValid(true).build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(confirmationItem), "T3"));

        assertEquals("Kontrol gerekli", preview.getMatchStatus());
        assertEquals(true, preview.isReviewRequired());
        assertEquals(true, exception.getMessage().contains("çözülmeden"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void explicitExistingSelectionUpdatesOnlyChosenDuplicate() {
        Product first = Product.builder().id(11L).name("Silikon Tabancası").source("T3").quantity(15).build();
        Product second = Product.builder().id(12L).name("Silikon_Tabancası").source("T3").quantity(10).build();
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(4).productName("SİLİKON-TABANCASI").quantity(20).isValid(true).build();
        when(productNameMatchService.normalize("SİLİKON-TABANCASI")).thenReturn("silikon tabancası");
        when(productNameMatchService.resolveForSource("SİLİKON-TABANCASI", "T3")).thenReturn(
                sourceResolution(List.of(first, second), List.of(first, second), safeGroup(List.of(first, second))));

        ProductPreviewDto resolved = bulkProductService.preparePreview(List.of(item), "T3").get(0);
        assertEquals(20, resolved.getQuantity());
        resolved.setReviewRequired(false);
        resolved.setMatchReviewRequired(false);
        resolved.setResolutionType("EXISTING");
        resolved.setSelectedProductId(12L);

        BulkProductService.BulkImportResult result =
                bulkProductService.confirmBulkImport(List.of(resolved), "T3");

        assertEquals(15, first.getQuantity());
        assertEquals(30, second.getQuantity());
        assertEquals(1, result.updatedCount());
        verify(productRepository).save(second);
    }

    @Test
    void importingSameWorkbookTwiceCreatesOnceThenIncrementsOnce() {
        Product existingAfterFirstImport = Product.builder()
                .id(77L).name("Silikon Tabancası").source("T3").quantity(0).build();
        when(productNameMatchService.normalize(anyString())).thenReturn("silikon tabancası");
        when(productNameMatchService.resolveForSource("Silikon Tabancası", "T3"))
                .thenReturn(sourceResolution(List.of(), List.of(), safeGroup(List.of())))
                .thenReturn(sourceResolution(
                        List.of(existingAfterFirstImport),
                        List.of(existingAfterFirstImport),
                        safeGroup(List.of(existingAfterFirstImport))));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                existingAfterFirstImport.setName(saved.getName());
                existingAfterFirstImport.setSource(saved.getSource());
                existingAfterFirstImport.setQuantity(saved.getQuantity());
            }
            return saved;
        });

        BulkProductService.BulkImportResult first = bulkProductService.confirmBulkImport(
                List.of(importRow(1, "Silikon Tabancası", 10, "xlsx:Ürünler:row:5")),
                "T3");
        BulkProductService.BulkImportResult second = bulkProductService.confirmBulkImport(
                List.of(importRow(1, "Silikon Tabancası", 10, "xlsx:Ürünler:row:5")),
                "T3");

        assertEquals(1, first.createdCount());
        assertEquals(0, first.updatedCount());
        assertEquals(0, second.createdCount());
        assertEquals(1, second.updatedCount());
        assertEquals(20, existingAfterFirstImport.getQuantity());
        verify(productRepository, org.mockito.Mockito.times(2)).save(any(Product.class));
    }

    @Test
    void explicitNewSelectionCreatesProductWithSelectedSourceWhenSourceHasNoMatch() {
        Product otherSource = Product.builder().id(21L).name("Beher_250 ml")
                .source("Belediye").quantity(100).build();
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(5).productName("Beher 250 ml").quantity(7).isValid(true).build();
        when(productNameMatchService.normalize("Beher 250 ml")).thenReturn("beher 250 ml");
        when(productNameMatchService.resolveForSource("Beher 250 ml", "T3")).thenReturn(
                sourceResolution(List.of(otherSource), List.of(), safeGroup(List.of(otherSource))));

        ProductPreviewDto resolved = bulkProductService.preparePreview(List.of(item), "T3").get(0);
        resolved.setReviewRequired(false);
        resolved.setMatchReviewRequired(false);
        resolved.setResolutionType("NEW");

        BulkProductService.BulkImportResult result =
                bulkProductService.confirmBulkImport(List.of(resolved), "T3");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertEquals("T3", productCaptor.getValue().getSource());
        assertEquals(7, productCaptor.getValue().getQuantity());
        assertEquals(1, result.createdCount());
    }

    @Test
    void confirmationRejectsManualSelectionWhenCandidateSnapshotChanged() {
        Product first = Product.builder().id(31L).name("Kablo").source("T3").quantity(10).build();
        Product second = Product.builder().id(32L).name("Kablo").source("T3").quantity(20).build();
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(6).productName("Kablo").quantity(5).isValid(true).build();
        when(productNameMatchService.normalize("Kablo")).thenReturn("kablo");
        when(productNameMatchService.resolveForSource("Kablo", "T3"))
                .thenReturn(sourceResolution(List.of(first, second), List.of(first, second), safeGroup(List.of(first, second))))
                .thenReturn(sourceResolution(List.of(first), List.of(first), safeGroup(List.of(first))));

        ProductPreviewDto resolved = bulkProductService.preparePreview(List.of(item), "T3").get(0);
        resolved.setReviewRequired(false);
        resolved.setMatchReviewRequired(false);
        resolved.setResolutionType("EXISTING");
        resolved.setSelectedProductId(31L);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(resolved), "T3"));

        assertEquals(true, exception.getMessage().contains("eşleşmeleri değişti"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void unresolvedReviewRowCannotBeSilentlyConfirmed() {
        ProductPreviewDto item = ProductPreviewDto.builder()
                .rowNumber(12)
                .productName("Teknik açıklama olabilir")
                .quantity(4)
                .isValid(true)
                .reviewRequired(true)
                .reviewMessage("Ürün adı kontrol edilmeli.")
                .build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> bulkProductService.confirmBulkImport(List.of(item), "T3"));

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("manuel"));
        verify(productRepository, never()).save(any());
    }

    private ProductNameMatchService.SourceMatchResolution sourceResolution(
            List<Product> all,
            List<Product> selectedSource,
            ProductNameMatchService.MatchResolution group) {
        return new ProductNameMatchService.SourceMatchResolution(all, selectedSource, group);
    }

    private ProductNameMatchService.MatchResolution safeGroup(List<Product> products) {
        int total = products.stream().mapToInt(Product::getQuantity).sum();
        Product target = products.isEmpty() ? null : products.get(0);
        return new ProductNameMatchService.MatchResolution(products, true, target, total, null, List.of());
    }

    private ProductPreviewDto importRow(
            int rowNumber,
            String productName,
            int quantity,
            String contributionId) {
        return ProductPreviewDto.builder()
                .rowNumber(rowNumber)
                .productName(productName)
                .quantity(quantity)
                .isValid(true)
                .contributingSourceRecordIds(List.of(contributionId))
                .build();
    }

    private ProductPreviewDto authoritativeImportRow(
            int rowNumber,
            String aiProductName,
            String sourceProductName,
            int quantity,
            String contributionId) {
        return ProductPreviewDto.builder()
                .rowNumber(rowNumber)
                .productName(aiProductName)
                .quantity(quantity)
                .isValid(true)
                .contributingSourceRecordIds(List.of(contributionId))
                .authoritativeSourceProductNames(Map.of(contributionId, sourceProductName))
                .build();
    }

    private void assertPreviewQuantity(
            List<ProductPreviewDto> preview,
            String productName,
            int expectedQuantity) {
        ProductPreviewDto item = preview.stream()
                .filter(row -> productName.equals(row.getProductName()))
                .findFirst()
                .orElseThrow();
        assertEquals(expectedQuantity, item.getQuantity());
    }

    private int savedQuantity(List<Product> products, String productName) {
        return products.stream()
                .filter(product -> productName.equals(product.getName()))
                .findFirst()
                .orElseThrow()
                .getQuantity();
    }
}

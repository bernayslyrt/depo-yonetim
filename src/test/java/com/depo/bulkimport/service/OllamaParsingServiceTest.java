package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OllamaParsingService birim testleri.
 *
 * <p>Ollama HTTP bağlantısı tamamen mock'lanır; tüm testler
 * ağ erişimi olmadan çalışır.</p>
 *
 * <p>Strateji: Servis gerçek RestTemplateBuilder ile (bogus URL'e) inşa edilir,
 * ardından {@link ReflectionTestUtils#setField} ile içindeki restTemplate alanı
 * bir mock ile değiştirilir. Private metotlar yine ReflectionTestUtils ile çağrılır.</p>
 */
class OllamaParsingServiceTest {

    private OllamaParsingService service;
    private ObjectMapper objectMapper;

    // ── Kurulum ──────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Servisi gerçek builder ile oluştur.
        // Hiçbir test callOllama() → HTTP bağlantısı gerektiren bir kod yoluna girmez;
        // yalnızca private parsing metotları yansıma ile çağrılır.
        service = new OllamaParsingService(
                new RestTemplateBuilder(),
                objectMapper,
                "http://localhost:11434",   // bogus — asla bağlantı kurulmaz
                "test-model",
                5,
                2,
                true
        );
    }

    // ── Yardımcı: private metotları yansıma ile çağır ────────────────────────

    /**
     * cleanAiResponse(String) private metodunu yansıma ile çağırır.
     */
    private String callCleanAiResponse(String raw) throws Exception {
        Method m = OllamaParsingService.class
                .getDeclaredMethod("cleanAiResponse", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, raw);
    }

    /**
     * Servisin tam JSON → DTO boru hattını çalıştırır:
     * deserializeResponse → validateResults → List<ProductPreviewDto>
     */
    @SuppressWarnings("unchecked")
    private List<ProductPreviewDto> parseJson(String json) throws Exception {
        Method deser = OllamaParsingService.class
                .getDeclaredMethod("deserializeResponse", String.class);
        deser.setAccessible(true);
        List<ProductPreviewDto> results = (List<ProductPreviewDto>) deser.invoke(service, json);

        Method validate = OllamaParsingService.class
                .getDeclaredMethod("validateResults", List.class);
        validate.setAccessible(true);
        validate.invoke(service, results);

        return results;
    }

    /**
     * getIntegerValue(Map, String) private metodunu yansıma ile çağırır.
     */
    private Integer callGetIntegerValue(Object quantityValue) throws Exception {
        Method m = OllamaParsingService.class
                .getDeclaredMethod("getIntegerValue", Map.class, String.class);
        m.setAccessible(true);
        Map<String, Object> map = Map.of("quantity", quantityValue);
        return (Integer) m.invoke(service, map, "quantity");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1) getIntegerValue — birim testleri
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getIntegerValue — tür dönüşüm testleri")
    class GetIntegerValueTests {

        @Test
        @DisplayName("Düz Integer geri döner")
        void integer_passthrough() throws Exception {
            assertThat(callGetIntegerValue(42)).isEqualTo(42);
        }

        @Test
        @DisplayName("Long → Integer dönüşümü")
        void long_to_integer() throws Exception {
            assertThat(callGetIntegerValue(100L)).isEqualTo(100);
        }

        @Test
        @DisplayName("Double 20.0 → 20 (Jackson bazen double döner)")
        void double_to_integer() throws Exception {
            assertThat(callGetIntegerValue(20.0)).isEqualTo(20);
        }

        @Test
        @DisplayName("Float 30.0f → 30")
        void float_to_integer() throws Exception {
            assertThat(callGetIntegerValue(30.0f)).isEqualTo(30);
        }

        @Test
        @DisplayName("'4 bidon' — rakam çıkarılır → 4")
        void string_4_bidon() throws Exception {
            assertThat(callGetIntegerValue("4 bidon")).isEqualTo(4);
        }

        @Test
        @DisplayName("'200 pcs' — rakam çıkarılır → 200")
        void string_200_pcs() throws Exception {
            assertThat(callGetIntegerValue("200 pcs")).isEqualTo(200);
        }

        @Test
        @DisplayName("Tamamen metin ('bidon') → null döner, uygulama çökmez")
        void string_only_returns_null() throws Exception {
            assertThat(callGetIntegerValue("bidon")).isNull();
        }

        @Test
        @DisplayName("Boş string → null döner")
        void empty_string_returns_null() throws Exception {
            Method m = OllamaParsingService.class
                    .getDeclaredMethod("getIntegerValue", Map.class, String.class);
            m.setAccessible(true);
            Map<String, Object> map = Map.of("quantity", "");
            assertThat((Integer) m.invoke(service, map, "quantity")).isNull();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2) cleanAiResponse — markdown temizleme
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("cleanAiResponse — markdown temizleme")
    class CleanAiResponseTests {

        @Test
        @DisplayName("```json ... ``` bloğunu temizler")
        void removes_json_code_block() throws Exception {
            String raw = "```json\n[{\"productCode\":\"A\"}]\n```";
            assertThat(callCleanAiResponse(raw)).isEqualTo("[{\"productCode\":\"A\"}]");
        }

        @Test
        @DisplayName("Ekstra önce/sonra metin varsa array bölümü çıkarılır")
        void extracts_array_from_surrounding_text() throws Exception {
            String raw = "İşte ürünler:\n[{\"productCode\":\"B\"}]\nTeşekkürler.";
            assertThat(callCleanAiResponse(raw)).isEqualTo("[{\"productCode\":\"B\"}]");
        }

        @Test
        @DisplayName("Boş yanıt doğrulama hatası oluşturur ve retry edilebilir")
        void blank_response_is_rejected() {
            assertThatThrownBy(() -> callCleanAiResponse(""))
                    .hasCauseInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> callCleanAiResponse("   "))
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3) Tedarikçi kenar durumları — tam boru hattı
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tedarikçi kenar durumları — tam boru hattı")
    class SupplierEdgeCaseTests {

        @Test
        @DisplayName("Temiz integer → geçerli satır, miktar doğru")
        void clean_integer_is_valid() throws Exception {
            String json = """
                    [{"productCode":"P001","productName":"Deterjan","quantity":20,"price":150.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            ProductPreviewDto dto = results.get(0);
            assertThat(dto.isValid()).isTrue();
            assertThat(dto.getQuantity()).isEqualTo(20);
            assertThat(dto.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Double quantity (20.0) → 20, geçerli")
        void double_quantity_is_coerced() throws Exception {
            String json = """
                    [{"productCode":"P002","productName":"Şampuan","quantity":20.0,"price":45.50}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isValid()).isTrue();
            assertThat(results.get(0).getQuantity()).isEqualTo(20);
        }

        @Test
        @DisplayName("'4 bidon' string quantity → rakam çıkar, geçerli satır")
        void quantity_4_bidon_is_extracted() throws Exception {
            String json = """
                    [{"productCode":"A1","productName":"Yağ","quantity":"4 bidon","price":100}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            ProductPreviewDto dto = results.get(0);
            assertThat(dto.getQuantity()).isEqualTo(4);
            assertThat(dto.getRawQuantityText()).isEqualTo("4 bidon");
            assertThat(dto.isValid()).isTrue();
        }

        @Test
        @DisplayName("'200 pcs' string quantity → 200 çıkarılır, geçerli satır")
        void quantity_200_pcs_is_extracted() throws Exception {
            String json = """
                    [{"productCode":"B2","productName":"Vida","quantity":"200 pcs","price":5.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getQuantity()).isEqualTo(200);
            assertThat(results.get(0).isValid()).isTrue();
        }

        @Test
        @DisplayName("'2 düzine' string quantity → 2 çıkarılır; rawQuantityText doğru")
        void quantity_2_duzine_extracts_leading_digit() throws Exception {
            String json = """
                    [{"productCode":"C3","productName":"Kalem","quantity":"2 düzine","price":12.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            ProductPreviewDto dto = results.get(0);
            assertThat(dto.getRawQuantityText()).isEqualTo("2 düzine");
            assertThat(dto.getQuantity()).isEqualTo(2);
            assertThat(dto.isValid()).isTrue();
        }

        @Test
        @DisplayName("'15 koli bekliyorduk ama 13 koydum' → uygulama çökmez, satır işlenir")
        void ambiguous_multi_number_quantity_does_not_crash() throws Exception {
            String json = """
                    [{"productCode":"D4","productName":"Koli","quantity":"15 koli bekliyorduk ama 13 koydum","price":0}]
                    """;

            assertThatCode(() -> parseJson(json)).doesNotThrowAnyException();

            List<ProductPreviewDto> results = parseJson(json);
            assertThat(results).hasSize(1);

            ProductPreviewDto dto = results.get(0);
            assertThat(dto.getRawQuantityText()).isNotBlank();
            assertThat(dto.isValid()).satisfiesAnyOf(
                    valid -> assertThat(valid).isTrue(),
                    valid -> assertThat(valid).isFalse()
            );
        }

        @Test
        @DisplayName("Null quantity → isValid=false, errorMessage 'miktar' içerir")
        void null_quantity_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"E5","productName":"Test Ürünü","quantity":null,"price":10.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            ProductPreviewDto dto = results.get(0);
            assertThat(dto.isValid()).isFalse();
            assertThat(dto.getErrorMessage()).containsIgnoringCase("miktar");
            assertThat(dto.getQuantity()).isNull();
        }

        @Test
        @DisplayName("Sıfır quantity → isValid=false")
        void zero_quantity_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"F6","productName":"Test Ürünü","quantity":0,"price":10.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isValid()).isFalse();
            assertThat(results.get(0).getErrorMessage()).containsIgnoringCase("0'dan büyük");
        }

        @Test
        @DisplayName("Negatif quantity → isValid=false")
        void negative_quantity_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"G7","productName":"Test Ürünü","quantity":-5,"price":10.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isValid()).isFalse();
        }

        @Test
        @DisplayName("Tamamen metin quantity ('bidon') → isValid=false, errorMessage dolu")
        void text_only_quantity_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"H8","productName":"Deterjan","quantity":"bidon","price":50.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            ProductPreviewDto dto = results.get(0);
            assertThat(dto.isValid()).isFalse();
            assertThat(dto.getErrorMessage()).isNotBlank();
            assertThat(dto.getQuantity()).isNull();
        }

        @Test
        @DisplayName("Karma liste: geçerli satır etkilenmez, geçersiz satır işaretlenir")
        void mixed_list_valid_and_invalid_rows() throws Exception {
            String json = """
                    [
                      {"productCode":"V1","productName":"Deterjan","quantity":10,"price":100.00},
                      {"productCode":"V2","productName":"Şampuan","quantity":"bidon","price":50.00}
                    ]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(2);

            ProductPreviewDto valid   = results.get(0);
            ProductPreviewDto invalid = results.get(1);

            assertThat(valid.isValid()).isTrue();
            assertThat(valid.getQuantity()).isEqualTo(10);

            assertThat(invalid.isValid()).isFalse();
            assertThat(invalid.getQuantity()).isNull();
            assertThat(invalid.getErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("Boş JSON array → boş liste, istisna yok")
        void empty_json_array_returns_empty_list() throws Exception {
            List<ProductPreviewDto> results = parseJson("[]");
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Negatif fiyat → isValid=false")
        void negative_price_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"P9","productName":"Ürün","quantity":5,"price":-10.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isValid()).isFalse();
            assertThat(results.get(0).getErrorMessage()).containsIgnoringCase("fiyat");
        }

        @Test
        @DisplayName("Boş productName → isValid=false")
        void blank_product_name_marks_row_invalid() throws Exception {
            String json = """
                    [{"productCode":"Q10","productName":"","quantity":5,"price":10.00}]
                    """;
            List<ProductPreviewDto> results = parseJson(json);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isValid()).isFalse();
            assertThat(results.get(0).getErrorMessage()).containsIgnoringCase("ürün adı");
        }

        @Test
        @DisplayName("Teknik açıklama şüphesi hard-invalid yapısal kontrollerden ayrıdır")
        void technical_description_suspicion_is_not_a_hard_structural_error() throws Exception {
            String technicalDescription = """
                    Boyut: 15 cm ve 5 mm uçlar içermelidir.
                    Teknik Özellikler: Seramik ve kil şekillendirme için farklı uç tipleri olmalıdır.
                    Malzeme: Dayanıklı plastik ve metal bileşenlerden oluşmalıdır.
                    """;
            String json = objectMapper.writeValueAsString(List.of(Map.of(
                    "productCode", "Spatula Seti",
                    "productName", technicalDescription,
                    "quantity", 4)));

            ProductPreviewDto result = parseJson(json).get(0);

            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Ürün adı ürün koduna kopyalanırsa preview satırı geçersiz olur")
        void product_name_copied_into_code_is_rejected() throws Exception {
            String json = """
                    [{"productCode":"Spatula Seti","productName":"Spatula Seti","quantity":4}]
                    """;

            ProductPreviewDto result = parseJson(json).get(0);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("ürün adından kopyalanamaz");
        }

        @Test
        @DisplayName("255 karakteri aşan ürün adı preview aşamasında geçersiz olur")
        void oversized_product_name_is_rejected_in_preview() throws Exception {
            String json = objectMapper.writeValueAsString(List.of(Map.of(
                    "productCode", "P-1",
                    "productName", "A".repeat(256),
                    "quantity", 4)));

            ProductPreviewDto result = parseJson(json).get(0);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("255");
        }
    }

    @Nested
    @DisplayName("Chunk retry ve merge")
    class ChunkRetryTests {

        @Test
        @DisplayName("İlk bozuk Ollama yanıtından sonra retry başarılı olur")
        void malformed_response_is_retried_then_succeeds() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(ollamaEnvelope("not-json"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"A1\",\"productName\":\"Kalem\",\"quantity\":6,\"price\":null}]"),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(testChunk(1)));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getProductName()).isEqualTo("Kalem");
            assertThat(results.get(0).getRowNumber()).isEqualTo(1);
            server.verify();
        }

        @Test
        @DisplayName("Tüm retry denemeleri bozuksa eksik preview yerine hata döner")
        void repeated_failure_aborts_complete_preview() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess(ollamaEnvelope("truncated ["), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> service.parseChunks(List.of(testChunk(1))))
                    .isInstanceOf(DocumentChunkParsingException.class)
                    .hasMessageContaining("Eksik bir ön izleme oluşturulmadı")
                    .hasMessageContaining("chunk=1");
            server.verify();
        }

        @Test
        @DisplayName("Birden fazla chunk kaynak sırasıyla birleşir ve satırlar yeniden numaralanır")
        void chunks_are_merged_in_source_order_and_reindexed() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"A\",\"productName\":\"Birinci\",\"quantity\":1,\"price\":null}]"),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"B\",\"productName\":\"İkinci\",\"quantity\":2,\"price\":null}]"),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(testChunk(1), testChunk(2)));

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("Birinci", "İkinci");
            assertThat(results).extracting(ProductPreviewDto::getRowNumber)
                    .containsExactly(1, 2);
            server.verify();
        }

        @Test
        @DisplayName("Semantic prompt gerçek malzeme adını ister, eksik kod null kalır ve schema sade kalır")
        void semantic_mapping_prompt_and_simplified_schema_are_sent() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(jsonPath("$.system", containsString("PRODUCT NAME")))
                    .andExpect(jsonPath("$.system", containsString("TECHNICAL DESCRIPTION")))
                    .andExpect(jsonPath("$.system", containsString("Never copy the product name into productCode")))
                    .andExpect(jsonPath("$.format.items.properties.productName.description",
                            containsString("short material/product name")))
                    .andExpect(jsonPath("$.format.items.properties.price").doesNotExist())
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":\"Spatula Seti\",\"quantity\":4}]"),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(testChunk(1)));

            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.getProductName()).isEqualTo("Spatula Seti");
                assertThat(result.getProductCode()).isNull();
                assertThat(result.isValid()).isTrue();
            });
            server.verify();
        }

        @Test
        @DisplayName("Şüpheli teknik açıklama yalnız ikinci çağrıda kaynak ürün adına düzeltilir")
        void suspicious_name_is_verified_and_corrected_without_changing_other_fields() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"SP-4\",\"productName\":"
                                    + "\"Seramik ve kil şekillendirme için farklı uç tiplerinden "
                                    + "oluşan set olmalıdır.\",\"quantity\":4}]"),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(jsonPath("$.system", containsString("verify only whether")))
                    .andExpect(jsonPath("$.prompt", containsString("source field labelled as product/material name: Spatula Seti")))
                    .andExpect(jsonPath("$.format.items.properties.status.enum[1]").value("CORRECTED"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"itemIndex\":1,\"status\":\"CORRECTED\","
                                    + "\"productName\":\"Spatula Seti\","
                                    + "\"reason\":\"Initial value was a technical description.\"}]"),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = verificationChunk("Spatula Seti");

            ProductPreviewDto result = service.parseChunks(List.of(chunk)).get(0);

            assertThat(result.getProductName()).isEqualTo("Spatula Seti");
            assertThat(result.getProductCode()).isEqualTo("SP-4");
            assertThat(result.getQuantity()).isEqualTo(4);
            assertThat(result.isReviewRequired()).isFalse();
            assertThat(result.isValid()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("Verifier yanlışlıkla CONFIRMED dese de güvenilir Malzeme Adı çelişkisi korunmaz")
        void authoritative_source_name_overrides_contradictory_confirmation() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":\"Beyaz, akrilik astarlı tuval olmalıdır.\","
                                    + "\"quantity\":4}]"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"itemIndex\":49,\"status\":\"CONFIRMED\","
                                    + "\"productName\":\"Beyaz, akrilik astarlı tuval olmalıdır.\","
                                    + "\"reason\":null}]"), MediaType.APPLICATION_JSON));
            DocumentChunk chunk = new DocumentChunk(
                    "test.xlsx", "XLSX", "EK-1", 1, 3, 3, 1, 1, false,
                    "DATA ROWS TO ANALYZE:\nROW 3:\n1 | Tuval_1 | Beyaz, akrilik astarlı | 4",
                    List.of("Tuval_1"));

            ProductPreviewDto result = service.parseChunks(List.of(chunk)).get(0);

            assertThat(result.getProductName()).isEqualTo("Tuval_1");
            assertThat(result.getQuantity()).isEqualTo(4);
            assertThat(result.isReviewRequired()).isFalse();
            assertThat(result.isValid()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("Yapılandırılmış Excel kaynak adları teknik açıklamalardan deterministik olarak geri yüklenir")
        void structured_source_names_override_color_dimensions_and_packaging_before_preview() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("["
                            + "{\"sourceRecordId\":\"xlsx:EK-1:row:10\",\"productCode\":null,"
                            + "\"productName\":\"Beyaz\",\"quantity\":400},"
                            + "{\"sourceRecordId\":\"xlsx:EK-1:row:11\",\"productCode\":null,"
                            + "\"productName\":\"Beyaz\",\"quantity\":450},"
                            + "{\"sourceRecordId\":\"xlsx:EK-1:row:12\",\"productCode\":null,"
                            + "\"productName\":\"Beyaz\",\"quantity\":500},"
                            + "{\"sourceRecordId\":\"xlsx:EK-1:row:13\",\"productCode\":null,"
                            + "\"productName\":\"50 ml, plastik şişe\",\"quantity\":1088},"
                            + "{\"sourceRecordId\":\"xlsx:EK-1:row:14\",\"productCode\":null,"
                            + "\"productName\":\"4 cm çap, 10 cm uzunluk\",\"quantity\":120}"
                            + "]"), MediaType.APPLICATION_JSON));
            List<ExcelChunkMetadata.SourceRecord> records = List.of(
                    new ExcelChunkMetadata.SourceRecord(10, "10 | Tuval_1 | Beyaz | 400", "Tuval_1", 400),
                    new ExcelChunkMetadata.SourceRecord(11, "11 | Tuval_2 | Beyaz | 450", "Tuval_2", 450),
                    new ExcelChunkMetadata.SourceRecord(12, "12 | Tuval_3 | Beyaz | 500", "Tuval_3", 500),
                    new ExcelChunkMetadata.SourceRecord(13,
                            "13 | Yapıştırıcı | 50 ml, plastik şişe | 1088", "Yapıştırıcı", 1088),
                    new ExcelChunkMetadata.SourceRecord(14,
                            "14 | Yapıştırıcı_Stick | 4 cm çap, 10 cm uzunluk | 120",
                            "Yapıştırıcı_Stick", 120));
            DocumentChunk chunk = DocumentChunk.excelRecords(
                    "test.xlsx", "EK-1", 1,
                    "Sıra No | Malzeme Adı | Teknik Özellikler | Genel Toplam", true, records);

            List<ProductPreviewDto> results = service.parseChunks(List.of(chunk));

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("Tuval_1", "Tuval_2", "Tuval_3", "Yapıştırıcı", "Yapıştırıcı_Stick");
            assertThat(results).extracting(ProductPreviewDto::getQuantity)
                    .containsExactly(400, 450, 500, 1088, 120);
            assertThat(results).allSatisfy(result -> {
                assertThat(result.isSourceIdentityReviewRequired()).isFalse();
                assertThat(result.getAuthoritativeSourceProductNames()).hasSize(1);
            });
            server.verify();
        }

        @Test
        @DisplayName("Doğrulayıcı emin değilse satır manuel kontrol durumuna alınır")
        void uncertain_verifier_result_marks_manual_review() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":"
                                    + "\"Seramik şekillendirme için dayanıklı malzemeden üretilmiştir.\","
                                    + "\"quantity\":4}]"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"itemIndex\":49,\"status\":\"REVIEW_REQUIRED\","
                                    + "\"productName\":null,"
                                    + "\"reason\":\"Ürün adı güvenilir şekilde belirlenemedi.\"}]"),
                            MediaType.APPLICATION_JSON));

            ProductPreviewDto result = service.parseChunks(List.of(verificationChunk(null))).get(0);

            assertThat(result.isReviewRequired()).isTrue();
            assertThat(result.getReviewMessage()).contains("güvenilir");
            assertThat(result.isValid()).isFalse();
            assertThat(result.getProductName()).contains("Seramik şekillendirme");
            server.verify();
        }

        @Test
        @DisplayName("Doğrulayıcı timeout olursa ana extraction başarısız olmaz, satır kontrole bırakılır")
        void verifier_timeout_degrades_to_manual_review() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":"
                                    + "\"Kullanım için dayanıklı malzemeden üretilmiştir ve uygun olmalıdır.\","
                                    + "\"quantity\":4}]"), MediaType.APPLICATION_JSON));
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andRespond(request -> {
                        throw new ResourceAccessException("verifier timeout");
                    });

            List<ProductPreviewDto> results = service.parseChunks(List.of(verificationChunk(null)));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).isReviewRequired()).isTrue();
            assertThat(results.get(0).getReviewMessage()).contains("tamamlanamadı");
            server.verify();
        }

        @Test
        @DisplayName("Kod alanı bulunmayan güvenilir Excel başlığında AI sıra numarası kodu temizlenir")
        void invented_code_is_cleared_when_excel_header_has_no_code_field() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"1\",\"productName\":\"Spatula Seti\",\"quantity\":4}]"),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = new DocumentChunk(
                    "test.xlsx", "XLSX", "EK-1", 1, 1, 2, 2, 1, true,
                    "DATA ROWS TO ANALYZE:\nROW 2:\n1 | Spatula Seti | Teknik açıklama | 4");

            ProductPreviewDto result = service.parseChunks(List.of(chunk)).get(0);

            assertThat(result.getProductCode()).isNull();
            assertThat(result.getProductName()).isEqualTo("Spatula Seti");
            assertThat(result.isValid()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("Açıkça numaralandırılmış satırlara karşı boş sonuç şüpheli sayılır ve retry edilir")
        void suspiciously_empty_structured_chunk_is_retried() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("[]"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("EK-1", 13)),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(numberedChunk("EK-1", 1)));

            assertThat(results).hasSize(13);
            server.verify();
        }

        @Test
        @DisplayName("Küçük completeness farkında retry edilir ve daha kapsamlı sonuç seçilir")
        void minor_completeness_gap_retries_and_keeps_more_complete_result() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("EK-2", 9)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("EK-2", 10)),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = new DocumentChunk(
                    "test.xlsx", "XLSX", "EK-2", 1, 1, 12, 12, 10, false,
                    "DATA ROWS TO ANALYZE:\nROW 1:\n1 | Spatula Seti | Teknik açıklama | 4");

            List<ProductPreviewDto> results = service.parseChunks(List.of(chunk));

            assertThat(results).hasSize(10);
            server.verify();
        }

        @Test
        @DisplayName("Küçük completeness farkı retrylerden sonra sürerse eksik preview döndürülmez")
        void repeated_minor_completeness_gap_aborts_preview() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("EK-2", 8)),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = new DocumentChunk(
                    "test.xlsx", "XLSX", "EK-2", 1, 1, 12, 12, 10, false,
                    "DATA ROWS TO ANALYZE:\nROW 1:\n1 | Spatula Seti | Teknik açıklama | 4");

            assertThatThrownBy(() -> service.parseChunks(List.of(chunk)))
                    .isInstanceOf(DocumentChunkParsingException.class)
                    .hasMessageContaining("hâlâ eksik")
                    .hasMessageContaining("Eksik bir ön izleme oluşturulmadı");
            server.verify();
        }

        @Test
        @DisplayName("Şüpheli derecede eksik sonuç tüm retrylerden sonra previewı durdurur")
        void repeatedly_sparse_structured_chunk_aborts_preview() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("[]"), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> service.parseChunks(List.of(numberedChunk("EK-1", 1))))
                    .isInstanceOf(DocumentChunkParsingException.class)
                    .hasMessageContaining("Eksik bir ön izleme oluşturulmadı");
            server.verify();
        }

        @Test
        @DisplayName("Beş worksheet ve her birindeki iki chunk sonuçları kaybolmadan eklenir")
        void five_worksheets_with_multiple_chunks_are_all_appended() throws Exception {
            MockRestServiceServer server = mockServer();
            List<DocumentChunk> chunks = new ArrayList<>();
            for (int sheet = 1; sheet <= 5; sheet++) {
                for (int chunk = 1; chunk <= 2; chunk++) {
                    String label = "EK-" + sheet + "-" + chunk;
                    chunks.add(new DocumentChunk(
                            "five.xlsx", "XLSX", "EK-" + sheet, chunk, 1, 2,
                            "DATA ROWS TO ANALYZE:\n" + label));
                    server.expect(requestTo("http://localhost:11434/api/generate"))
                            .andRespond(withSuccess(ollamaEnvelope(productsJson(label, 1)),
                                    MediaType.APPLICATION_JSON));
                }
            }

            List<ProductPreviewDto> results = service.parseChunks(chunks);

            assertThat(results).hasSize(10);
            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly(
                            "EK-1-1-1", "EK-1-2-1", "EK-2-1-1", "EK-2-2-1",
                            "EK-3-1-1", "EK-3-2-1", "EK-4-1-1", "EK-4-2-1",
                            "EK-5-1-1", "EK-5-2-1");
            assertThat(results).extracting(ProductPreviewDto::getRowNumber)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            server.verify();
        }

        @Test
        @DisplayName("Dört PDF sayfasının sonuçları sayfa sırasıyla birleştirilir")
        void four_pdf_pages_are_all_appended_in_page_order() throws Exception {
            MockRestServiceServer server = mockServer();
            List<DocumentChunk> chunks = new ArrayList<>();
            for (int page = 1; page <= 4; page++) {
                chunks.add(pdfChunk(page, 1, 0, "Page " + page + " product"));
                server.expect(requestTo("http://localhost:11434/api/generate"))
                        .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF-" + page, 1)),
                                MediaType.APPLICATION_JSON));
            }

            List<ProductPreviewDto> results = service.parseChunks(chunks);

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("PDF-1-1", "PDF-2-1", "PDF-3-1", "PDF-4-1");
            assertThat(results).extracting(ProductPreviewDto::getRowNumber)
                    .containsExactly(1, 2, 3, 4);
            server.verify();
        }

        @Test
        @DisplayName("Aynı PDF sayfasındaki birden fazla chunk kaybolmadan birleşir")
        void multiple_chunks_on_one_pdf_page_are_merged() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PAGE2-A", 2)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PAGE2-B", 2)),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    pdfChunk(2, 1, 0, "First half"),
                    pdfChunk(2, 2, 0, "Second half")));

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("PAGE2-A-1", "PAGE2-A-2", "PAGE2-B-1", "PAGE2-B-2");
            server.verify();
        }

        @Test
        @DisplayName("PDF promptu sayfa bağlamını ve teknik sayı güvenliğini açıkça taşır")
        void pdf_prompt_is_page_aware_and_keeps_specification_numbers_out_of_quantity() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(jsonPath("$.system", containsString("PDF-SPECIFIC CONTEXT")))
                    .andExpect(jsonPath("$.system", containsString("12 V")))
                    .andExpect(jsonPath("$.prompt", containsString("SOURCE TYPE: PDF")))
                    .andExpect(jsonPath("$.prompt", containsString("PAGE: 3")))
                    .andExpect(jsonPath("$.prompt", containsString("12V 5mm 300 pieces 20x15mm")))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":\"LED Set\",\"quantity\":7}]"),
                            MediaType.APPLICATION_JSON));

            ProductPreviewDto result = service.parseChunks(List.of(
                    pdfChunk(3, 1, 0,
                            "LED Set | Technical 12V 5mm 300 pieces 20x15mm | Quantity 7"))).get(0);

            assertThat(result.getQuantity()).isEqualTo(7);
            server.verify();
        }

        @Test
        @DisplayName("Kod alanı olmayan PDF'de ürün adı productCode olarak korunmaz")
        void pdf_without_explicit_code_field_clears_name_copied_into_product_code() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"M3 Vida Somun Seti\","
                                    + "\"productName\":\"M3 Vida Somun Seti\",\"quantity\":3}]"),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = DocumentChunk.pdf(
                    "test.pdf", 1, 1, 2, List.of(), true, 0,
                    "Urun Adi: M3 Vida Somun Seti\nMiktar: 3");

            ProductPreviewDto result = service.parseChunks(List.of(chunk)).get(0);

            assertThat(result.getProductCode()).isNull();
            assertThat(result.getProductName()).isEqualTo("M3 Vida Somun Seti");
            assertThat(result.isValid()).isTrue();
            server.verify();
        }

        @Test
        @DisplayName("Çok satırlı ürün adı açık kod alanında productCode olursa nedenli hata döner")
        void multiline_name_in_explicit_pdf_code_field_has_user_facing_validation_reason() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":\"M3 Vida\\nSomun\\nSeti\","
                                    + "\"productName\":\"M3 Vida Somun Seti\",\"quantity\":3}]"),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = DocumentChunk.pdf(
                    "test.pdf", 1, 1, 3, List.of(), false, 0,
                    "Urun Kodu: M3 Vida Somun Seti\nUrun Adi: M3 Vida Somun Seti\nMiktar: 3");

            ProductPreviewDto result = service.parseChunks(List.of(chunk)).get(0);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage())
                    .contains("kontrol karakteri")
                    .contains("ürün adından kopyalanamaz");
            server.verify();
        }

        @Test
        @DisplayName("Kesin aday sayısı bilinmeyen PDF'de çok düşük çıkarım retry edilir")
        void suspiciously_low_free_form_pdf_extraction_is_retried() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("LOW", 2)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("RETRY", 8)),
                            MediaType.APPLICATION_JSON));
            DocumentChunk chunk = DocumentChunk.pdf(
                    "test.pdf", 3, 1, 20, List.of(), false, 12,
                    "Twelve repeated item markers in a semi-structured PDF list");

            List<ProductPreviewDto> results = service.parseChunks(List.of(chunk));

            assertThat(results).hasSize(8);
            server.verify();
        }

        @Test
        @DisplayName("PDF sayfa özeti merge, validation ve preview sayılarını açıkça loglar")
        void pdf_page_final_accounting_is_logged_without_silent_row_removal() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("VALID", 1)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":\"Invalid quantity\","
                                    + "\"quantity\":null}]"), MediaType.APPLICATION_JSON));

            Logger logger = (Logger) LoggerFactory.getLogger(OllamaParsingService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                List<ProductPreviewDto> results = service.parseChunks(List.of(
                        pdfChunk(4, 1, 0, "First item"),
                        pdfChunk(4, 2, 0, "Second item")));

                assertThat(results).hasSize(2);
                assertThat(appender.list)
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .anySatisfy(message -> assertThat(message)
                                .contains("page=4")
                                .contains("productsAfterMerge=2")
                                .contains("productsAfterValidation=2")
                                .contains("invalidProducts=1")
                                .contains("finalPreviewContribution=2"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
            server.verify();
        }

        @Test
        @DisplayName("Eksik PDF chunk sonucu retry edilir ve tam sonuç alınır")
        void incomplete_pdf_chunk_is_retried() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF", 24)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF", 25)),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    pdfChunk(2, 1, 25, "Twenty-five reliable numbered PDF rows")));

            assertThat(results).hasSize(25);
            server.verify();
        }

        @Test
        @DisplayName("Sayfa dizisinden kanıtlanan iki kayıtlı son PDF chunk'ı da strict kontrol edilir")
        void small_tail_pdf_chunk_with_page_level_evidence_is_retried() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("TAIL", 1)),
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("TAIL", 2)),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    pdfChunk(2, 3, 2, "Two reliable tail records from the page sequence")));

            assertThat(results).hasSize(2);
            server.verify();
        }

        @Test
        @DisplayName("25 güvenilir PDF kaydına 25 sonuç tek denemede başarılı olur")
        void complete_pdf_chunk_does_not_retry() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF", 25)),
                            MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    pdfChunk(2, 1, 25, "Twenty-five reliable numbered PDF rows")));

            assertThat(results).hasSize(25);
            server.verify();
        }

        @Test
        @DisplayName("Kalıcı PDF chunk eksikliği normal görünen kısmi preview döndürmez")
        void persistent_pdf_chunk_gap_aborts_the_complete_preview() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF", 24)),
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> service.parseChunks(List.of(
                    pdfChunk(3, 2, 25, "Twenty-five reliable numbered PDF rows"))))
                    .isInstanceOf(DocumentChunkParsingException.class)
                    .hasMessageContaining("page=3")
                    .hasMessageContaining("Eksik bir ön izleme oluşturulmadı");
            server.verify();
        }

        @Test
        @DisplayName("Ürün adı ikinci aşama doğrulaması PDF için kaynak sütun varsaymadan çalışır")
        void pdf_product_name_verification_uses_page_source_without_excel_authority() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"productCode\":null,\"productName\":"
                                    + "\"Seramik ve kil şekillendirme için dayanıklı malzemeden üretilmiştir.\","
                                    + "\"quantity\":4}]"), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andExpect(jsonPath("$.prompt", containsString("PAGE: 4")))
                    .andRespond(withSuccess(ollamaEnvelope(
                            "[{\"itemIndex\":1,\"status\":\"CORRECTED\","
                                    + "\"productName\":\"Spatula Seti\","
                                    + "\"reason\":\"Initial value was technical description.\"}]"),
                            MediaType.APPLICATION_JSON));

            ProductPreviewDto result = service.parseChunks(List.of(
                    pdfChunk(4, 1, 0,
                            "Spatula Seti\nSeramik ve kil sekillendirme icin teknik aciklama\nMiktar: 4")))
                    .get(0);

            assertThat(result.getProductName()).isEqualTo("Spatula Seti");
            assertThat(result.getQuantity()).isEqualTo(4);
            assertThat(result.isReviewRequired()).isFalse();
            server.verify();
        }

        @Test
        @DisplayName("Ürün içermeyen PDF sayfası boş sonuçla güvenle tamamlanabilir")
        void non_product_pdf_page_may_return_empty_result() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("[]"), MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    pdfChunk(1, 1, 0, "INVENTORY CATALOG COVER PAGE")));

            assertThat(results).isEmpty();
            server.verify();
        }

        @Test
        @DisplayName("Beş güvenilir PDF record bloğuna dört çıktı kalıcı olarak kabul edilmez")
        void five_pdf_records_returning_four_outputs_fail_after_bounded_retries() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(ExpectedCount.times(3), requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope(productsJson("PDF", 4)),
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> service.parseChunks(List.of(
                    reliableRecordChunk(1, "Alpha", "Beta", "Gamma", "Delta", "Epsilon"))))
                    .isInstanceOf(DocumentChunkParsingException.class)
                    .hasMessageContaining("kayıt sayısı uyuşmadan")
                    .hasMessageContaining("Eksik bir ön izleme oluşturulmadı");
            server.verify();
        }

        @Test
        @DisplayName("Komşu PDF record alan kayması retry ile düzeltilir")
        void adjacent_pdf_record_field_shift_triggers_retry() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("""
                            [{"productCode":null,"productName":"Beta","quantity":2},
                             {"productCode":null,"productName":"Alpha","quantity":1}]
                            """), MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("""
                            [{"productCode":null,"productName":"Alpha","quantity":1},
                             {"productCode":null,"productName":"Beta","quantity":2}]
                            """), MediaType.APPLICATION_JSON));

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    reliableRecordChunk(1, "Alpha", "Beta")));

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("Alpha", "Beta");
            server.verify();
        }

        @Test
        @DisplayName("Güvenilir PDF ad sınırı sıra numarası ve açıklama taşmasını kanonikleştirir")
        void reliable_pdf_name_anchor_canonicalizes_sequence_prefix_and_description_tail()
                throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("""
                            [{"productCode":null,
                              "productName":"21. Reaktif Şişesi 250 ml Yüzey",
                              "quantity":14}]
                            """), MediaType.APPLICATION_JSON));
            PdfRecordSegmenter.LogicalRecord record = new PdfRecordSegmenter.LogicalRecord(
                    21,
                    "21. Reaktif Şişesi 250 ml - Yüzey dayanımı yüksektir. "
                            + "Talep edilen stok miktarı: 14 adet.",
                    "Reaktif Şişesi 250 ml",
                    null,
                    14,
                    true,
                    PdfRecordSegmenter.StartKind.NUMBERED);

            List<ProductPreviewDto> results = service.parseChunks(List.of(
                    DocumentChunk.pdfRecords(
                            "test.pdf", 3, 1, "Header", List.of(record),
                            PdfRecordSegmenter.Confidence.RELIABLE, false)));

            assertThat(results).extracting(ProductPreviewDto::getProductName)
                    .containsExactly("Reaktif Şişesi 250 ml");
            server.verify();
        }

        @Test
        @DisplayName("Karma PDF chunk içinde kod yok kanıtı kayıt bazında uygulanır")
        void mixed_pdf_chunk_clears_only_the_record_whose_code_is_absent() throws Exception {
            MockRestServiceServer server = mockServer();
            server.expect(requestTo("http://localhost:11434/api/generate"))
                    .andRespond(withSuccess(ollamaEnvelope("""
                            [{"productCode":"WRONG","productName":"Coded Product","quantity":2},
                             {"productCode":"M3 Vida Somun Seti",
                              "productName":"M3 Vida Somun Seti","quantity":3}]
                            """), MediaType.APPLICATION_JSON));
            List<PdfRecordSegmenter.LogicalRecord> records = List.of(
                    new PdfRecordSegmenter.LogicalRecord(
                            1, "Malzeme: Coded Product\nKod: P-1\nIstenen miktar: 2",
                            "Coded Product", "P-1", 2, false,
                            PdfRecordSegmenter.StartKind.EXPLICIT_LABEL),
                    new PdfRecordSegmenter.LogicalRecord(
                            2, "Malzeme: M3 Vida Somun Seti\nKod: Belgede belirtilmemis"
                                    + "\nIstenen miktar: 3",
                            "M3 Vida Somun Seti", null, 3, true,
                            PdfRecordSegmenter.StartKind.EXPLICIT_LABEL));
            DocumentChunk chunk = DocumentChunk.pdfRecords(
                    "test.pdf", 4, 1, "Mixed records", records,
                    PdfRecordSegmenter.Confidence.RELIABLE, true);

            List<ProductPreviewDto> results = service.parseChunks(List.of(chunk));

            assertThat(results).extracting(ProductPreviewDto::getProductCode)
                    .containsExactly("P-1", null);
            assertThat(results.get(1).getProductName()).isEqualTo("M3 Vida Somun Seti");
            assertThat(results.get(1).getQuantity()).isEqualTo(3);
            server.verify();
        }

        private MockRestServiceServer mockServer() {
            RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
            return MockRestServiceServer.bindTo(restTemplate).build();
        }

        private DocumentChunk reliableRecordChunk(int pageNumber, String... names) {
            List<PdfRecordSegmenter.LogicalRecord> records = new ArrayList<>();
            for (int index = 0; index < names.length; index++) {
                records.add(new PdfRecordSegmenter.LogicalRecord(
                        index + 1,
                        "Urun Adi: " + names[index] + "\nADET: " + (index + 1),
                        names[index],
                        null,
                        index + 1,
                        true,
                        PdfRecordSegmenter.StartKind.EXPLICIT_LABEL));
            }
            return DocumentChunk.pdfRecords(
                    "test.pdf", pageNumber, 1, "Reference header", records,
                    PdfRecordSegmenter.Confidence.RELIABLE, false);
        }

        private String ollamaEnvelope(String generated) throws Exception {
            return objectMapper.writeValueAsString(Map.of("response", generated));
        }

        private DocumentChunk testChunk(int index) {
            return new DocumentChunk(
                    "test.xlsx", "XLSX", "Sheet1", index, 1, 2,
                    "DATA ROWS TO ANALYZE:\nROW 1:\nKalem | 1");
        }

        private DocumentChunk numberedChunk(String worksheet, int index) {
            return new DocumentChunk(
                    "test.xlsx", "XLSX", worksheet, index, 1, 15, 15, 13, false,
                    "DATA ROWS TO ANALYZE:\nROW 1:\n1 | Spatula Seti | Teknik açıklama | 4");
        }

        private DocumentChunk verificationChunk(String sourceProductNameCandidate) {
            return new DocumentChunk(
                    "test.xlsx", "XLSX", "EK-1", 1, 3, 3, 1, 1, false,
                    "POTENTIAL HEADER/CONTEXT ROWS (REFERENCE ONLY):\n"
                            + "ROW 1:\nSıra No | Malzeme Adı | Teknik Özellikler | Toplam Adet\n\n"
                            + "DATA ROWS TO ANALYZE:\nROW 3:\n1 | Spatula Seti | "
                            + "Seramik ve kil şekillendirme için farklı uçlar | 4",
                    sourceProductNameCandidate == null
                            ? List.of()
                            : List.of(sourceProductNameCandidate));
        }

        private DocumentChunk pdfChunk(
                int pageNumber,
                int chunkIndex,
                int candidateRecordCount,
                String sourceText) {
            return DocumentChunk.pdf(
                    "test.pdf",
                    pageNumber,
                    chunkIndex,
                    (int) sourceText.lines().filter(line -> !line.isBlank()).count(),
                    java.util.stream.IntStream.rangeClosed(1, candidateRecordCount)
                            .boxed()
                            .toList(),
                    sourceText);
        }

        private String productsJson(String prefix, int count) throws Exception {
            List<Map<String, Object>> products = new ArrayList<>();
            for (int index = 1; index <= count; index++) {
                products.add(Map.of(
                        "productCode", prefix + "-C" + index,
                        "productName", prefix + "-" + index,
                        "quantity", index));
            }
            return objectMapper.writeValueAsString(products);
        }
    }
}

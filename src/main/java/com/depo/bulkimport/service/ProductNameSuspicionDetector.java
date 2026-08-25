package com.depo.bulkimport.service;

import com.depo.bulkimport.dto.ProductPreviewDto;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative, deterministic signal combiner for semantic product-name review. */
final class ProductNameSuspicionDetector {

    private static final Pattern SENTENCE_PHRASE = Pattern.compile(
            "(?iu)\\b(?:olmalıdır|üretilmiştir|kullanılabilir|uygundur|özelliği|özellikleri|"
                    + "performansı|boyutları|ölçüleri|malzemeden|dayanıklı)\\b");
    private static final Pattern SPECIFICATION_NUMBER = Pattern.compile(
            "(?iu)(?:\\d+(?:[.,]\\d+)?\\s*(?:v|volt|w|watt|mm|cm|m|mikron|micron|"
                    + "ml|cl|l|gr|kg|hz|mah|amper)|\\d+(?:[.,]\\d+)?\\s*[x×]\\s*\\d+(?:[.,]\\d+)?)");
    private static final Pattern DIMENSION_DESCRIPTION = Pattern.compile(
            "(?iu)\\d+(?:[.,]\\d+)?\\s*(?:mm|cm|m|mikron|micron)\\s*"
                    + "(?:çap|uzunluk|genişlik|yükseklik|kalınlık)");
    private static final Pattern PACKAGING_DESCRIPTION = Pattern.compile(
            "(?iu)(?:\\d+\\s*['’]?\\s*(?:li|lı|lu|lü)\\s*"
                    + "(?:paket|set|kutu|ambalaj)|paket içeriği|set içeriği|"
                    + "\\d+(?:[.,]\\d+)?\\s*(?:ml|cl|l)\\s*[,;]?\\s*"
                    + "(?:plastik\\s+)?(?:şişe|kutu|paket|ambalaj))");
    private static final List<String> TECHNICAL_TERMS = List.of(
            "teknik özellik", "boyut", "ölçü", "voltaj", "gerilim", "performans",
            "set içeriği", "paket içeriği", "malzemeden", "üretilmiştir", "kullanım için",
            "içermelidir", "oluşmalıdır", "adet içermeli");

    Suspicion inspect(ProductPreviewDto product, String sourceProductNameCandidate) {
        String productName = ProductPreviewValidation.trimToNull(product.getProductName());
        if (productName == null) {
            return Suspicion.notSuspicious();
        }

        List<String> signals = new ArrayList<>();
        int score = 0;
        boolean sourceMismatch = hasReliableSourceMismatch(productName, sourceProductNameCandidate);
        if (sourceMismatch) {
            score += 4;
            signals.add("Kaynak Malzeme Adı alanıyla eşleşmiyor");
        }

        long lineCount = productName.lines().filter(line -> !line.isBlank()).count();
        if (lineCount >= 2) {
            score += 2;
            signals.add("Birden fazla satıra yayılıyor");
        }

        Matcher sentenceMatcher = SENTENCE_PHRASE.matcher(productName);
        if (sentenceMatcher.find()) {
            score += 3;
            signals.add("Açıklama cümlesi dili içeriyor");
        }

        String normalized = productName.toLowerCase(Locale.forLanguageTag("tr-TR"));
        long technicalTermCount = TECHNICAL_TERMS.stream().filter(normalized::contains).count();
        if (technicalTermCount >= 2) {
            score += 2;
            signals.add("Birden fazla teknik ifade içeriyor");
        } else if (technicalTermCount == 1) {
            score += 1;
        }

        int numericSpecificationCount = matchCount(SPECIFICATION_NUMBER, productName);
        if (numericSpecificationCount >= 2) {
            score += 4;
            signals.add("Birden fazla ölçü/teknik sayı içeriyor");
        } else if (numericSpecificationCount == 1) {
            score += 1;
        }
        if (DIMENSION_DESCRIPTION.matcher(productName).find()) {
            score += 4;
            signals.add("Ürün adı yerine boyut açıklamasına benziyor");
        }
        if (PACKAGING_DESCRIPTION.matcher(productName).find()) {
            score += 4;
            signals.add("Ürün adı yerine paketleme açıklamasına benziyor");
        }

        int wordCount = productName.trim().split("\\s+").length;
        long punctuationCount = productName.chars()
                .filter(character -> character == ',' || character == ';' || character == ':'
                        || character == '.' || character == '(' || character == ')')
                .count();
        if (wordCount >= 12 && productName.matches("(?s).*?[.!?]\\s*$")) {
            score += 2;
            signals.add("Ürün etiketi yerine tam cümleye benziyor");
        }
        if (punctuationCount >= 3) {
            score += 1;
            signals.add("Yoğun noktalama içeriyor");
        }

        boolean suspicious = sourceMismatch || score >= 4;
        return new Suspicion(suspicious, score, List.copyOf(signals));
    }

    private boolean hasReliableSourceMismatch(String productName, String sourceCandidate) {
        String candidate = ProductPreviewValidation.trimToNull(sourceCandidate);
        return candidate != null && !semanticKey(productName).equals(semanticKey(candidate));
    }

    private String semanticKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.forLanguageTag("tr-TR"))
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private int matchCount(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    record Suspicion(boolean suspicious, int score, List<String> signals) {
        private static Suspicion notSuspicious() {
            return new Suspicion(false, 0, List.of());
        }

        String userMessage() {
            if (signals.contains("Kaynak Malzeme Adı alanıyla eşleşmiyor")) {
                return "Ürün adı kaynak Malzeme Adı alanıyla uyuşmuyor olabilir.";
            }
            return "Ürün adı teknik açıklama olabilir.";
        }
    }
}

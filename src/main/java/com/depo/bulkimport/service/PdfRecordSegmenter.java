package com.depo.bulkimport.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects conservative PDF record boundaries without interpreting inventory
 * fields. The extracted blocks remain source text; Ollama still performs the
 * semantic product mapping.
 */
final class PdfRecordSegmenter {

    enum Confidence {
        RELIABLE,
        APPROXIMATE,
        UNKNOWN
    }

    enum StartKind {
        NUMBERED,
        EXPLICIT_LABEL
    }

    record LogicalRecord(
            int sourceRecordId,
            String sourceText,
            String productNameAnchor,
            String productCodeAnchor,
            Integer quantityAnchor,
            boolean explicitProductCodeAbsent,
            StartKind startKind) {
    }

    record Segmentation(
            String headerContext,
            List<LogicalRecord> records,
            Confidence confidence,
            int detectedRecordMarkers,
            boolean mixedFormats) {

        Segmentation {
            records = List.copyOf(records);
        }
    }

    private static final Pattern NUMBERED_START = Pattern.compile(
            "^\\s*(\\d{1,4})\\s*(?:[.)\\-:|;]\\s*|\\s+)(?=.*\\p{L}).*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern QUANTITY_ONLY = Pattern.compile(
            "^\\s*\\d{1,9}\\s*(?:adet|ad\\.?|pcs?)\\.?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TECHNICAL_NUMBER_START = Pattern.compile(
            "^\\s*\\d+(?:[.,]\\d+)?\\s*(?:v|w|a|mm|cm|ml|kg|g|m|metre|meter|"
                    + "volt|adet|pieces?|parça|parca|profil|profile)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLICIT_RECORD_START = Pattern.compile(
            "^\\s*(?:ürün|urun|malzeme|araç\\s+gereç|arac\\s+gerec|product|item)"
                    + "(?:\\s+(?:adı|adi|name))?\\s*[:|]\\s*\\S.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STRONG_MIXED_NUMBERED_START = Pattern.compile(
            "^\\s*\\d{1,4}\\s*[.)\\-:|;]\\s*.*\\|.*"
                    + "(?:toplam|miktar|quantity|qty)\\s*[:|].*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBERED_RECORD_WITH_TRAILING_QUANTITY = Pattern.compile(
            "^\\s*\\d{1,4}\\s*(?:[.)\\-:|;]\\s*|\\s+)(?=.*\\p{L}).*?"
                    + "(?:[|;]\\s*|\\s+)(\\d{1,9})(?:\\s*(?:adet|ad\\.?|pcs?))?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CONSERVATIVE_DESCRIPTION_START = Pattern.compile(
            "(?iu)(?:yüzey\\s+dayanım|set\\s+içerisinde|paket\\s+içerisinde|"
                    + "kalınlık\\s+\\d|su\\s+bazlı(?:dır)?|çok\\s+amaçlı\\s+eğitim|"
                    + "\\d+(?:[.,]\\d+)?\\s*mm\\s+uç|\\d+\\s*parçalı\\s+kutu|"
                    + "\\d+(?:[.,]\\d+)?\\s*[x×]\\s*\\d+(?:[.,]\\d+)?\\s*mm\\s+ölçüler|"
                    + "\\d+(?:[.,]\\d+)?\\s*metre\\s+kablo|\\d+\\s*v\\s+adaptörlerle)");
    private static final Pattern LABELLED_QUANTITY = Pattern.compile(
            "(?:talep\\s+edilen\\s+stok\\s+miktar[ıi]|istenen\\s+miktar|"
                    + "genel\\s+toplam|toplam(?:\\s+miktar)?|miktar|adet|quantity|qty)"
                    + "\\s*[:|]?\\s*(\\d{1,9})(?:\\s*adet)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CODE_LABEL = Pattern.compile(
            "(?:^|\\|)\\s*(?:kod|ürün\\s+kodu|urun\\s+kodu|stok\\s+kodu|"
                    + "malzeme\\s+kodu)\\s*[:|]\\s*([^|\\n]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
    private static final Pattern PAGE_FOOTER = Pattern.compile(
            "^\\s*(?:(?:test\\s+)?özet[i]?|(?:(?:genel|beklenen)\\s+)?toplam)\\s*[:：].*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    Segmentation segment(String pageText) {
        List<String> lines = pageText.lines().toList();
        List<NumberedCandidate> numberedCandidates = findNumberedCandidates(lines);
        Set<Integer> reliableNumberedIds = reliableSequentialIdentifiers(numberedCandidates);
        int explicitStartCount = (int) lines.stream()
                .filter(line -> EXPLICIT_RECORD_START.matcher(line).matches())
                .count();
        boolean repeatedExplicitTemplate = explicitStartCount >= 2;

        List<RecordStart> starts = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (EXPLICIT_RECORD_START.matcher(line).matches()) {
                starts.add(new RecordStart(lineIndex, StartKind.EXPLICIT_LABEL, null));
                continue;
            }

            NumberedCandidate numbered = numberedCandidate(lineIndex, line);
            if (numbered == null) {
                continue;
            }
            if (reliableNumberedIds.contains(numbered.identifier())
                    || repeatedExplicitTemplate
                    && STRONG_MIXED_NUMBERED_START.matcher(line).matches()) {
                starts.add(new RecordStart(
                        lineIndex, StartKind.NUMBERED, numbered.identifier()));
            }
        }

        if (starts.isEmpty()) {
            int approximateMarkers = Math.max(numberedCandidates.size(), explicitStartCount);
            return new Segmentation(
                    "", List.of(),
                    approximateMarkers > 0 ? Confidence.APPROXIMATE : Confidence.UNKNOWN,
                    approximateMarkers, false);
        }

        String headerContext = joinLines(lines, 0, starts.get(0).lineIndex());
        List<LogicalRecord> records = new ArrayList<>();
        Set<StartKind> startKinds = new HashSet<>();
        boolean everyStartIsNumbered = starts.stream()
                .allMatch(start -> start.kind() == StartKind.NUMBERED);
        for (int recordIndex = 0; recordIndex < starts.size(); recordIndex++) {
            RecordStart start = starts.get(recordIndex);
            int end = recordIndex + 1 < starts.size()
                    ? starts.get(recordIndex + 1).lineIndex()
                    : lines.size();
            while (end > start.lineIndex() + 1
                    && PAGE_FOOTER.matcher(lines.get(end - 1)).matches()) {
                end--;
            }
            String sourceText = joinLines(lines, start.lineIndex(), end);
            if (sourceText.isBlank()) {
                continue;
            }
            startKinds.add(start.kind());
            records.add(new LogicalRecord(
                    everyStartIsNumbered ? start.identifier() : records.size() + 1,
                    sourceText,
                    extractProductNameAnchor(sourceText, start.kind()),
                    extractProductCodeAnchor(sourceText),
                    extractQuantityAnchor(sourceText),
                    explicitProductCodeAbsent(sourceText),
                    start.kind()));
        }

        boolean everyRecordHasQuantityEvidence = !records.isEmpty()
                && records.stream().allMatch(record -> record.quantityAnchor() != null);
        boolean sequentialStructure = reliableNumberedIds.size() >= 3;
        boolean repeatedLabelStructure = explicitStartCount >= 2;
        Confidence confidence = everyRecordHasQuantityEvidence
                && (sequentialStructure || repeatedLabelStructure)
                ? Confidence.RELIABLE
                : Confidence.APPROXIMATE;

        return new Segmentation(
                headerContext,
                records,
                confidence,
                records.size(),
                startKinds.size() > 1);
    }

    private List<NumberedCandidate> findNumberedCandidates(List<String> lines) {
        List<NumberedCandidate> candidates = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            NumberedCandidate candidate = numberedCandidate(lineIndex, lines.get(lineIndex));
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private NumberedCandidate numberedCandidate(int lineIndex, String line) {
        if (QUANTITY_ONLY.matcher(line).matches()
                || TECHNICAL_NUMBER_START.matcher(line).matches()
                || isHeaderLike(line)) {
            return null;
        }
        Matcher matcher = NUMBERED_START.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        return new NumberedCandidate(lineIndex, Integer.parseInt(matcher.group(1)));
    }

    private Set<Integer> reliableSequentialIdentifiers(List<NumberedCandidate> candidates) {
        Set<Integer> unique = new LinkedHashSet<>();
        candidates.forEach(candidate -> unique.add(candidate.identifier()));
        List<Integer> sorted = unique.stream().sorted().toList();
        Set<Integer> reliable = new HashSet<>();
        List<Integer> run = new ArrayList<>();
        for (Integer identifier : sorted) {
            if (!run.isEmpty() && identifier != run.get(run.size() - 1) + 1) {
                addReliableRun(reliable, run);
                run.clear();
            }
            run.add(identifier);
        }
        addReliableRun(reliable, run);
        return reliable;
    }

    private void addReliableRun(Set<Integer> reliable, List<Integer> run) {
        if (run.size() >= 3) {
            reliable.addAll(run);
        }
    }

    private boolean isHeaderLike(String line) {
        String normalized = normalize(line);
        boolean hasSequenceHeader = containsAny(normalized, "sira no", "sira numarasi");
        boolean hasProductNameHeader = containsAny(
                normalized, "urun adi", "malzeme adi", "urun / malzeme");
        if (!hasSequenceHeader && !hasProductNameHeader) {
            return false;
        }
        int labels = 0;
        labels += hasSequenceHeader ? 1 : 0;
        labels += hasProductNameHeader ? 1 : 0;
        labels += containsAny(normalized, "urun kod", "malzeme kod", "stok kod") ? 1 : 0;
        labels += containsAny(normalized, "teknik ozellik", "teknik bilgi", "aciklama") ? 1 : 0;
        labels += containsAny(normalized, "miktar", "toplam", "adet") ? 1 : 0;
        return labels >= 3;
    }

    private boolean containsAny(String text, String... alternatives) {
        for (String alternative : alternatives) {
            if (text.contains(alternative)) {
                return true;
            }
        }
        return false;
    }

    private String extractProductNameAnchor(String sourceText, StartKind startKind) {
        String firstLine = sourceText.lines().findFirst().orElse("").trim();
        if (startKind == StartKind.EXPLICIT_LABEL) {
            int separator = firstSeparatorIndex(firstLine);
            return separator < 0 ? null : trimToNull(firstLine.substring(separator + 1));
        }

        Matcher numbered = NUMBERED_START.matcher(firstLine);
        if (!numbered.matches()) {
            return null;
        }
        String withoutMarker = firstLine.substring(numbered.end(1)).trim()
                .replaceFirst("^[.)\\-:|;]\\s*", "");
        int pipe = withoutMarker.indexOf('|');
        if (pipe >= 0) {
            return trimToNull(withoutMarker.substring(0, pipe));
        }
        int proseSeparator = withoutMarker.indexOf(" - ");
        if (proseSeparator >= 0) {
            return trimToNull(withoutMarker.substring(0, proseSeparator));
        }
        Matcher descriptionStart = CONSERVATIVE_DESCRIPTION_START.matcher(withoutMarker);
        return descriptionStart.find() && descriptionStart.start() > 0
                ? trimToNull(withoutMarker.substring(0, descriptionStart.start()))
                : null;
    }

    private int firstSeparatorIndex(String line) {
        int colon = line.indexOf(':');
        int pipe = line.indexOf('|');
        if (colon < 0) {
            return pipe;
        }
        return pipe < 0 ? colon : Math.min(colon, pipe);
    }

    private Integer extractQuantityAnchor(String sourceText) {
        Matcher labelled = LABELLED_QUANTITY.matcher(sourceText);
        Integer lastLabelled = null;
        while (labelled.find()) {
            lastLabelled = Integer.parseInt(labelled.group(1));
        }
        if (lastLabelled != null) {
            return lastLabelled;
        }
        String firstLine = sourceText.lines().findFirst().orElse("");
        Matcher trailing = NUMBERED_RECORD_WITH_TRAILING_QUANTITY.matcher(firstLine);
        return trailing.matches() ? Integer.parseInt(trailing.group(1)) : null;
    }

    private boolean explicitProductCodeAbsent(String sourceText) {
        Matcher matcher = CODE_LABEL.matcher(sourceText);
        while (matcher.find()) {
            String value = normalize(matcher.group(1)).trim();
            if (!value.equals("-")
                    && !value.equals("yok")
                    && !value.contains("belgede belirtilmemis")
                    && !value.contains("belirtilmemis")
                    && !value.contains("bulunmuyor")) {
                return false;
            }
        }
        return true;
    }

    private String extractProductCodeAnchor(String sourceText) {
        Matcher matcher = CODE_LABEL.matcher(sourceText);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            String normalized = normalize(value);
            if (!normalized.equals("-")
                    && !normalized.equals("yok")
                    && !normalized.contains("belgede belirtilmemis")
                    && !normalized.contains("belirtilmemis")
                    && !normalized.contains("bulunmuyor")) {
                return value;
            }
        }
        return null;
    }

    private String joinLines(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end)).trim();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i');
    }

    private String trimToNull(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private record NumberedCandidate(int lineIndex, int identifier) {
    }

    private record RecordStart(int lineIndex, StartKind kind, Integer identifier) {
    }
}

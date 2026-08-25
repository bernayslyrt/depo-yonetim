package com.depo.bulkimport.service;

import java.util.List;

/**
 * One logically complete fragment sent to Ollama.
 *
 * <p>The metadata is intentionally transient: it is used for prompt context,
 * logging and retry diagnostics and is never persisted.</p>
 */
record DocumentChunk(
        String sourceDocument,
        String sourceType,
        String worksheetName,
        int chunkIndex,
        Integer startRow,
        Integer endRow,
        int sourceRowCount,
        int candidateRecordCount,
        boolean explicitProductCodeFieldAbsent,
        String content,
        List<String> sourceProductNameCandidates,
        Integer pageNumber,
        int logicalLineCount,
        int sourceCharacterCount,
        List<Integer> sourceRecordIdentifiers,
        int approximateItemMarkerCount,
        PdfChunkMetadata pdfMetadata,
        ExcelChunkMetadata excelMetadata) {

    DocumentChunk {
        sourceProductNameCandidates = sourceProductNameCandidates == null
                ? List.of()
                : List.copyOf(sourceProductNameCandidates);
        sourceRecordIdentifiers = sourceRecordIdentifiers == null
                ? List.of()
                : List.copyOf(sourceRecordIdentifiers);
        if (excelMetadata != null) {
            excelMetadata = new ExcelChunkMetadata(
                    excelMetadata.headerContext(),
                    excelMetadata.explicitProductCodeFieldAbsent(),
                    identifyExcelRecords(worksheetName, excelMetadata.sourceRecords()),
                    identifyExcelRecords(worksheetName, excelMetadata.allSourceRows()));
        }
    }

    private static List<ExcelChunkMetadata.SourceRecord> identifyExcelRecords(
            String worksheetName,
            List<ExcelChunkMetadata.SourceRecord> records) {
        return records.stream()
                .map(record -> record.withSourceIdentity(
                        "xlsx:" + worksheetName + ":row:" + record.sourceRow()))
                .toList();
    }

    DocumentChunk(
            String sourceDocument,
            String sourceType,
            String worksheetName,
            int chunkIndex,
            Integer startRow,
            Integer endRow,
            int sourceRowCount,
            int candidateRecordCount,
            boolean explicitProductCodeFieldAbsent,
            String content) {
        this(sourceDocument, sourceType, worksheetName, chunkIndex, startRow, endRow,
                sourceRowCount, candidateRecordCount, explicitProductCodeFieldAbsent,
                content, List.of(), null, 0, content == null ? 0 : content.length(), List.of(), 0,
                null, null);
    }

    DocumentChunk(
            String sourceDocument,
            String sourceType,
            String worksheetName,
            int chunkIndex,
            Integer startRow,
            Integer endRow,
            int sourceRowCount,
            int candidateRecordCount,
            boolean explicitProductCodeFieldAbsent,
            String content,
            List<String> sourceProductNameCandidates) {
        this(sourceDocument, sourceType, worksheetName, chunkIndex, startRow, endRow,
                sourceRowCount, candidateRecordCount, explicitProductCodeFieldAbsent,
                content, sourceProductNameCandidates, null, 0,
                content == null ? 0 : content.length(), List.of(), 0, null, null);
    }

    DocumentChunk(
            String sourceDocument,
            String sourceType,
            String worksheetName,
            int chunkIndex,
            Integer startRow,
            Integer endRow,
            String content) {
        this(
                sourceDocument,
                sourceType,
                worksheetName,
                chunkIndex,
                startRow,
                endRow,
                startRow == null || endRow == null ? 0 : endRow - startRow + 1,
                0,
                false,
                content,
                List.of(),
                null,
                0,
                content == null ? 0 : content.length(),
                List.of(),
                0,
                null,
                null);
    }

    DocumentChunk(
            String sourceDocument,
            String sourceType,
            String worksheetName,
            int chunkIndex,
            Integer startRow,
            Integer endRow,
            int sourceRowCount,
            String content) {
        this(
                sourceDocument,
                sourceType,
                worksheetName,
                chunkIndex,
                startRow,
                endRow,
                sourceRowCount,
                0,
                false,
                content,
                List.of(),
                null,
                0,
                content == null ? 0 : content.length(),
                List.of(),
                0,
                null,
                null);
    }

    static DocumentChunk pdf(
            String sourceDocument,
            int pageNumber,
            int chunkIndex,
            int logicalLineCount,
            List<Integer> sourceRecordIdentifiers,
            String sourceText) {
        return pdf(
                sourceDocument,
                pageNumber,
                chunkIndex,
                logicalLineCount,
                sourceRecordIdentifiers,
                false,
                0,
                sourceText);
    }

    static DocumentChunk pdf(
            String sourceDocument,
            int pageNumber,
            int chunkIndex,
            int logicalLineCount,
            List<Integer> sourceRecordIdentifiers,
            boolean explicitProductCodeFieldAbsent,
            int approximateItemMarkerCount,
            String sourceText) {
        String content = "PDF PAGE SOURCE TEXT TO ANALYZE:\n" + sourceText;
        return new DocumentChunk(
                sourceDocument,
                "PDF",
                null,
                chunkIndex,
                null,
                null,
                0,
                sourceRecordIdentifiers.size(),
                explicitProductCodeFieldAbsent,
                content,
                List.of(),
                pageNumber,
                logicalLineCount,
                sourceText == null ? 0 : sourceText.length(),
                sourceRecordIdentifiers,
                approximateItemMarkerCount,
                null,
                null);
    }

    static DocumentChunk pdfRecords(
            String sourceDocument,
            int pageNumber,
            int chunkIndex,
            String headerContext,
            List<PdfRecordSegmenter.LogicalRecord> sourceRecords,
            PdfRecordSegmenter.Confidence confidence,
            boolean difficultLayout) {
        List<Integer> identifiers = sourceRecords.stream()
                .map(PdfRecordSegmenter.LogicalRecord::sourceRecordId)
                .toList();
        String sourceText = sourceRecords.stream()
                .map(PdfRecordSegmenter.LogicalRecord::sourceText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        StringBuilder content = new StringBuilder();
        if (headerContext != null && !headerContext.isBlank()) {
            content.append("PDF PAGE HEADER/CONTEXT (REFERENCE ONLY):\n")
                    .append(headerContext.trim())
                    .append("\n\n");
        }
        content.append("PDF LOGICAL RECORDS TO ANALYZE:\n");
        for (PdfRecordSegmenter.LogicalRecord record : sourceRecords) {
            content.append("\nRECORD ").append(record.sourceRecordId()).append(":\n");
            if (record.quantityAnchor() != null) {
                content.append("SOURCE QUANTITY EVIDENCE: ")
                        .append(record.quantityAnchor()).append('\n');
            }
            content.append("SOURCE PRODUCT CODE EVIDENCE: ")
                    .append(record.productCodeAnchor() == null
                            ? "NONE (no explicit source code)"
                            : record.productCodeAnchor())
                    .append('\n');
            content.append("SOURCE RECORD TEXT:\n")
                    .append(record.sourceText()).append('\n');
        }

        boolean everyRecordHasNoExplicitCode = sourceRecords.stream()
                .allMatch(PdfRecordSegmenter.LogicalRecord::explicitProductCodeAbsent);
        int exactCandidateCount = confidence == PdfRecordSegmenter.Confidence.RELIABLE
                ? sourceRecords.size()
                : 0;
        return new DocumentChunk(
                sourceDocument,
                "PDF",
                null,
                chunkIndex,
                null,
                null,
                0,
                exactCandidateCount,
                everyRecordHasNoExplicitCode,
                content.toString().trim(),
                List.of(),
                pageNumber,
                (int) sourceText.lines().filter(line -> !line.isBlank()).count(),
                sourceText.length(),
                identifiers,
                sourceRecords.size(),
                new PdfChunkMetadata(confidence, difficultLayout, headerContext, sourceRecords),
                null);
    }

    static DocumentChunk excelRecords(
            String sourceDocument,
            String worksheetName,
            int chunkIndex,
            String headerContext,
            boolean explicitProductCodeFieldAbsent,
            List<ExcelChunkMetadata.SourceRecord> sourceRecords) {
        List<ExcelChunkMetadata.SourceRecord> identifiedRecords =
                identifyExcelRecords(worksheetName, sourceRecords);
        StringBuilder content = new StringBuilder()
                .append("POTENTIAL HEADER/CONTEXT ROWS (REFERENCE ONLY; "
                        + "extract records only from DATA ROWS):\n")
                .append(headerContext)
                .append("\n\nDATA ROWS TO ANALYZE:\n");
        identifiedRecords.forEach(record -> content
                .append("SOURCE RECORD ID: ").append(record.sourceIdentity()).append('\n')
                .append(record.sourceText()).append('\n'));
        int startRow = identifiedRecords.get(0).sourceRow();
        int endRow = identifiedRecords.get(identifiedRecords.size() - 1).sourceRow();
        List<String> sourceNameCandidates = identifiedRecords.stream()
                .map(ExcelChunkMetadata.SourceRecord::productNameCandidate)
                .allMatch(candidate -> candidate != null && !candidate.isBlank())
                ? identifiedRecords.stream()
                        .map(ExcelChunkMetadata.SourceRecord::productNameCandidate)
                        .toList()
                : List.of();
        return new DocumentChunk(
                sourceDocument,
                "XLSX",
                worksheetName,
                chunkIndex,
                startRow,
                endRow,
                identifiedRecords.size(),
                identifiedRecords.size(),
                explicitProductCodeFieldAbsent,
                content.toString().trim(),
                sourceNameCandidates,
                null,
                0,
                content.length(),
                identifiedRecords.stream().map(ExcelChunkMetadata.SourceRecord::sourceRow).toList(),
                0,
                null,
                new ExcelChunkMetadata(headerContext, explicitProductCodeFieldAbsent, identifiedRecords));
    }

    String description() {
        StringBuilder description = new StringBuilder()
                .append("document=").append(sourceDocument)
                .append(", type=").append(sourceType)
                .append(", chunk=").append(chunkIndex);
        if (worksheetName != null) {
            description.append(", worksheet=").append(worksheetName);
        }
        if (pageNumber != null) {
            description.append(", page=").append(pageNumber);
        }
        if (startRow != null && endRow != null) {
            description.append(", rows=").append(startRow).append('-').append(endRow);
        }
        if (sourceRowCount > 0) {
            description.append(", sourceRowCount=").append(sourceRowCount);
        }
        if (candidateRecordCount > 0) {
            description.append(", candidateRecordCount=").append(candidateRecordCount);
        }
        if (approximateItemMarkerCount > 0) {
            description.append(", approximateItemMarkers=").append(approximateItemMarkerCount);
        }
        if (pdfMetadata != null) {
            description.append(", recordBoundaryConfidence=")
                    .append(pdfMetadata.boundaryConfidence());
        }
        if (excelMetadata != null) {
            description.append(", reliableExcelRecords=")
                    .append(excelMetadata.sourceRecords().size());
        }
        return description.toString();
    }

    String toPromptFragment() {
        StringBuilder prompt = new StringBuilder()
                .append("SOURCE DOCUMENT: ").append(sourceDocument).append('\n')
                .append("SOURCE TYPE: ").append(sourceType).append('\n');
        if (worksheetName != null) {
            prompt.append("WORKSHEET: ").append(worksheetName).append('\n');
        }
        if (pageNumber != null) {
            prompt.append("PAGE: ").append(pageNumber).append('\n');
        }
        prompt.append("CHUNK: ").append(chunkIndex).append('\n');
        if (pdfMetadata != null) {
            prompt.append("SOURCE LOGICAL RECORD COUNT: ")
                    .append(pdfMetadata.sourceRecords().size()).append('\n')
                    .append("RECORD BOUNDARY CONFIDENCE: ")
                    .append(pdfMetadata.boundaryConfidence()).append('\n');
        }
        if (excelMetadata != null) {
            prompt.append("SOURCE LOGICAL RECORD COUNT: ")
                    .append(excelMetadata.sourceRecords().size()).append('\n');
        }
        if (startRow != null && endRow != null) {
            prompt.append("SOURCE ROWS: ").append(startRow).append('-').append(endRow).append('\n');
        }
        return prompt.append("\n--- FRAGMENT START ---\n")
                .append(content)
                .append("\n--- FRAGMENT END ---")
                .toString();
    }
}

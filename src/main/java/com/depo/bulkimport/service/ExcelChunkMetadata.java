package com.depo.bulkimport.service;

import java.util.List;

record ExcelChunkMetadata(
        String headerContext,
        boolean explicitProductCodeFieldAbsent,
        List<SourceRecord> sourceRecords,
        List<SourceRecord> allSourceRows) {

    ExcelChunkMetadata {
        sourceRecords = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
        allSourceRows = allSourceRows == null ? List.of() : List.copyOf(allSourceRows);
    }

    ExcelChunkMetadata(
            String headerContext,
            boolean explicitProductCodeFieldAbsent,
            List<SourceRecord> sourceRecords) {
        this(headerContext, explicitProductCodeFieldAbsent, sourceRecords, sourceRecords);
    }

    record SourceRecord(
            String sourceIdentity,
            int sourceRow,
            String sourceText,
            String productNameCandidate,
            Integer quantityCandidate) {

        SourceRecord(
                int sourceRow,
                String sourceText,
                String productNameCandidate,
                Integer quantityCandidate) {
            this(null, sourceRow, sourceText, productNameCandidate, quantityCandidate);
        }

        SourceRecord(int sourceRow, String sourceText, String productNameCandidate) {
            this(null, sourceRow, sourceText, productNameCandidate, null);
        }

        SourceRecord withSourceIdentity(String identity) {
            if (sourceIdentity != null && !sourceIdentity.isBlank()) {
                return this;
            }
            return new SourceRecord(
                    identity, sourceRow, sourceText, productNameCandidate, quantityCandidate);
        }
    }
}

package com.depo.bulkimport.service;

import java.util.List;

/** Transient source alignment evidence for a record-aware PDF micro-chunk. */
record PdfChunkMetadata(
        PdfRecordSegmenter.Confidence boundaryConfidence,
        boolean difficultLayout,
        String headerContext,
        List<PdfRecordSegmenter.LogicalRecord> sourceRecords) {

    PdfChunkMetadata {
        sourceRecords = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
    }
}

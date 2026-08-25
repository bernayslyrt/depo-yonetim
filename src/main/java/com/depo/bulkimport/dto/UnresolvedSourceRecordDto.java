package com.depo.bulkimport.dto;

import lombok.Builder;
import lombok.Value;

/** A reliably located source record that could not be parsed after recovery. */
@Value
@Builder
public class UnresolvedSourceRecordDto {
    String id;
    String sourceType;
    String worksheetName;
    Integer sourceRowStart;
    Integer sourceRowEnd;
    Integer pageNumber;
    Integer sourceRecordStart;
    Integer sourceRecordEnd;
    int insertionIndex;
    String sourceText;
    String reason;
}

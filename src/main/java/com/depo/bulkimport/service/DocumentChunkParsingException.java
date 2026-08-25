package com.depo.bulkimport.service;

/**
 * Raised when a document fragment still cannot be parsed after bounded retries.
 * Failing the complete preview request prevents a partial import from looking
 * like a successful, complete result.
 */
public class DocumentChunkParsingException extends RuntimeException {

    public DocumentChunkParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}

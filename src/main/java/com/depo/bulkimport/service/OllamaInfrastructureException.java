package com.depo.bulkimport.service;

/** An Ollama transport/API outage which must fail the complete document. */
public class OllamaInfrastructureException extends RuntimeException {
    public OllamaInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}

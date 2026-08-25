package com.depo.bulkimport.service;

/** Signals explicit user cancellation of one bulk-import parsing job. */
public class BulkImportCancelledException extends RuntimeException {

    public BulkImportCancelledException(String jobId) {
        super("Toplu içe aktarım kullanıcı tarafından iptal edildi: " + jobId);
    }
}

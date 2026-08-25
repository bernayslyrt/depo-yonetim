package com.depo.bulkimport.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns cancellation state for active bulk-import parsing jobs only. */
@Service
@Slf4j
public class BulkImportJobService {

    private static final long STALE_JOB_MILLIS = Duration.ofHours(1).toMillis();
    private final Map<String, BulkImportCancellationToken> jobs = new ConcurrentHashMap<>();

    public String newJobId() {
        return UUID.randomUUID().toString();
    }

    public BulkImportCancellationToken start(String requestedJobId) {
        purgeStaleJobs();
        String jobId = requireJobId(requestedJobId);
        BulkImportCancellationToken token = jobs.computeIfAbsent(
                jobId, ignored -> new BulkImportCancellationToken(jobId, true));
        if (!token.markStarted() && !token.isCancelled()) {
            throw new IllegalArgumentException("Bu toplu içe aktarım işi zaten başlatılmış.");
        }
        return token;
    }

    public CancelResult cancel(String requestedJobId) {
        purgeStaleJobs();
        String jobId = requireJobId(requestedJobId);
        BulkImportCancellationToken token = jobs.computeIfAbsent(
                jobId, ignored -> new BulkImportCancellationToken(jobId, true));
        boolean newlyCancelled = token.cancel();
        return new CancelResult(newlyCancelled, token.previewId());
    }

    public void bindPreview(BulkImportCancellationToken token, String previewId) {
        token.throwIfCancelled();
        token.bindPreviewId(previewId);
        token.throwIfCancelled();
    }

    /** Cancels all tracked work and returns any preview that must be invalidated. */
    public String fail(BulkImportCancellationToken token) {
        token.cancelAfterFailure();
        return token.previewId();
    }

    public void finish(BulkImportCancellationToken token) {
        jobs.remove(token.jobId(), token);
        if (token.isCancelled()) {
            log.info("BULK_IMPORT_DIAGNOSTICS|stage=CANCELLED|jobId={}|elapsedMs={}|"
                            + "requestsStarted={}|retries={}|splits={}|tasksCancelled={}",
                    token.jobId(), token.elapsedMillis(), token.requestsStarted(),
                    token.retriesStarted(), token.recursiveSplits(), token.tasksCancelled());
        }
    }

    private String requireJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Toplu içe aktarım iş kimliği eksik.");
        }
        try {
            return UUID.fromString(jobId).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Geçersiz toplu içe aktarım iş kimliği.");
        }
    }

    private void purgeStaleJobs() {
        jobs.entrySet().removeIf(entry -> !entry.getValue().hasStarted()
                && entry.getValue().elapsedMillis() > STALE_JOB_MILLIS);
    }

    public record CancelResult(boolean newlyCancelled, String previewId) {
    }
}

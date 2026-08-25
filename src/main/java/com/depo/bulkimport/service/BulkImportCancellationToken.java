package com.depo.bulkimport.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Import-scoped cancellation state shared by the request and its chunk workers. */
public final class BulkImportCancellationToken {

    private final String jobId;
    private final long startedNanos = System.nanoTime();
    private final boolean cancellable;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger requestsStarted = new AtomicInteger();
    private final AtomicInteger retriesStarted = new AtomicInteger();
    private final AtomicInteger recursiveSplits = new AtomicInteger();
    private final AtomicInteger tasksCancelled = new AtomicInteger();
    private final AtomicReference<RuntimeException> fatalFailure = new AtomicReference<>();
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
    private volatile String previewId;

    BulkImportCancellationToken(String jobId, boolean cancellable) {
        this.jobId = jobId;
        this.cancellable = cancellable;
    }

    static BulkImportCancellationToken none() {
        BulkImportCancellationToken token = new BulkImportCancellationToken("internal", false);
        token.started.set(true);
        return token;
    }

    boolean markStarted() {
        return started.compareAndSet(false, true);
    }

    public void throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new BulkImportCancelledException(jobId);
        }
    }

    void track(Future<?> task) {
        tasks.add(task);
        if (cancelled.get() && !task.isDone() && task.cancel(true)) {
            tasksCancelled.incrementAndGet();
        }
    }

    void recordRequestStarted(boolean retry) {
        requestsStarted.incrementAndGet();
        if (retry) {
            retriesStarted.incrementAndGet();
        }
    }

    void recordRecursiveSplit() {
        recursiveSplits.incrementAndGet();
    }

    boolean cancel() {
        if (!cancellable) {
            return false;
        }
        return cancelTrackedTasks();
    }

    /**
     * Stops all work after a fatal import failure, including internal/non-user-cancellable jobs.
     */
    boolean cancelAfterFailure() {
        return cancelTrackedTasks();
    }

    void recordFatalFailure(RuntimeException failure) {
        fatalFailure.compareAndSet(null, failure);
        cancelled.set(true);
    }

    RuntimeException fatalFailure() {
        return fatalFailure.get();
    }

    private boolean cancelTrackedTasks() {
        boolean newlyCancelled = cancelled.compareAndSet(false, true);
        for (Future<?> task : tasks) {
            if (!task.isDone() && task.cancel(true)) {
                tasksCancelled.incrementAndGet();
            }
        }
        return newlyCancelled;
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean hasStarted() {
        return started.get();
    }

    String jobId() {
        return jobId;
    }

    long elapsedMillis() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    int requestsStarted() {
        return requestsStarted.get();
    }

    int retriesStarted() {
        return retriesStarted.get();
    }

    int recursiveSplits() {
        return recursiveSplits.get();
    }

    int tasksCancelled() {
        return tasksCancelled.get();
    }

    String previewId() {
        return previewId;
    }

    void bindPreviewId(String previewId) {
        this.previewId = previewId;
    }
}

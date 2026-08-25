package com.depo.bulkimport.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BulkImportCancellationTokenTest {

    @Test
    void cancelInterruptsRunningTaskAndRemovesQueuedTaskForOnlyThisImport() throws Exception {
        BulkImportCancellationToken token = new BulkImportCancellationToken(
                "11111111-1111-1111-1111-111111111111", true);
        token.markStarted();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Future<?> runningTask = executor.submit(() -> {
            running.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        Future<?> queuedTask = executor.submit(() -> { });
        token.track(runningTask);
        token.track(queuedTask);

        assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
        token.cancel();

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(runningTask.isCancelled()).isTrue();
        assertThat(queuedTask.isCancelled()).isTrue();
        assertThat(token.tasksCancelled()).isEqualTo(2);
        executor.shutdownNow();
    }
}

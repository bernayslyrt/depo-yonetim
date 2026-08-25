package com.depo.service;

import com.depo.config.DatabaseBackupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseBackupServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void deletesOnlyManagedBackupFilesOlderThanRetentionPeriod() throws Exception {
        DatabaseBackupProperties properties = validProperties();
        properties.setRetentionDays(14);
        DatabaseBackupService service = new DatabaseBackupService(properties, Runnable::run);

        Path expiredBackup = createFile("backup_20260701_0300.sql", 15);
        Path expiredPartial = createFile("backup_20260701_0300.sql.part", 15);
        Path recentBackup = createFile("backup_20260814_0300.sql", 1);
        Path unrelatedFile = createFile("important.sql", 30);

        service.deleteExpiredBackups(tempDirectory);

        assertFalse(Files.exists(expiredBackup));
        assertFalse(Files.exists(expiredPartial));
        assertTrue(Files.exists(recentBackup));
        assertTrue(Files.exists(unrelatedFile));
    }

    @Test
    void scheduledMethodOnlyDispatchesOneBackgroundJobAtATime() {
        DatabaseBackupProperties properties = validProperties();
        AtomicReference<Runnable> submittedJob = new AtomicReference<>();
        AtomicInteger submissionCount = new AtomicInteger();
        Executor capturingExecutor = task -> {
            submissionCount.incrementAndGet();
            submittedJob.set(task);
        };
        DatabaseBackupService service = new DatabaseBackupService(properties, capturingExecutor);

        service.scheduleNightlyBackup();
        service.scheduleNightlyBackup();

        assertEquals(1, submissionCount.get());
        assertNotNull(submittedJob.get());
    }

    @Test
    void disabledBackupDoesNotSubmitBackgroundWork() {
        DatabaseBackupProperties properties = validProperties();
        properties.setEnabled(false);
        AtomicInteger submissionCount = new AtomicInteger();
        DatabaseBackupService service = new DatabaseBackupService(
                properties,
                task -> submissionCount.incrementAndGet());

        service.scheduleNightlyBackup();

        assertEquals(0, submissionCount.get());
    }

    private DatabaseBackupProperties validProperties() {
        DatabaseBackupProperties properties = new DatabaseBackupProperties();
        properties.setEnabled(true);
        properties.setMysqldumpPath("mysqldump");
        properties.setHost("localhost");
        properties.setPort(3306);
        properties.setUsername("test-user");
        properties.setPassword("test-password");
        properties.setDatabaseName("test-database");
        properties.setDirectory(tempDirectory);
        return properties;
    }

    private Path createFile(String fileName, long ageInDays) throws Exception {
        Path file = Files.writeString(tempDirectory.resolve(fileName), "test");
        Files.setLastModifiedTime(
                file,
                FileTime.from(Instant.now().minus(ageInDays, ChronoUnit.DAYS)));
        return file;
    }
}

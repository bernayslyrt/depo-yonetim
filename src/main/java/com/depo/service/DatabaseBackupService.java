package com.depo.service;

import com.depo.config.DatabaseBackupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final int MAX_ERROR_LOG_LENGTH = 2_000;

    private final DatabaseBackupProperties properties;
    private final Executor backupExecutor;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);

    public DatabaseBackupService(
            DatabaseBackupProperties properties,
            @Qualifier("databaseBackupExecutor") Executor backupExecutor) {
        this.properties = properties;
        this.backupExecutor = backupExecutor;
    }

    /**
     * Her gece 03:00'te yalnızca işi özel executor'a aktarır. mysqldump bu
     * scheduler thread'i veya uygulamanın istek thread'lerini bloke etmez.
     */
    @Scheduled(
            cron = "${database.backup.cron:0 0 3 * * *}",
            zone = "${database.backup.zone:Europe/Istanbul}")
    public void scheduleNightlyBackup() {
        if (!properties.isEnabled()) {
            log.debug("Otomatik veritabanı yedekleme devre dışı.");
            return;
        }

        if (!backupRunning.compareAndSet(false, true)) {
            log.warn("Önceki veritabanı yedekleme işlemi devam ettiği için yeni çalışma atlandı.");
            return;
        }

        try {
            backupExecutor.execute(this::runBackupSafely);
        } catch (RejectedExecutionException ex) {
            backupRunning.set(false);
            log.error("Veritabanı yedekleme işi arka plan executor'ına aktarılamadı.", ex);
        } catch (RuntimeException ex) {
            backupRunning.set(false);
            log.error("Veritabanı yedekleme işi başlatılamadı.", ex);
        }
    }

    private void runBackupSafely() {
        try {
            createBackup();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Veritabanı yedekleme işlemi kesintiye uğradı.", ex);
        } catch (Exception ex) {
            log.error("Veritabanı yedekleme işlemi başarısız oldu: {}", ex.getMessage(), ex);
        } finally {
            backupRunning.set(false);
        }
    }

    private void createBackup() throws IOException, InterruptedException {
        ConnectionTarget connectionTarget = validateConfiguration();

        Path backupDirectory = properties.getDirectory().toAbsolutePath().normalize();
        Files.createDirectories(backupDirectory);
        deleteExpiredBackups(backupDirectory);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(properties.getZone()));
        String timestamp = FILE_TIMESTAMP.format(now);
        Path finalFile = backupDirectory.resolve("backup_" + timestamp + ".sql");
        Path partialFile = backupDirectory.resolve("backup_" + timestamp + ".sql.part");
        Path errorFile = backupDirectory.resolve("backup_" + timestamp + ".err");

        if (Files.exists(finalFile)) {
            throw new IOException("Aynı zaman damgasına ait yedek zaten mevcut: " + finalFile);
        }
        Files.deleteIfExists(partialFile);
        Files.deleteIfExists(errorFile);

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(connectionTarget));
            processBuilder.environment().put("MYSQL_PWD", properties.getPassword());
            processBuilder.redirectOutput(partialFile.toFile());
            processBuilder.redirectError(errorFile.toFile());

            log.info("Veritabanı yedekleme başlatıldı. Veritabanı: {}, hedef: {}",
                    connectionTarget.databaseName(), finalFile);

            process = processBuilder.start();
            boolean finished = process.waitFor(
                    properties.getProcessTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);

            if (!finished) {
                terminateProcess(process);
                throw new IOException("mysqldump zaman aşımına uğradı. Süre: "
                        + properties.getProcessTimeout());
            }

            int exitCode = process.exitValue();
            String errorOutput = readErrorOutput(errorFile);
            if (exitCode != 0) {
                throw new IOException("mysqldump çıkış kodu " + exitCode
                        + formatProcessError(errorOutput));
            }
            if (!Files.exists(partialFile) || Files.size(partialFile) == 0) {
                throw new IOException("mysqldump boş bir yedek dosyası üretti.");
            }

            promoteCompletedBackup(partialFile, finalFile);
            log.info("Veritabanı yedekleme başarıyla tamamlandı. Dosya: {}, boyut: {} bayt",
                    finalFile, Files.size(finalFile));

            if (!errorOutput.isBlank()) {
                log.warn("mysqldump uyarı çıktısı: {}", abbreviate(errorOutput));
            }
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcess(process);
            }
            Files.deleteIfExists(partialFile);
            Files.deleteIfExists(errorFile);
        }
    }

    private List<String> buildCommand(ConnectionTarget target) {
        List<String> command = new ArrayList<>();
        command.add(properties.getMysqldumpPath());
        command.add("--host=" + target.host());
        command.add("--port=" + target.port());
        command.add("--user=" + properties.getUsername());
        command.add("--protocol=TCP");
        String sslMode = properties.getSslMode().toUpperCase(Locale.ROOT);
        command.add("DISABLED".equals(sslMode) ? "--skip-ssl" : "--ssl");
        command.add("--single-transaction");
        command.add("--quick");
        command.add("--skip-lock-tables");
        command.add("--default-character-set=utf8mb4");
        command.add(target.databaseName());
        return command;
    }

    private ConnectionTarget validateConfiguration() {
        requireText(properties.getMysqldumpPath(), "database.backup.mysqldump-path");
        requireText(properties.getUsername(), "database.backup.username");
        requireText(properties.getPassword(), "database.backup.password");
        requireText(properties.getSslMode(), "database.backup.ssl-mode");
        requireText(properties.getZone(), "database.backup.zone");

        ConnectionTarget target = resolveConnectionTarget();
        String sslMode = properties.getSslMode().toUpperCase(Locale.ROOT);
        if (!List.of("DISABLED", "PREFERRED", "REQUIRED").contains(sslMode)) {
            throw new IllegalStateException(
                    "database.backup.ssl-mode DISABLED, PREFERRED veya REQUIRED olmalıdır.");
        }
        requireText(target.host(), "database.backup.host");
        requireText(target.databaseName(), "database.backup.database-name");
        if (target.port() < 1 || target.port() > 65_535) {
            throw new IllegalStateException("database.backup.port 1-65535 arasında olmalıdır.");
        }
        if (properties.getRetentionDays() < 1) {
            throw new IllegalStateException("database.backup.retention-days en az 1 olmalıdır.");
        }
        if (properties.getProcessTimeout() == null || properties.getProcessTimeout().isNegative()
                || properties.getProcessTimeout().isZero()) {
            throw new IllegalStateException("database.backup.process-timeout pozitif olmalıdır.");
        }
        return target;
    }

    private ConnectionTarget resolveConnectionTarget() {
        String host = properties.getHost();
        int port = properties.getPort();
        String databaseName = properties.getDatabaseName();

        if (hasText(host) && port > 0 && hasText(databaseName)) {
            return new ConnectionTarget(host, port, databaseName);
        }

        requireText(properties.getJdbcUrl(), "database.backup.jdbc-url");
        try {
            String rawUrl = properties.getJdbcUrl().startsWith("jdbc:")
                    ? properties.getJdbcUrl().substring("jdbc:".length())
                    : properties.getJdbcUrl();
            URI uri = URI.create(rawUrl);
            String urlDatabase = uri.getPath() == null
                    ? null
                    : uri.getPath().replaceFirst("^/", "");

            return new ConnectionTarget(
                    hasText(host) ? host : uri.getHost(),
                    port > 0 ? port : (uri.getPort() > 0 ? uri.getPort() : 3306),
                    hasText(databaseName) ? databaseName : urlDatabase);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "MySQL bağlantı bilgileri JDBC URL'den okunamadı. "
                            + "database.backup.host, port ve database-name değerlerini açıkça yapılandırın.",
                    ex);
        }
    }

    private void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " yapılandırılmalıdır.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    void deleteExpiredBackups(Path backupDirectory) throws IOException {
        Instant cutoff = Instant.now().minusSeconds(properties.getRetentionDays() * 86_400L);
        int deletedCount = 0;

        try (Stream<Path> paths = Files.list(backupDirectory)) {
            List<Path> expiredFiles = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isManagedBackupFile)
                    .filter(path -> isOlderThan(path, cutoff))
                    .toList();

            for (Path expiredFile : expiredFiles) {
                try {
                    if (Files.deleteIfExists(expiredFile)) {
                        deletedCount++;
                        log.info("Saklama süresi dolan yedek silindi: {}", expiredFile);
                    }
                } catch (IOException ex) {
                    log.error("Eski yedek dosyası silinemedi: {}", expiredFile, ex);
                }
            }
        }

        if (deletedCount > 0) {
            log.info("Yedek saklama politikası tamamlandı. Silinen dosya sayısı: {}", deletedCount);
        }
    }

    private boolean isManagedBackupFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("backup_")
                && (fileName.endsWith(".sql")
                || fileName.endsWith(".sql.part")
                || fileName.endsWith(".err"));
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            return lastModified.toInstant().isBefore(cutoff);
        } catch (IOException ex) {
            log.error("Yedek dosyasının tarihi okunamadı: {}", path, ex);
            return false;
        }
    }

    private void promoteCompletedBackup(Path partialFile, Path finalFile) throws IOException {
        try {
            Files.move(partialFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(partialFile, finalFile);
        }
    }

    private void terminateProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private String readErrorOutput(Path errorFile) throws IOException {
        if (!Files.exists(errorFile)) {
            return "";
        }
        return Files.readString(errorFile, StandardCharsets.UTF_8).trim();
    }

    private String formatProcessError(String errorOutput) {
        return errorOutput.isBlank() ? "." : ": " + abbreviate(errorOutput);
    }

    private String abbreviate(String value) {
        if (value.length() <= MAX_ERROR_LOG_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LOG_LENGTH) + "…";
    }

    private record ConnectionTarget(String host, int port, String databaseName) {
    }
}

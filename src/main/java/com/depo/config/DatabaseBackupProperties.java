package com.depo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "database.backup")
public class DatabaseBackupProperties {

    private boolean enabled = true;
    private String mysqldumpPath = "mysqldump";
    private String jdbcUrl;
    private String host;
    private int port;
    private String username;
    private String password;
    private String databaseName;
    private String sslMode = "PREFERRED";
    private Path directory = Path.of("backups");
    private int retentionDays = 14;
    private Duration processTimeout = Duration.ofMinutes(30);
    private String zone = "Europe/Istanbul";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMysqldumpPath() {
        return mysqldumpPath;
    }

    public void setMysqldumpPath(String mysqldumpPath) {
        this.mysqldumpPath = mysqldumpPath;
    }

    public String getHost() {
        return host;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSslMode() {
        return sslMode;
    }

    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Duration getProcessTimeout() {
        return processTimeout;
    }

    public void setProcessTimeout(Duration processTimeout) {
        this.processTimeout = processTimeout;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }
}

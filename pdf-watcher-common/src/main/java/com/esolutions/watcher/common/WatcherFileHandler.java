package com.esolutions.watcher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class WatcherFileHandler {

    private static final Logger log = LoggerFactory.getLogger(WatcherFileHandler.class);

    private final Path processedDir;
    private final Path failedDir;
    private final Path emailedDir;

    public WatcherFileHandler(Path processedDir, Path failedDir, Path emailedDir) {
        this.processedDir = processedDir;
        this.failedDir = failedDir;
        this.emailedDir = emailedDir;
    }

    public WatcherFileHandler(Path processedDir, Path failedDir) {
        this(processedDir, failedDir, null);
    }

    public Path processedDir() { return processedDir; }
    public Path failedDir() { return failedDir; }
    public Path emailedDir() { return emailedDir; }
    public boolean hasEmailedDir() { return emailedDir != null; }

    public void createDirectories() throws IOException {
        Files.createDirectories(processedDir);
        Files.createDirectories(failedDir);
        if (emailedDir != null) {
            Files.createDirectories(emailedDir);
        }
    }

    public void moveToProcessed(Path file) {
        safeMove(file, processedDir);
    }

    public void moveToFailed(Path file) {
        safeMove(file, failedDir);
    }

    public void moveFile(Path file, Path targetDir) {
        safeMove(file, targetDir);
    }

    public void copyToEmailed(Path file) {
        if (emailedDir == null || !Files.exists(file)) return;
        try {
            Files.createDirectories(emailedDir);
            Path target = emailedDir.resolve(file.getFileName());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Archived {} to emailed/", file.getFileName());
        } catch (IOException e) {
            log.warn("Could not copy {} to emailed/: {}", file.getFileName(), e.getMessage());
        }
    }

    private void safeMove(Path file, Path targetDir) {
        if (!Files.exists(file)) return;
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(file.getFileName());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Moved {} -> {}", file.getFileName(), targetDir.getFileName());
        } catch (IOException e) {
            log.warn("Could not move {} to {}: {}", file.getFileName(), targetDir, e.getMessage());
        }
    }
}

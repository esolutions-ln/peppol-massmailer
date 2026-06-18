package com.esolutions.watcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

public class FolderWatcher {

    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);

    private final WatcherConfig config;
    private final ApiClient api;
    private final LedgerStore ledger;
    private final ObjectMapper json;

    private volatile boolean running = true;

    public FolderWatcher(WatcherConfig config, ApiClient api, LedgerStore ledger) {
        this.config = config;
        this.api = api;
        this.ledger = ledger;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public void stop() {
        running = false;
    }

    public void start() throws IOException, InterruptedException {
        Path inbox = config.inboxDirectory();
        Files.createDirectories(inbox);
        Files.createDirectories(config.emailedDirectory());
        Files.createDirectories(config.failedDirectory());
        Files.createDirectories(config.ledgerFile().getParent());

        log.info("Watching {} for invoice PDFs", inbox.toAbsolutePath());

        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            inbox.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            while (running) {
                WatchKey key = ws.take();
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path filename = (Path) ev.context();
                    if (!filename.toString().endsWith(".pdf")) continue;
                    processPdf(inbox.resolve(filename));
                }
                if (!key.reset()) break;
            }
        }
    }

    private void processPdf(Path pdfPath) {
        String base = pdfPath.getFileName().toString().replace(".pdf", "");
        log.info("New PDF detected: {}", pdfPath.getFileName());

        Path sidecarPath = pdfPath.resolveSibling(base + ".json");

        if (!Files.exists(sidecarPath)) {
            log.warn("No sidecar JSON found for {} — waiting up to {}ms", base, config.sidecarWaitMs());
            sidecarPath = awaitSidecar(pdfPath, base);
        }

        if (sidecarPath == null || !Files.exists(sidecarPath)) {
            log.error("Sidecar missing for {} after timeout — moving to failed", base);
            moveFile(pdfPath, config.failedDirectory());
            return;
        }

        try {
            SidecarData sidecar = json.readValue(sidecarPath.toFile(), SidecarData.class);
            process(pdfPath, sidecarPath, sidecar);
        } catch (Exception e) {
            log.error("Failed to process {}: {}", base, e.getMessage(), e);
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
        }
    }

    private Path awaitSidecar(Path pdf, String base) {
        Path sidecar = pdf.resolveSibling(base + ".json");
        int elapsed = 0;
        int step = 200;
        int maxWait = config.sidecarWaitMs();

        while (elapsed < maxWait) {
            if (Files.exists(sidecar)) return sidecar;
            try { Thread.sleep(step); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            elapsed += step;
        }
        return Files.exists(sidecar) ? sidecar : null;
    }

    private void process(Path pdfPath, Path sidecarPath, SidecarData sidecar) {
        String invoiceNumber = sidecar.invoiceNumber(pdfPath);

        if (ledger.alreadySent(invoiceNumber)) {
            log.info("Invoice {} already sent — skipping", invoiceNumber);
            moveFile(pdfPath, config.emailedDirectory());
            moveFile(sidecarPath, config.emailedDirectory());
            return;
        }

        if (sidecar.organizationId() == null) {
            log.error("Sidecar {} missing organizationId — cannot dispatch", invoiceNumber);
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
            return;
        }

        if (sidecar.recipientEmail() == null || sidecar.recipientEmail().isBlank()) {
            log.error("Sidecar {} missing recipientEmail — cannot dispatch", invoiceNumber);
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
            return;
        }

        byte[] pdfBytes;
        try {
            pdfBytes = Files.readAllBytes(pdfPath);
        } catch (IOException e) {
            log.error("Cannot read PDF {}: {}", pdfPath.getFileName(), e.getMessage());
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
            return;
        }

        if (pdfBytes.length == 0) {
            log.warn("PDF {} is empty — moving to failed", pdfPath.getFileName());
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
            return;
        }

        try {
            var result = api.createCampaign(sidecar.organizationId(), sidecar, pdfBytes);

            if (result.success()) {
                log.info("Dispatched invoice {} as campaign {} (org={})",
                        invoiceNumber, result.campaignId(), sidecar.organizationId());
                moveFile(pdfPath, config.emailedDirectory());
                moveFile(sidecarPath, config.emailedDirectory());
                ledger.recordSent(result.campaignId(), invoiceNumber,
                        pdfPath.getFileName().toString(), sidecar.recipientEmail());
            } else {
                log.error("Failed to dispatch invoice {}: {}", invoiceNumber, result.error());
                moveFile(pdfPath, config.failedDirectory());
                moveFile(sidecarPath, config.failedDirectory());
            }
        } catch (Exception e) {
            log.error("Error dispatching invoice {}: {}", invoiceNumber, e.getMessage(), e);
            moveFile(pdfPath, config.failedDirectory());
            moveFile(sidecarPath, config.failedDirectory());
        }
    }

    private void moveFile(Path file, Path targetDir) {
        if (!Files.exists(file)) return;
        try {
            Path target = targetDir.resolve(file.getFileName());
            Files.createDirectories(targetDir);
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Moved {} → {}", file.getFileName(), targetDir.getFileName());
        } catch (IOException e) {
            log.warn("Could not move {} to {}: {}", file.getFileName(), targetDir, e.getMessage());
        }
    }
}

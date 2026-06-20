package com.esolutions.watcher;

import com.esolutions.watcher.common.PdfValidator;
import com.esolutions.watcher.common.SidecarData;
import com.esolutions.watcher.common.WatcherFileHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FolderWatcher {

    private static final Logger log = LoggerFactory.getLogger(FolderWatcher.class);

    private final WatcherConfig config;
    private final ApiClient api;
    private final LedgerStore ledger;
    private final ObjectMapper json;
    private final WatcherFileHandler fileHandler;

    private volatile boolean running = true;
    private final Set<String> dispatchedInSession = ConcurrentHashMap.newKeySet();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public FolderWatcher(WatcherConfig config, ApiClient api, LedgerStore ledger) {
        this.config = config;
        this.api = api;
        this.ledger = ledger;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule());
        this.fileHandler = new WatcherFileHandler(
                config.emailedDirectory(),
                config.failedDirectory(),
                null
        );
    }

    public void stop() {
        running = false;
    }

    public long processed() { return processedCount.get(); }
    public long failed() { return failedCount.get(); }

    public void start() throws IOException, InterruptedException {
        Path inbox = config.inboxDirectory();
        Files.createDirectories(inbox);
        fileHandler.createDirectories();

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

    void processPdf(Path pdfPath) {
        String base = pdfPath.getFileName().toString().replace(".pdf", "");

        if (dispatchedInSession.contains(base)) {
            log.debug("{} already dispatched this session — skipping duplicate", base);
            return;
        }

        if (!Files.exists(pdfPath)) {
            log.debug("PDF no longer exists: {}", pdfPath.getFileName());
            return;
        }

        log.info("New PDF detected: {}", pdfPath.getFileName());

        Path sidecarPath = pdfPath.resolveSibling(base + ".json");
        if (!Files.exists(sidecarPath)) {
            log.info("No sidecar JSON for {} — waiting up to {}ms", base, config.sidecarWaitMs());
            sidecarPath = awaitSidecar(pdfPath, base);
        }

        if (sidecarPath == null || !Files.exists(sidecarPath)) {
            log.error("Sidecar missing for {} after timeout — moving to failed", base);
            fileHandler.moveToFailed(pdfPath);
            failedCount.incrementAndGet();
            return;
        }

        try {
            SidecarData sidecar = json.readValue(sidecarPath.toFile(), SidecarData.class);
            process(pdfPath, sidecarPath, sidecar);
        } catch (Exception e) {
            log.error("Failed to process {}: {}", base, e.getMessage(), e);
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
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
        String invoiceNumber = sidecar.effectiveInvoiceNumber(pdfPath);

        if (ledger.alreadySent(invoiceNumber)) {
            log.info("Invoice {} already sent (ledger) — skipping", invoiceNumber);
            fileHandler.moveToProcessed(pdfPath);
            fileHandler.moveToProcessed(sidecarPath);
            return;
        }

        UUID orgId = sidecar.organizationId() != null ? sidecar.organizationId() : config.organizationId();
        if (orgId == null) {
            log.error("Sidecar {} missing organizationId and no default in config — cannot dispatch", invoiceNumber);
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
            return;
        }

        if (sidecar.recipientEmail() == null || sidecar.recipientEmail().isBlank()) {
            log.error("Sidecar {} missing recipientEmail — cannot dispatch", invoiceNumber);
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
            return;
        }

        byte[] pdfBytes;
        try {
            pdfBytes = Files.readAllBytes(pdfPath);
        } catch (IOException e) {
            log.error("Cannot read PDF {}: {}", pdfPath.getFileName(), e.getMessage());
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
            return;
        }

        if (pdfBytes.length == 0) {
            log.warn("PDF {} is empty — moving to failed", pdfPath.getFileName());
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
            return;
        }

        if (!PdfValidator.isValidPdf(pdfBytes, pdfPath.getFileName().toString())) {
            log.warn("PDF {} has invalid magic bytes — moving to failed", pdfPath.getFileName());
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
            return;
        }

        try {
            var result = api.createCampaign(orgId, sidecar, pdfBytes, pdfPath);

            if (result.success()) {
                log.info("Dispatched invoice {} as campaign {} (org={})",
                        invoiceNumber, result.campaignId(), orgId);
                fileHandler.moveToProcessed(pdfPath);
                fileHandler.moveToProcessed(sidecarPath);
                ledger.recordSent(result.campaignId(), invoiceNumber,
                        pdfPath.getFileName().toString(), sidecar.recipientEmail());
                dispatchedInSession.add(invoiceNumber);
                processedCount.incrementAndGet();
            } else {
                log.error("Failed to dispatch invoice {}: {}", invoiceNumber, result.error());
                fileHandler.moveToFailed(pdfPath);
                fileHandler.moveToFailed(sidecarPath);
                failedCount.incrementAndGet();
            }
        } catch (Exception e) {
            log.error("Error dispatching invoice {}: {}", invoiceNumber, e.getMessage(), e);
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
            failedCount.incrementAndGet();
        }
    }
}

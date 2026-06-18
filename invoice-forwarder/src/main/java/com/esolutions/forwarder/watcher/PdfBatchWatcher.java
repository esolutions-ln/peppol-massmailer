package com.esolutions.forwarder.watcher;

import com.esolutions.forwarder.client.MailerDtos;
import com.esolutions.forwarder.client.MassMailerClient;
import com.esolutions.forwarder.config.ForwarderProperties;
import com.esolutions.forwarder.store.CampaignLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches the inbox directory for fiscalised invoice PDFs + their .json sidecar.
 * Pairs are collected over a fixed time window ({@code batchWindowSeconds}),
 * then submitted as a single campaign to the remote Mass Mailer.
 *
 * <h3>Per-PDF flow</h3>
 * <ol>
 *   <li>PDF + sidecar appear; pair is queued for the next flush</li>
 *   <li>Resolve customer by VAT (then TIN) under {@code defaultOrgId}; register if missing</li>
 *   <li>Build an {@code InvoiceRecipientEntry} with Base64-encoded PDF</li>
 *   <li>On batch flush, all entries POSTed as one {@code CampaignRequest}</li>
 *   <li>Files moved to {@code processedDir} on accept, {@code failedDir} on hard failure</li>
 * </ol>
 */
@Component
public class PdfBatchWatcher {

    private static final Logger log = LoggerFactory.getLogger(PdfBatchWatcher.class);

    private final ForwarderProperties props;
    private final ObjectMapper objectMapper;
    private final MassMailerClient client;
    private final CampaignLedger ledger;

    private final Object pendingLock = new Object();
    private final List<Pending> pending = new ArrayList<>();
    private final Set<String> dispatchedInSession = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    private record Pending(Path pdf, Path sidecar, InvoiceSidecar data) {}

    public PdfBatchWatcher(ForwarderProperties props,
                           ObjectMapper objectMapper,
                           MassMailerClient client,
                           CampaignLedger ledger) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.client = client;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (props.inboxDir() == null || props.inboxDir().isBlank()) {
            log.warn("Forwarder: inbox-dir not configured — watcher disabled");
            return;
        }
        if (props.defaultOrgId() == null) {
            log.warn("Forwarder: default-org-id not configured — watcher disabled");
            return;
        }
        try {
            Files.createDirectories(Path.of(props.inboxDir()));
            Files.createDirectories(Path.of(props.processedDir()));
            Files.createDirectories(Path.of(props.failedDir()));
        } catch (IOException e) {
            log.error("Cannot create inbox directories: {}", e.getMessage());
            return;
        }
        Thread.ofVirtual().name("pdf-watcher").start(this::watchLoop);
        Thread.ofVirtual().name("pdf-flusher").start(this::flushLoop);
        log.info("Forwarder watcher started → {} (batch window {}s)",
                props.inboxDir(), props.batchWindowSeconds());
    }

    // ── Watch loop ──

    private void watchLoop() {
        Path inbox = Path.of(props.inboxDir());
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            inbox.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path filename = (Path) ev.context();
                    if (!filename.toString().endsWith(".pdf")) continue;
                    ingest(inbox.resolve(filename));
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Watch loop failed: {}", e.getMessage(), e);
        }
    }

    private void ingest(Path pdf) {
        String base = pdf.getFileName().toString().replace(".pdf", "");
        if (dispatchedInSession.contains(base)) return;

        Path sidecar = pdf.resolveSibling(base + ".json");
        if (!awaitFile(sidecar)) {
            log.warn("No sidecar JSON for {} after waiting — moving to failed/", pdf.getFileName());
            moveTo(pdf, props.failedDir());
            failedCount.incrementAndGet();
            return;
        }
        try {
            InvoiceSidecar data = objectMapper.readValue(sidecar.toFile(), InvoiceSidecar.class);
            validate(data, base);
            synchronized (pendingLock) {
                pending.add(new Pending(pdf, sidecar, data));
            }
            log.info("Queued {} for next batch", base);
        } catch (Exception e) {
            log.error("Failed to read sidecar for {}: {}", base, e.getMessage());
            moveTo(pdf, props.failedDir());
            moveTo(sidecar, props.failedDir());
            failedCount.incrementAndGet();
        }
    }

    private boolean awaitFile(Path p) {
        int[] backoff = {100, 200, 400, 800, 1500, 2000};
        for (int d : backoff) {
            if (Files.exists(p)) return true;
            try { Thread.sleep(d); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Files.exists(p);
    }

    private void validate(InvoiceSidecar s, String base) {
        if (s.recipientEmail() == null || s.recipientEmail().isBlank())
            throw new IllegalArgumentException("Sidecar " + base + " missing recipientEmail");
        boolean noTaxId = (s.bpn() == null || s.bpn().isBlank())
                && (s.vatNumber() == null || s.vatNumber().isBlank())
                && (s.tinNumber() == null || s.tinNumber().isBlank());
        if (noTaxId)
            log.warn("Sidecar {} has no BPN/VAT/TIN — customer will be matched/registered by email only", base);
    }

    // ── Flush loop ──

    private void flushLoop() {
        long intervalMs = props.batchWindowSeconds() * 1000L;
        while (!Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(intervalMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            flushNow();
        }
    }

    /** Package-private so tests / admin endpoints can trigger an immediate flush. */
    void flushNow() {
        List<Pending> batch;
        synchronized (pendingLock) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        String batchId = UUID.randomUUID().toString();
        log.info("Flushing batch {} ({} pdfs)", batchId, batch.size());
        try {
            submitBatch(batchId, batch);
        } catch (Exception e) {
            log.error("Batch {} failed entirely: {}", batchId, e.getMessage(), e);
            for (Pending p : batch) {
                moveTo(p.pdf(), props.failedDir());
                moveTo(p.sidecar(), props.failedDir());
                failedCount.incrementAndGet();
            }
        }
    }

    private void submitBatch(String batchId, List<Pending> batch) throws IOException {
        UUID orgId = props.defaultOrgId();
        List<MailerDtos.InvoiceRecipientEntry> entries = new ArrayList<>(batch.size());
        Set<String> filenames = new HashSet<>();

        for (Pending p : batch) {
            InvoiceSidecar s = p.data();
            // Customer resolution: BPN → VAT → TIN → register
            var existing = client.findCustomerByTaxId(orgId, s.bpn(), s.vatNumber(), s.tinNumber());
            String email;
            String name;
            if (existing.isPresent()) {
                email = existing.get().email();
                name = existing.get().name() != null ? existing.get().name() : s.recipientName();
                log.debug("Resolved customer by tax id (bpn={}, vat={}) → {} ({})",
                        s.bpn(), s.vatNumber(), email, existing.get().id());
            } else {
                var created = client.registerCustomer(orgId, new MailerDtos.RegisterCustomerRequest(
                        s.recipientEmail(),
                        s.recipientName(),
                        s.recipientCompany(),
                        s.tradingName(),
                        s.erpSource() != null ? s.erpSource() : "GENERIC_API",
                        s.vatNumber(),
                        s.tinNumber(),
                        s.bpn(),
                        s.addressLine1(),
                        s.addressLine2(),
                        s.city(),
                        s.country()
                ));
                email = created.email();
                name = created.name() != null ? created.name() : s.recipientName();
                log.info("Registered new customer {} under org {} [bpn={}, vat={}]",
                        email, orgId, s.bpn(), s.vatNumber());
            }

            byte[] pdfBytes = Files.readAllBytes(p.pdf());
            entries.add(new MailerDtos.InvoiceRecipientEntry(
                    email,
                    name,
                    s.invoiceNumber() != null ? s.invoiceNumber() : p.pdf().getFileName().toString().replace(".pdf", ""),
                    s.invoiceDate(),
                    s.dueDate(),
                    s.totalAmount(),
                    s.vatAmount(),
                    s.currency(),
                    s.fiscalDeviceSerialNumber(),
                    s.fiscalDayNumber(),
                    s.globalInvoiceCounter(),
                    s.verificationCode(),
                    s.qrCodeUrl(),
                    Base64.getEncoder().encodeToString(pdfBytes),
                    p.pdf().getFileName().toString(),
                    s.mergeFields()
            ));
            filenames.add(p.pdf().getFileName().toString());
        }

        // Use the first sidecar's campaign metadata; fall back to sane defaults.
        InvoiceSidecar first = batch.get(0).data();
        var req = new MailerDtos.CampaignRequest(
                first.campaignName() != null ? first.campaignName() : "Forwarder batch " + batchId,
                first.subject() != null ? first.subject() : "Your Invoice",
                first.templateName() != null ? first.templateName() : "invoice",
                first.templateVariables(),
                orgId,
                null,
                entries
        );

        var created = client.createCampaign(req);
        if (created == null || created.id() == null) {
            throw new IllegalStateException("createCampaign returned no id");
        }
        ledger.recordSubmission(batchId, created.id(), entries.size(), String.join(",", filenames));
        log.info("Batch {} accepted as campaign {} ({} recipients)", batchId, created.id(), entries.size());

        // Move files to processed/ now that the server has accepted them
        for (Pending p : batch) {
            moveTo(p.pdf(), props.processedDir());
            moveTo(p.sidecar(), props.processedDir());
            dispatchedInSession.add(p.pdf().getFileName().toString().replace(".pdf", ""));
            processedCount.incrementAndGet();
        }
    }

    private void moveTo(Path file, String dir) {
        if (!Files.exists(file)) return;
        try {
            Path target = Path.of(dir).resolve(file.getFileName());
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not move {} → {}: {}", file.getFileName(), dir, e.getMessage());
        }
    }

    public long processed() { return processedCount.get(); }
    public long failed() { return failedCount.get(); }
    public int pendingSize() {
        synchronized (pendingLock) { return pending.size(); }
    }
}

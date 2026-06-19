package com.esolutions.massmailer.service;

import com.esolutions.massmailer.config.PdfWatcherProperties;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.peppol.service.PeppolDeliveryService;
import com.esolutions.watcher.common.PdfValidator;
import com.esolutions.watcher.common.SidecarData;
import com.esolutions.watcher.common.WatcherFileHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "massmailer.pdf-watcher", name = "enabled", havingValue = "true")
public class PdfFolderWatcherService {

    private static final Logger log = LoggerFactory.getLogger(PdfFolderWatcherService.class);

    private final PdfWatcherProperties props;
    private final ObjectMapper objectMapper;
    private final CustomerContactService customerService;
    private final CampaignOrchestrator orchestrator;
    private final PeppolDeliveryService peppolService;
    private final DeliveryModeRouter deliveryModeRouter;
    private final WatcherFileHandler fileHandler;

    private final Set<String> dispatchedInSession = ConcurrentHashMap.newKeySet();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public PdfFolderWatcherService(PdfWatcherProperties props,
                                    CustomerContactService customerService,
                                    CampaignOrchestrator orchestrator,
                                    PeppolDeliveryService peppolService,
                                    DeliveryModeRouter deliveryModeRouter,
                                    ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.customerService = customerService;
        this.orchestrator = orchestrator;
        this.peppolService = peppolService;
        this.deliveryModeRouter = deliveryModeRouter;
        this.fileHandler = new WatcherFileHandler(
                Path.of(props.inboxDirectory()).resolve("processed"),
                Path.of(props.inboxDirectory()).resolve("failed"),
                hasEmailedDir() ? Path.of(props.emailedDirectory()) : null
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWatching() {
        String inbox = props.inboxDirectory();
        if (inbox == null || inbox.isBlank()) {
            log.warn("PdfFolderWatcher: inbox-directory is not configured — watcher not started");
            return;
        }
        Thread.ofVirtual().name("pdf-watcher").start(this::watchLoop);
        log.info("PdfFolderWatcher: started watching {}", inbox);
    }

    private void watchLoop() {
        Path inboxPath = Path.of(props.inboxDirectory());
        try {
            fileHandler.createDirectories();
        } catch (IOException e) {
            log.error("PdfFolderWatcher: cannot create subdirectories in {}: {}", inboxPath, e.getMessage());
            return;
        }

        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            inboxPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path filename = (Path) event.context();
                    if (!filename.toString().endsWith(".pdf")) continue;
                    processFile(inboxPath.resolve(filename));
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("PdfFolderWatcher: interrupted, stopping");
        } catch (IOException e) {
            log.error("PdfFolderWatcher: watch loop failed: {}", e.getMessage(), e);
        }
    }

    void processFile(Path pdfPath) {
        String baseName = pdfPath.getFileName().toString().replace(".pdf", "");
        Path sidecarPath = pdfPath.resolveSibling(baseName + ".json");

        if (!Files.exists(pdfPath)) {
            log.debug("PdfFolderWatcher: PDF no longer exists: {}", pdfPath.getFileName());
            return;
        }

        if (dispatchedInSession.contains(baseName)) {
            log.warn("PdfFolderWatcher: {} already dispatched this session — skipping duplicate", baseName);
            return;
        }

        if (!awaitSidecar(sidecarPath)) {
            log.warn("PdfFolderWatcher: no sidecar JSON for {} after waiting — moving to failed", pdfPath.getFileName());
            fileHandler.moveToFailed(pdfPath);
            failedCount.incrementAndGet();
            return;
        }

        try {
            SidecarData sidecar = objectMapper.readValue(sidecarPath.toFile(), SidecarData.class);
            validateSidecar(sidecar, baseName);
            dispatch(pdfPath, sidecar);
            fileHandler.copyToEmailed(pdfPath);
            fileHandler.moveToProcessed(pdfPath);
            fileHandler.moveToProcessed(sidecarPath);
            dispatchedInSession.add(baseName);
            long total = processedCount.incrementAndGet();
            log.info("PdfFolderWatcher: dispatched {} -> {} [total={}]",
                     baseName, sidecar.recipientEmail(), total);
        } catch (Exception e) {
            long failures = failedCount.incrementAndGet();
            log.error("PdfFolderWatcher: failed to process {} [totalFailures={}]: {}",
                      pdfPath.getFileName(), failures, e.getMessage(), e);
            fileHandler.moveToFailed(pdfPath);
            fileHandler.moveToFailed(sidecarPath);
        }
    }

    private boolean awaitSidecar(Path sidecarPath) {
        int[] backoffMs = {100, 200, 400, 800, 1500, 2000};
        for (int delay : backoffMs) {
            if (Files.exists(sidecarPath)) return true;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Files.exists(sidecarPath);
    }

    private void validateSidecar(SidecarData sidecar, String pdfBaseName) {
        if (sidecar.recipientEmail() == null || sidecar.recipientEmail().isBlank()) {
            throw new IllegalArgumentException("Sidecar for " + pdfBaseName + " is missing recipientEmail");
        }
        if (resolveOrgId(sidecar) == null) {
            throw new IllegalArgumentException(
                    "Sidecar for " + pdfBaseName + " has no organizationId and no default is configured");
        }
    }

    private void dispatch(Path pdfPath, SidecarData sidecar) throws IOException {
        UUID orgId = resolveOrgId(sidecar);
        byte[] pdfBytes = Files.readAllBytes(pdfPath);
        String fileName = pdfPath.getFileName().toString();

        if (!PdfValidator.isValidPdf(pdfBytes, fileName)) {
            throw new IllegalArgumentException("File does not have valid PDF magic bytes: " + fileName);
        }

        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
        String invoiceNumber = sidecar.effectiveInvoiceNumber(pdfPath);

        var invoice = new CanonicalInvoice(
                ErpSource.GENERIC_API,
                orgId.toString(),
                invoiceNumber,
                sidecar.recipientEmail(),
                sidecar.recipientName(),
                sidecar.recipientCompany(),
                invoiceNumber,
                sidecar.invoiceDate() != null ? sidecar.invoiceDate() : LocalDate.now(),
                sidecar.dueDate(),
                null,
                sidecar.vatAmount(),
                sidecar.totalAmount(),
                sidecar.currency(),
                new FiscalMetadata(
                        sidecar.fiscalDeviceSerialNumber(),
                        sidecar.fiscalDayNumber(),
                        sidecar.globalInvoiceCounter(),
                        sidecar.verificationCode(),
                        sidecar.qrCodeUrl()
                ),
                new PdfSource(null, base64Pdf, null, fileName),
                sidecar.templateVariables() != null ? sidecar.templateVariables() : Map.of()
        );

        boolean hasVatOrTin = (sidecar.vatNumber() != null && !sidecar.vatNumber().isBlank())
                || (sidecar.tinNumber() != null && !sidecar.tinNumber().isBlank());

        if (hasVatOrTin) {
            customerService.upsert(
                    orgId,
                    invoice.recipientEmail(),
                    invoice.recipientName(),
                    invoice.recipientCompany(),
                    ErpSource.GENERIC_API.name(),
                    null,
                    sidecar.vatNumber(),
                    sidecar.tinNumber(),
                    null
            );
        } else {
            customerService.upsertAll(orgId, List.of(invoice));
        }

        DeliveryMode effectiveMode = deliveryModeRouter.resolveDeliveryMode(orgId, invoice.recipientEmail().trim().toLowerCase());

        if (effectiveMode == DeliveryMode.AS4 || effectiveMode == DeliveryMode.BOTH) {
            peppolService.deliver(orgId, invoice);
        }

        if (effectiveMode == DeliveryMode.EMAIL || effectiveMode == DeliveryMode.BOTH) {
            var campaignReq = orchestrator.fromCanonicalInvoices(
                    sidecar.effectiveCampaignName(),
                    sidecar.effectiveSubject(),
                    sidecar.effectiveTemplateName(),
                    sidecar.templateVariables(),
                    orgId,
                    List.of(invoice)
            );
            var campaign = orchestrator.createCampaign(campaignReq);
            orchestrator.dispatchCampaign(campaign.getId());
        }
    }

    private UUID resolveOrgId(SidecarData sidecar) {
        return sidecar.organizationId() != null ? sidecar.organizationId() : props.defaultOrganizationId();
    }

    private boolean hasEmailedDir() {
        return props.emailedDirectory() != null && !props.emailedDirectory().isBlank();
    }

    public long processed() { return processedCount.get(); }
    public long failed() { return failedCount.get(); }
}

package com.esolutions.massmailer.service;

import com.esolutions.massmailer.config.PdfWatcherProperties;
import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.service.PeppolDeliveryService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PDF Folder Watcher — monitors a configured inbox directory and dispatches
 * fiscalised invoice PDFs as they arrive, without requiring an API call from the ERP.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Your ERP prints the fiscalised invoice to a PDF file in the inbox directory</li>
 *   <li>Your ERP (or a print driver script) writes a companion {@code .json} sidecar
 *       with the same base name — e.g. {@code INV-2026-0100.pdf} + {@code INV-2026-0100.json}</li>
 *   <li>This service detects both files, reads the sidecar, and routes the invoice
 *       through the standard dispatch pipeline (EMAIL or PEPPOL)</li>
 *   <li>Successfully dispatched files are moved to {@code inbox/processed/};
 *       failures move to {@code inbox/failed/} for manual inspection</li>
 * </ol>
 *
 * <h3>Sidecar JSON format</h3>
 * <pre>{@code
 * {
 *   "organizationId":           "d4f7a2c1-...",      // optional — falls back to default
 *   "campaignName":             "March 2026 Invoices",
 *   "subject":                  "Your Invoice from Acme Holdings",
 *   "templateName":             "invoice",
 *   "invoiceNumber":            "INV-2026-0100",      // must match PDF filename (without .pdf)
 *   "recipientEmail":           "buyer@acme.co.zw",
 *   "recipientName":            "Alice Moyo",
 *   "recipientCompany":         "Acme Holdings",
 *   "invoiceDate":              "2026-03-01",
 *   "dueDate":                  "2026-03-31",
 *   "totalAmount":              2400.00,
 *   "vatAmount":                360.00,
 *   "currency":                 "USD",
 *   "fiscalDeviceSerialNumber": "FD-SN-12345",
 *   "fiscalDayNumber":          "60",
 *   "globalInvoiceCounter":     "10001",
 *   "verificationCode":         "AAAA-BBBB-1111",
 *   "qrCodeUrl":                "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111",
 *   "templateVariables": {
 *     "companyName":   "Acme Holdings (Pvt) Ltd",
 *     "accountsEmail": "accounts@acmeholdings.co.zw"
 *   }
 * }
 * }</pre>
 *
 * <h3>Enabling</h3>
 * <pre>
 *   massmailer.pdf-watcher.enabled=true
 *   massmailer.pdf-watcher.inbox-directory=/var/lib/invoicedirect/inbox
 *   massmailer.pdf-watcher.default-organization-id=&lt;your-org-uuid&gt;
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "massmailer.pdf-watcher", name = "enabled", havingValue = "true")
public class PdfFolderWatcherService {

    private static final Logger log = LoggerFactory.getLogger(PdfFolderWatcherService.class);

    /** Sidecar JSON schema — all fields optional except invoiceNumber and recipientEmail. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceSidecar(
            UUID organizationId,
            String campaignName,
            String subject,
            String templateName,
            String invoiceNumber,
            String recipientEmail,
            String recipientName,
            String recipientCompany,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal totalAmount,
            BigDecimal vatAmount,
            String currency,
            String fiscalDeviceSerialNumber,
            String fiscalDayNumber,
            String globalInvoiceCounter,
            String verificationCode,
            String qrCodeUrl,
            Map<String, Object> templateVariables
    ) {}

    private final PdfWatcherProperties props;
    private final ObjectMapper objectMapper;
    private final CustomerContactService customerService;
    private final CustomerContactRepository customerRepo;
    private final OrganizationRepository orgRepo;
    private final CampaignOrchestrator orchestrator;
    private final PeppolDeliveryService peppolService;

    public PdfFolderWatcherService(PdfWatcherProperties props,
                                    CustomerContactService customerService,
                                    CustomerContactRepository customerRepo,
                                    OrganizationRepository orgRepo,
                                    CampaignOrchestrator orchestrator,
                                    PeppolDeliveryService peppolService) {
        this.props = props;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.customerService = customerService;
        this.customerRepo = customerRepo;
        this.orgRepo = orgRepo;
        this.orchestrator = orchestrator;
        this.peppolService = peppolService;
    }

    /**
     * Starts the background watcher loop on a virtual thread after the application is ready.
     * Runs continuously until the JVM exits.
     */
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

    // ── Core watch loop ──

    private void watchLoop() {
        Path inboxPath = Path.of(props.inboxDirectory());
        try {
            Files.createDirectories(inboxPath.resolve("processed"));
            Files.createDirectories(inboxPath.resolve("failed"));
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

                    Path fullPath = inboxPath.resolve(filename);

                    // Short pause — lets the ERP finish writing both the PDF and sidecar
                    Thread.sleep(600);
                    processFile(fullPath);
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

    // ── File processing — package-private for testing ──

    void processFile(Path pdfPath) {
        String baseName = pdfPath.getFileName().toString().replace(".pdf", "");
        Path sidecarPath = pdfPath.resolveSibling(baseName + ".json");

        if (!Files.exists(pdfPath)) {
            log.debug("PdfFolderWatcher: PDF no longer exists (already moved?): {}", pdfPath.getFileName());
            return;
        }

        if (!Files.exists(sidecarPath)) {
            log.warn("PdfFolderWatcher: no sidecar JSON for {} — skipping (place {}.json alongside the PDF)",
                    pdfPath.getFileName(), baseName);
            moveToSubdir(pdfPath, "failed");
            return;
        }

        try {
            InvoiceSidecar sidecar = objectMapper.readValue(sidecarPath.toFile(), InvoiceSidecar.class);
            validateSidecar(sidecar, baseName);
            dispatch(pdfPath, sidecar);
            moveToSubdir(pdfPath, "processed");
            moveToSubdir(sidecarPath, "processed");
            log.info("PdfFolderWatcher: dispatched {} → {}", baseName, sidecar.recipientEmail());
        } catch (Exception e) {
            log.error("PdfFolderWatcher: failed to process {}: {}", pdfPath.getFileName(), e.getMessage(), e);
            moveToSubdir(pdfPath, "failed");
            moveToSubdir(sidecarPath, "failed");
        }
    }

    private void validateSidecar(InvoiceSidecar sidecar, String pdfBaseName) {
        if (sidecar.recipientEmail() == null || sidecar.recipientEmail().isBlank()) {
            throw new IllegalArgumentException("Sidecar for " + pdfBaseName + " is missing recipientEmail");
        }
        if (resolveOrgId(sidecar) == null) {
            throw new IllegalArgumentException(
                    "Sidecar for " + pdfBaseName + " has no organizationId and no default is configured");
        }
    }

    private void dispatch(Path pdfPath, InvoiceSidecar sidecar) throws IOException {
        UUID orgId = resolveOrgId(sidecar);
        String base64Pdf = Base64.getEncoder().encodeToString(Files.readAllBytes(pdfPath));
        String fileName = pdfPath.getFileName().toString();

        var invoice = new CanonicalInvoice(
                ErpSource.GENERIC_API,
                orgId.toString(),
                sidecar.invoiceNumber() != null ? sidecar.invoiceNumber() : fileName.replace(".pdf", ""),
                sidecar.recipientEmail(),
                sidecar.recipientName(),
                sidecar.recipientCompany(),
                sidecar.invoiceNumber() != null ? sidecar.invoiceNumber() : fileName.replace(".pdf", ""),
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

        // Register / refresh the customer record before dispatch
        customerService.upsertAll(orgId, List.of(invoice));

        // Resolve effective delivery mode: customer override → org default
        DeliveryMode orgMode = orgRepo.findById(orgId)
                .map(o -> o.getDeliveryMode() != null ? o.getDeliveryMode() : DeliveryMode.EMAIL)
                .orElse(DeliveryMode.EMAIL);

        DeliveryMode effectiveMode = orgMode;
        CustomerContact contact = customerRepo
                .findByOrganizationIdAndEmail(orgId, invoice.recipientEmail().trim().toLowerCase())
                .orElse(null);
        if (contact != null && contact.getDeliveryMode() != null) {
            effectiveMode = contact.getDeliveryMode();
        }

        // Dispatch to PEPPOL channel
        if (effectiveMode == DeliveryMode.AS4 || effectiveMode == DeliveryMode.BOTH) {
            peppolService.deliver(orgId, invoice);
        }

        // Dispatch to EMAIL channel
        if (effectiveMode == DeliveryMode.EMAIL || effectiveMode == DeliveryMode.BOTH) {
            var campaignReq = orchestrator.fromCanonicalInvoices(
                    sidecar.campaignName() != null ? sidecar.campaignName() : "PDF Watcher",
                    sidecar.subject() != null ? sidecar.subject() : "Your Invoice",
                    sidecar.templateName() != null ? sidecar.templateName() : "invoice",
                    sidecar.templateVariables(),
                    orgId,
                    List.of(invoice)
            );
            var campaign = orchestrator.createCampaign(campaignReq);
            orchestrator.dispatchCampaign(campaign.getId());
        }
    }

    private UUID resolveOrgId(InvoiceSidecar sidecar) {
        return sidecar.organizationId() != null ? sidecar.organizationId() : props.defaultOrganizationId();
    }

    private void moveToSubdir(Path file, String subdir) {
        if (!Files.exists(file)) return;
        try {
            Path target = file.getParent().resolve(subdir).resolve(file.getFileName());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("PdfFolderWatcher: could not move {} to {}/: {}", file.getFileName(), subdir, e.getMessage());
        }
    }
}

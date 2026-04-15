package com.esolutions.massmailer.service;

import com.esolutions.massmailer.billing.service.MeteringService;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.dto.MailDtos.*;
import com.esolutions.massmailer.exception.CampaignNotFoundException;
import com.esolutions.massmailer.model.*;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.repository.CampaignRepository;
import com.esolutions.massmailer.repository.RecipientRepository;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * Orchestrates mass-mailing of fiscalised invoices:
 *
 * 1. Persists campaign + per-recipient invoice records
 * 2. Resolves each recipient's PDF attachment (file or Base64)
 * 3. Renders personalised HTML email body with invoice merge fields
 * 4. Dispatches in batches using StructuredTaskScope (Java 25 structured concurrency)
 * 5. Tracks per-recipient delivery status with invoice correlation
 */
@Service
public class CampaignOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CampaignOrchestrator.class);

    private final CampaignRepository campaignRepo;
    private final RecipientRepository recipientRepo;
    private final SmtpSendService smtpService;
    private final TemplateRenderService templateService;
    private final PdfAttachmentResolver pdfResolver;
    private final ZimraFiscalValidator fiscalValidator;
    private final MailerProperties props;
    private final ObjectMapper objectMapper;
    private final MeteringService meteringService;
    private final OrganizationRepository orgRepo;

    public CampaignOrchestrator(CampaignRepository campaignRepo,
                                 RecipientRepository recipientRepo,
                                 SmtpSendService smtpService,
                                 TemplateRenderService templateService,
                                 PdfAttachmentResolver pdfResolver,
                                 ZimraFiscalValidator fiscalValidator,
                                 MailerProperties props,
                                 ObjectMapper objectMapper,
                                 MeteringService meteringService,
                                 OrganizationRepository orgRepo) {
        this.campaignRepo = campaignRepo;
        this.recipientRepo = recipientRepo;
        this.smtpService = smtpService;
        this.templateService = templateService;
        this.pdfResolver = pdfResolver;
        this.fiscalValidator = fiscalValidator;
        this.props = props;
        this.objectMapper = objectMapper;
        this.meteringService = meteringService;
        this.orgRepo = orgRepo;
    }

    // ══════════════════════════════════════════════
    //  Create Campaign
    // ══════════════════════════════════════════════

    @Transactional
    public MailCampaign createCampaign(CampaignRequest request) {
        var campaign = MailCampaign.builder()
                .name(request.name())
                .subject(request.subject())
                .templateName(request.templateName())
                .templateVariablesJson(toJson(request.templateVariables()))
                .totalRecipients(request.recipients().size())
                .organizationId(request.organizationId())
                .status(CampaignStatus.QUEUED)
                .build();

        campaignRepo.save(campaign);

        var recipients = request.recipients().stream()
                .map(r -> MailRecipient.builder()
                        .campaign(campaign)
                        .email(r.email().trim().toLowerCase())
                        .name(r.name())
                        // Invoice identity
                        .invoiceNumber(r.invoiceNumber())
                        .invoiceDate(r.invoiceDate())
                        .dueDate(r.dueDate())
                        // Financial
                        .totalAmount(r.totalAmount())
                        .vatAmount(r.vatAmount())
                        .currency(r.currency())
                        // Fiscal device / ZIMRA
                        .fiscalDeviceSerialNumber(r.fiscalDeviceSerialNumber())
                        .fiscalDayNumber(r.fiscalDayNumber())
                        .globalInvoiceCounter(r.globalInvoiceCounter())
                        .verificationCode(r.verificationCode())
                        .qrCodeUrl(r.qrCodeUrl())
                        // PDF attachment source
                        .pdfFilePath(r.pdfFilePath())
                        .pdfBase64(r.pdfBase64())
                        .pdfFileName(r.pdfFileName())
                        // Merge fields
                        .mergeFieldsJson(toJson(r.mergeFields()))
                        .build())
                .toList();

        recipientRepo.saveAll(recipients);
        campaign.setRecipients(new ArrayList<>(recipients));

        log.info("Campaign '{}' created with {} invoice recipients [id={}]",
                campaign.getName(), recipients.size(), campaign.getId());

        return campaign;
    }

    // ══════════════════════════════════════════════
    //  Dispatch Campaign (async, virtual threads)
    // ══════════════════════════════════════════════

    @Async("mailExecutor")
    @Transactional
    public void dispatchCampaign(UUID campaignId) {
        var campaign = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        campaign.markInProgress();
        campaignRepo.save(campaign);

        var pending = recipientRepo.findByCampaignIdAndDeliveryStatus(
                campaignId, MailRecipient.RecipientStatus.PENDING);

        Map<String, Object> campaignVars = fromJson(campaign.getTemplateVariablesJson());

        // Batch processing with structured concurrency
        var batches = partition(pending, props.batchSize());
        int batchNum = 0;

        for (var batch : batches) {
            batchNum++;
            log.info("Dispatching batch {}/{} for campaign '{}'",
                    batchNum, batches.size(), campaign.getName());

            dispatchBatch(campaign, batch, campaignVars);
            campaignRepo.save(campaign);

            // Inter-batch pause to let SMTP breathe
            sleep(1000);
        }

        campaign.markCompleted();
        campaignRepo.save(campaign);

        log.info("Campaign '{}' completed: sent={}, failed={}, skipped={}",
                campaign.getName(), campaign.getSentCount(),
                campaign.getFailedCount(), campaign.getSkippedCount());
    }

    /**
     * Dispatches a batch of invoice emails using Java 25 StructuredTaskScope.
     *
     * Each virtual thread:
     *   1. Resolves the recipient's PDF attachment
     *   2. Builds per-recipient merge fields (invoice metadata → template variables)
     *   3. Renders the HTML body
     *   4. Sends via SMTP with PDF attachment
     */
    private void dispatchBatch(MailCampaign campaign, List<MailRecipient> batch,
                                Map<String, Object> campaignVars) {
        try (var scope = StructuredTaskScope.open()) {

            var tasks = batch.stream()
                    .map(recipient -> scope.fork(() -> sendInvoiceEmail(campaign, recipient, campaignVars)))
                    .toList();

            scope.join();

            // Pattern match on sealed DeliveryResult for exhaustive handling
            for (int i = 0; i < tasks.size(); i++) {
                var recipient = batch.get(i);
                DeliveryResult result = tasks.get(i).get();

                switch (result) {
                    case DeliveryResult.Delivered d -> {
                        recipient.markSent(d.messageId(), d.attachmentSizeBytes());
                        campaign.incrementSent();
                    }
                    case DeliveryResult.Failed f -> {
                        recipient.markFailed(f.errorMessage());
                        campaign.incrementFailed();
                    }
                    case DeliveryResult.Skipped s -> {
                        recipient.markSkipped(s.reason());
                        campaign.incrementSkipped();
                    }
                }

                // ── Meter every delivery against the sending organization ──
                if (campaign.getOrganizationId() != null) {
                    orgRepo.findById(campaign.getOrganizationId()).ifPresent(org ->
                        meteringService.recordDelivery(
                                org, result, campaign.getId(),
                                result instanceof DeliveryResult.Delivered d ? d.attachmentSizeBytes() : 0L,
                                "EMAIL"
                        )
                    );
                }
            }

            recipientRepo.saveAll(batch);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Batch dispatch interrupted for campaign {}", campaign.getId());
        }
    }

    /**
     * Sends a single invoice email for one recipient — runs on a virtual thread.
     */
    private DeliveryResult sendInvoiceEmail(MailCampaign campaign,
                                             MailRecipient recipient,
                                             Map<String, Object> campaignVars) {
        String invoiceNum = recipient.getInvoiceNumber();

        // ── 1. Validate PDF attachment exists ──
        if (!recipient.hasPdfAttachment()) {
            log.warn("No PDF attachment for invoice {} → skipping {}", invoiceNum, recipient.getEmail());
            return new DeliveryResult.Skipped(
                    recipient.getEmail(), invoiceNum,
                    "No PDF attachment provided for invoice " + invoiceNum);
        }

        // ── 2. Resolve the PDF ──
        ResolvedAttachment pdf;
        try {
            pdf = pdfResolver.resolve(
                    recipient.getPdfFilePath(),
                    recipient.getPdfBase64(),
                    recipient.getPdfFileName());

            if (pdf == null) {
                return new DeliveryResult.Skipped(
                        recipient.getEmail(), invoiceNum,
                        "PDF resolver returned null for invoice " + invoiceNum);
            }
        } catch (PdfAttachmentResolver.PdfResolutionException e) {
            log.error("PDF resolution failed for invoice {}: {}", invoiceNum, e.getMessage());
            return new DeliveryResult.Failed(
                    recipient.getEmail(), invoiceNum,
                    "PDF resolution error: " + e.getMessage(), false);
        }

        // ── 3. Fiscal validation gate (when enabled) ──
        if (props.fiscalValidationEnabled()) {
            try {
                byte[] pdfBytes = pdf.source().getInputStream().readAllBytes();
                ZimraFiscalValidator.ValidationResult fiscalResult =
                        fiscalValidator.validate(pdfBytes, invoiceNum);
                if (!fiscalResult.valid()) {
                    log.warn("Fiscal validation failed for invoice {}: {}", invoiceNum, fiscalResult.errors());
                    return new DeliveryResult.Failed(
                            recipient.getEmail(), invoiceNum,
                            "Fiscal validation failed: " + fiscalResult.errors(), false);
                }
            } catch (java.io.IOException e) {
                log.error("Failed to read PDF bytes for fiscal validation, invoice {}: {}", invoiceNum, e.getMessage());
                return new DeliveryResult.Failed(
                        recipient.getEmail(), invoiceNum,
                        "Fiscal validation error: " + e.getMessage(), false);
            }
        }

        // ── 4. Build per-recipient merge variables (invoice metadata → template) ──
        Map<String, Object> mergeFields = buildInvoiceMergeFields(recipient);

        // ── 5. Render HTML body ──
        String html = templateService.render(
                campaign.getTemplateName(), campaignVars, mergeFields);

        // ── 6. Send with PDF attachment ──
        return smtpService.sendWithFallback(
                recipient.getEmail(), recipient.getName(),
                campaign.getSubject(), html,
                invoiceNum, pdf);
    }

    /**
     * Builds a map of invoice-specific variables for template rendering.
     * These override campaign-level variables for personalisation.
     */
    private Map<String, Object> buildInvoiceMergeFields(MailRecipient r) {
        var fields = new HashMap<String, Object>();

        // Contact
        if (r.getName() != null) fields.put("recipientName", r.getName());

        // Invoice identity
        fields.put("invoiceNumber", r.getInvoiceNumber());
        if (r.getInvoiceDate() != null) fields.put("invoiceDate", r.getInvoiceDate().toString());
        if (r.getDueDate() != null) fields.put("dueDate", r.getDueDate().toString());

        // Financial
        if (r.getTotalAmount() != null) fields.put("totalAmount", r.getTotalAmount().toPlainString());
        if (r.getVatAmount() != null) fields.put("vatAmount", r.getVatAmount().toPlainString());
        fields.put("currency", r.getCurrency());
        // Resolve display symbol for Zimbabwe multi-currency (ZWG→ZiG, USD→$, ZAR→R, etc.)
        if (r.getCurrency() != null) {
            fields.put("currencySymbol",
                    com.esolutions.massmailer.domain.model.ZimbabweCurrency.symbolFor(r.getCurrency()));
        }

        // Fiscal device / ZIMRA
        if (r.getFiscalDeviceSerialNumber() != null)
            fields.put("fiscalDeviceSerialNumber", r.getFiscalDeviceSerialNumber());
        if (r.getFiscalDayNumber() != null)
            fields.put("fiscalDayNumber", r.getFiscalDayNumber());
        if (r.getGlobalInvoiceCounter() != null)
            fields.put("globalInvoiceCounter", r.getGlobalInvoiceCounter());
        if (r.getVerificationCode() != null)
            fields.put("verificationCode", r.getVerificationCode());
        if (r.getQrCodeUrl() != null)
            fields.put("qrCodeUrl", r.getQrCodeUrl());

        // Merge additional per-recipient fields from JSON
        Map<String, Object> extra = fromJson(r.getMergeFieldsJson());
        fields.putAll(extra);

        return fields;
    }

    // ══════════════════════════════════════════════
    //  Query
    // ══════════════════════════════════════════════

    @Transactional(readOnly = true)
    public CampaignResponse getCampaignStatus(UUID campaignId) {
        var c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        return toCampaignResponse(c);
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> listCampaigns() {
        return campaignRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toCampaignResponse)
                .toList();
    }

    // ══════════════════════════════════════════════
    //  Bridge: CanonicalInvoice → CampaignRequest
    // ══════════════════════════════════════════════

    /**
     * Converts a list of ERP-normalised canonical invoices into a CampaignRequest
     * that the existing dispatch pipeline can process.
     *
     * <p>This is the bridge between the Hexagonal ERP adapter layer and the
     * existing campaign infrastructure — no existing dispatch code was modified.</p>
     */
    public CampaignRequest fromCanonicalInvoices(String campaignName,
                                                  String subject,
                                                  String templateName,
                                                  Map<String, Object> templateVars,
                                                  List<CanonicalInvoice> invoices) {
        return fromCanonicalInvoices(campaignName, subject, templateName, templateVars, null, invoices);
    }

    public CampaignRequest fromCanonicalInvoices(String campaignName,
                                                  String subject,
                                                  String templateName,
                                                  Map<String, Object> templateVars,
                                                  UUID organizationId,
                                                  List<CanonicalInvoice> invoices) {

        var recipients = invoices.stream()
                .map(inv -> {
                    var pdf = inv.pdfSource();
                    String pdfFilePath = pdf != null ? pdf.filePath() : null;
                    String pdfBase64 = pdf != null ? pdf.base64() : null;
                    String pdfFileName = pdf != null ? pdf.fileName() : null;

                    var fiscal = inv.fiscalMetadata() != null
                            ? inv.fiscalMetadata()
                            : CanonicalInvoice.FiscalMetadata.EMPTY;

                    var mergeFields = new HashMap<String, Object>();
                    if (inv.recipientName() != null)    mergeFields.put("recipientName", inv.recipientName());
                    if (inv.recipientCompany() != null)  mergeFields.put("recipientCompany", inv.recipientCompany());
                    if (inv.additionalMergeFields() != null) mergeFields.putAll(inv.additionalMergeFields());

                    return new InvoiceRecipientEntry(
                            inv.recipientEmail(),
                            inv.recipientName(),
                            inv.invoiceNumber(),
                            inv.invoiceDate(),
                            inv.dueDate(),
                            inv.totalAmount(),
                            inv.vatAmount(),
                            inv.currency(),
                            fiscal.fiscalDeviceSerialNumber(),
                            fiscal.fiscalDayNumber(),
                            fiscal.globalInvoiceCounter(),
                            fiscal.verificationCode(),
                            fiscal.qrCodeUrl(),
                            pdfFilePath,
                            pdfBase64,
                            pdfFileName,
                            mergeFields
                    );
                })
                .toList();

        return new CampaignRequest(campaignName, subject, templateName, templateVars, organizationId, recipients);
    }

    // ══════════════════════════════════════════════
    //  Retry failed recipients
    // ══════════════════════════════════════════════

    @Async("mailExecutor")
    @Transactional
    public void retryFailed(UUID campaignId) {
        var campaign = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        var retryable = recipientRepo.findRetryable(campaignId, props.maxRetries());
        if (retryable.isEmpty()) {
            log.info("No retryable recipients for campaign {}", campaignId);
            return;
        }

        log.info("Retrying {} failed invoice recipients for campaign '{}'",
                retryable.size(), campaign.getName());

        retryable.forEach(r -> r.setDeliveryStatus(MailRecipient.RecipientStatus.PENDING));
        recipientRepo.saveAll(retryable);

        Map<String, Object> campaignVars = fromJson(campaign.getTemplateVariablesJson());
        var batches = partition(retryable, props.batchSize());

        for (var batch : batches) {
            dispatchBatch(campaign, batch, campaignVars);
            campaignRepo.save(campaign);
        }
    }

    // ══════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════

    private CampaignResponse toCampaignResponse(MailCampaign c) {
        return new CampaignResponse(
                c.getId(), c.getName(), c.getStatus().name(),
                c.getTotalRecipients(), c.getSentCount(),
                c.getFailedCount(), c.getSkippedCount(),
                c.getCreatedAt().toString(),
                c.getStartedAt() != null ? c.getStartedAt().toString() : null,
                c.getCompletedAt() != null ? c.getCompletedAt().toString() : null
        );
    }

    private <T> List<List<T>> partition(List<T> list, int batchSize) {
        var partitions = new ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialisation failed", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("JSON deserialisation failed", e);
            return Map.of();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

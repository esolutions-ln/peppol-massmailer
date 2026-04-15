package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.billing.service.BillingService;
import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import com.esolutions.massmailer.repository.CampaignRepository;
import com.esolutions.massmailer.repository.RecipientRepository;
import com.esolutions.massmailer.security.OrgPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Organisation invoice dashboard — authenticated via X-API-Key.
 *
 * Every endpoint is scoped to the authenticated organisation.
 * An org can only see its own campaigns and invoices.
 *
 * Base path: /api/v1/my
 */
@RestController
@RequestMapping("/api/v1/my")
@Tag(name = "My Invoice Dashboard")
@SecurityRequirement(name = "ApiKeyAuth")
public class OrgInvoiceDashboardController {

    private final CampaignRepository campaignRepo;
    private final RecipientRepository recipientRepo;
    private final BillingService billingService;

    public OrgInvoiceDashboardController(CampaignRepository campaignRepo,
                                          RecipientRepository recipientRepo,
                                          BillingService billingService) {
        this.campaignRepo = campaignRepo;
        this.recipientRepo = recipientRepo;
        this.billingService = billingService;
    }

    // ── Response records ──

    public record OrgProfileResponse(
            UUID id, String name, String slug, String senderEmail,
            String senderDisplayName, String accountsEmail,
            String primaryErpSource, String status
    ) {}

    public record CampaignSummary(
            UUID id, String name, String status,
            int totalRecipients, int sent, int failed, int skipped,
            String createdAt, String completedAt
    ) {}

    public record InvoiceRecord(
            UUID id,
            String invoiceNumber,
            String recipientEmail,
            String recipientName,
            String status,
            String currency,
            BigDecimal totalAmount,
            BigDecimal vatAmount,
            String invoiceDate,
            String dueDate,
            String sentAt,
            String errorMessage,
            int retryCount,
            String messageId,
            UUID campaignId,
            String campaignName,
            String pdfFileName,
            String pdfBase64,
            Long attachmentSizeBytes
    ) {}

    public record DashboardStats(
            long totalCampaigns,
            long totalInvoices,
            long delivered,
            long failed,
            long skipped,
            long pending,
            String currentPeriod,
            BigDecimal currentPeriodCost,
            String billingCurrency
    ) {}

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/profile — Who am I?
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Get your organisation profile",
            description = "Returns the authenticated organisation's details.")
    @GetMapping(value = "/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrgProfileResponse> getProfile(
            @AuthenticationPrincipal OrgPrincipal principal) {

        var org = principal.org();
        return ResponseEntity.ok(new OrgProfileResponse(
                org.getId(), org.getName(), org.getSlug(),
                org.getSenderEmail(), org.getSenderDisplayName(),
                org.getAccountsEmail(), org.getPrimaryErpSource(),
                org.getStatus().name()
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/stats — Dashboard summary stats
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Get dashboard summary statistics",
            description = """
                    Returns aggregate counts across all campaigns and invoices for your
                    organisation, plus the current billing period cost.
                    """)
    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DashboardStats> getStats(
            @AuthenticationPrincipal OrgPrincipal principal) {

        UUID orgId = principal.orgId();

        var campaigns = campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        var allInvoices = recipientRepo.findByOrganizationId(orgId);

        long delivered = allInvoices.stream()
                .filter(r -> r.getDeliveryStatus() == RecipientStatus.SENT).count();
        long failed = allInvoices.stream()
                .filter(r -> r.getDeliveryStatus() == RecipientStatus.FAILED).count();
        long skipped = allInvoices.stream()
                .filter(r -> r.getDeliveryStatus() == RecipientStatus.SKIPPED).count();
        long pending = allInvoices.stream()
                .filter(r -> r.getDeliveryStatus() == RecipientStatus.PENDING).count();

        // Current billing period
        String period = com.esolutions.massmailer.billing.model.UsageRecord
                .deriveBillingPeriod(Instant.now());
        BigDecimal periodCost = BigDecimal.ZERO;
        String billingCurrency = "USD";

        var summary = billingService.getSummary(orgId, period);
        if (summary != null) {
            periodCost = summary.getTotalAmount();
            billingCurrency = summary.getCurrency();
        }

        return ResponseEntity.ok(new DashboardStats(
                campaigns.size(),
                allInvoices.size(),
                delivered, failed, skipped, pending,
                period, periodCost, billingCurrency
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/campaigns — All campaigns
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "List all your invoice campaigns",
            description = "Returns all campaigns submitted by your organisation, newest first.")
    @GetMapping(value = "/campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CampaignSummary>> listCampaigns(
            @AuthenticationPrincipal OrgPrincipal principal,
            @Parameter(description = "Filter by status: QUEUED, IN_PROGRESS, COMPLETED, PARTIALLY_FAILED, FAILED")
            @RequestParam(required = false) String status) {

        UUID orgId = principal.orgId();
        List<MailCampaign> campaigns;

        if (status != null && !status.isBlank()) {
            try {
                var s = com.esolutions.massmailer.model.CampaignStatus.valueOf(status.toUpperCase());
                campaigns = campaignRepo.findByOrganizationIdAndStatusOrderByCreatedAtDesc(orgId, s);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            campaigns = campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        }

        return ResponseEntity.ok(campaigns.stream().map(this::toCampaignSummary).toList());
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/campaigns/{id} — Single campaign + all invoices
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Get a campaign and all its invoice delivery records",
            description = "Returns the campaign details plus every invoice recipient and their delivery status.")
    @ApiResponse(responseCode = "200", description = "Campaign found")
    @ApiResponse(responseCode = "404", description = "Campaign not found or does not belong to your organisation")
    @GetMapping(value = "/campaigns/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getCampaignDetail(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable UUID id) {

        return campaignRepo.findByIdAndOrganizationId(id, principal.orgId())
                .map(campaign -> {
                    var invoices = recipientRepo.findByCampaignId(id)
                            .stream().map(r -> toInvoiceRecord(r, campaign)).toList();

                    var result = new LinkedHashMap<String, Object>();
                    result.put("campaign", toCampaignSummary(campaign));
                    result.put("invoices", invoices);
                    result.put("totalInvoices", invoices.size());
                    return ResponseEntity.ok((Map<String, Object>) result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/invoices — All invoices across all campaigns
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "List all submitted invoices",
            description = """
                    Returns every invoice submitted by your organisation across all campaigns.
                    Filter by delivery status to find failed or pending invoices.

                    **Status values:** `PENDING`, `SENT`, `FAILED`, `SKIPPED`, `BOUNCED`
                    """)
    @GetMapping(value = "/invoices", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<List<InvoiceRecord>> listInvoices(
            @AuthenticationPrincipal OrgPrincipal principal,
            @Parameter(description = "Filter by status: PENDING, SENT, FAILED, SKIPPED")
            @RequestParam(required = false) String status) {

        UUID orgId = principal.orgId();
        List<MailRecipient> recipients;

        if (status != null && !status.isBlank()) {
            try {
                var s = RecipientStatus.valueOf(status.toUpperCase());
                recipients = recipientRepo.findByOrganizationIdAndStatus(orgId, s);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            recipients = recipientRepo.findByOrganizationId(orgId);
        }

        // Batch-load campaigns to avoid N+1
        var campaignCache = new HashMap<UUID, MailCampaign>();
        var invoices = recipients.stream().map(r -> {
            var campaign = campaignCache.computeIfAbsent(
                    r.getCampaign().getId(), cid ->
                            campaignRepo.findById(cid).orElse(r.getCampaign()));
            return toInvoiceRecord(r, campaign);
        }).toList();

        return ResponseEntity.ok(invoices);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/invoices/{invoiceNumber} — Single invoice lookup
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Look up a specific invoice by invoice number",
            description = "Returns all delivery attempts for a given invoice number across all campaigns.")
    @GetMapping(value = "/invoices/{invoiceNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<List<InvoiceRecord>> getInvoice(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable String invoiceNumber) {

        var records = recipientRepo
                .findByOrganizationIdAndInvoiceNumber(principal.orgId(), invoiceNumber)
                .stream()
                .map(r -> toInvoiceRecord(r, r.getCampaign()))
                .toList();

        if (records.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(records);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/my/billing — Current billing summary
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Get your current billing summary",
            description = "Returns the current month's invoice counts and charges.")
    @GetMapping(value = "/billing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBilling(
            @AuthenticationPrincipal OrgPrincipal principal,
            @Parameter(description = "Billing period in YYYY-MM format. Defaults to current month.",
                    example = "2026-03")
            @RequestParam(required = false) String period) {

        String billingPeriod = (period != null && !period.isBlank())
                ? period
                : com.esolutions.massmailer.billing.model.UsageRecord
                        .deriveBillingPeriod(Instant.now());

        var summary = billingService.getSummary(principal.orgId(), billingPeriod);
        if (summary == null) {
            return ResponseEntity.ok(Map.of(
                    "billingPeriod", billingPeriod,
                    "message", "No usage recorded for this period yet"
            ));
        }
        return ResponseEntity.ok(summary);
    }

    @Operation(summary = "Get your full billing history")
    @GetMapping(value = "/billing/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBillingHistory(
            @AuthenticationPrincipal OrgPrincipal principal) {
        return ResponseEntity.ok(billingService.getOrgBillingHistory(principal.orgId()));
    }

    // ── Mappers ──

    private CampaignSummary toCampaignSummary(MailCampaign c) {
        return new CampaignSummary(
                c.getId(), c.getName(), c.getStatus().name(),
                c.getTotalRecipients(), c.getSentCount(),
                c.getFailedCount(), c.getSkippedCount(),
                c.getCreatedAt().toString(),
                c.getCompletedAt() != null ? c.getCompletedAt().toString() : null
        );
    }

    private InvoiceRecord toInvoiceRecord(MailRecipient r, MailCampaign campaign) {
        UUID campaignId = null;
        String campaignName = null;
        try {
            if (campaign != null) {
                campaignId = campaign.getId();
                campaignName = campaign.getName();
            }
        } catch (Exception ignored) {
            // lazy proxy not initialized — skip campaign details
        }
        return new InvoiceRecord(
                r.getId(),
                r.getInvoiceNumber(),
                r.getEmail(),
                r.getName(),
                r.getDeliveryStatus().name(),
                r.getCurrency(),
                r.getTotalAmount(),
                r.getVatAmount(),
                r.getInvoiceDate() != null ? r.getInvoiceDate().toString() : null,
                r.getDueDate() != null ? r.getDueDate().toString() : null,
                r.getSentAt() != null ? r.getSentAt().toString() : null,
                r.getErrorMessage(),
                r.getRetryCount(),
                r.getMessageId(),
                campaignId,
                campaignName,
                r.getPdfFileName(),
                r.getPdfBase64(),
                r.getAttachmentSizeBytes()
        );
    }
}

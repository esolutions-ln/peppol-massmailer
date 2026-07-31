package com.esolutions.massmailer.billing.controller;

import com.esolutions.massmailer.billing.controller.BillingDtos.*;
import com.esolutions.massmailer.billing.model.*;
import com.esolutions.massmailer.billing.service.BillingService;
import com.esolutions.massmailer.billing.service.BillingService.TierInput;
import com.esolutions.massmailer.billing.service.PlatformInvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "Billing & Metering")
public class BillingController {

    private final BillingService billingService;
    private final PlatformInvoiceService platformInvoiceService;

    public BillingController(BillingService billingService,
                              PlatformInvoiceService platformInvoiceService) {
        this.billingService = billingService;
        this.platformInvoiceService = platformInvoiceService;
    }

    // ═══════════════════════════════════════
    //  Rate Profiles
    // ═══════════════════════════════════════

    @Operation(summary = "Create a tiered rate profile",
            description = """
                    Creates a pricing profile with volume-based tiers. Each tier defines a \
                    per-invoice rate for a range of monthly volume. Assign the profile to \
                    organizations to bill them per invoice delivered through the channel.

                    **Example tiers:**
                    - Tier 1: invoices 1–500 at $0.10 each
                    - Tier 2: invoices 501–2000 at $0.07 each
                    - Tier 3: invoices 2001+ at $0.04 each (toInvoice = null for unbounded)
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            examples = @ExampleObject(value = """
                    {
                      "name": "Standard",
                      "description": "Default tiered pricing for SMEs",
                      "currency": "USD",
                      "monthlyBaseFee": 25.00,
                      "tiers": [
                        {"tierName": "Base", "fromInvoice": 1, "toInvoice": 500, "ratePerInvoice": 0.10},
                        {"tierName": "Volume", "fromInvoice": 501, "toInvoice": 2000, "ratePerInvoice": 0.07},
                        {"tierName": "Enterprise", "fromInvoice": 2001, "toInvoice": null, "ratePerInvoice": 0.04}
                      ]
                    }
                    """)))
    @PostMapping(value = "/rate-profiles", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RateProfileResponse> createRateProfile(
            @Valid @RequestBody CreateRateProfileRequest request) {

        var tiers = request.tiers().stream()
                .map(t -> new TierInput(t.tierName(), t.fromInvoice(), t.toInvoice(), t.ratePerInvoice()))
                .toList();

        var profile = billingService.createRateProfile(
                request.name(), request.description(), request.currency(),
                request.monthlyBaseFee(), tiers);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(profile));
    }

    @Operation(summary = "List active rate profiles")
    @GetMapping(value = "/rate-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<RateProfileResponse>> listRateProfiles() {
        return ResponseEntity.ok(billingService.listActiveProfiles().stream()
                .map(this::toResponse).toList());
    }

    @Operation(summary = "Estimate cost for a given volume against a rate profile",
            description = "Preview what an organization would pay for N invoices in a month.")
    @PostMapping(value = "/estimate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CostEstimateResponse> estimateCost(
            @Valid @RequestBody CostEstimateRequest request) {

        var profile = billingService.getProfile(request.rateProfileId());
        var total = profile.calculateCost(request.invoiceCount());
        var baseFee = profile.getMonthlyBaseFee();

        return ResponseEntity.ok(new CostEstimateResponse(
                profile.getName(), request.invoiceCount(),
                baseFee, total.subtract(baseFee), total, profile.getCurrency()));
    }

    // ═══════════════════════════════════════
    //  Billing Summaries
    // ═══════════════════════════════════════

    @Operation(summary = "Get billing summary for an org and period",
            description = "Returns invoice counts, tiered cost breakdown, and billing status for one month.")
    @GetMapping(value = "/summary/{orgId}/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BillingSummaryResponse> getSummary(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {

        var summary = billingService.getSummary(orgId, period);
        if (summary == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toSummaryResponse(summary));
    }

    @Operation(summary = "Get billing history for an organization")
    @GetMapping(value = "/history/{orgId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BillingSummaryResponse>> getBillingHistory(@PathVariable UUID orgId) {
        return ResponseEntity.ok(billingService.getOrgBillingHistory(orgId).stream()
                .map(this::toSummaryResponse).toList());
    }

    @Operation(summary = "Close a billing period (finalize tally)",
            description = "Marks the period as CLOSED. Run at month-end.")
    @PostMapping(value = "/close/{orgId}/{period}")
    public ResponseEntity<BillingSummaryResponse> closePeriod(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {
        return ResponseEntity.ok(toSummaryResponse(billingService.closePeriod(orgId, period)));
    }

    // ═══════════════════════════════════════
    //  Usage Details
    // ═══════════════════════════════════════

    @Operation(summary = "Get detailed usage records for an org and period",
            description = "Returns every individual invoice delivery record — useful for audit and reconciliation.")
    @GetMapping(value = "/usage/{orgId}/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<UsageRecordResponse>> getUsageDetails(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {

        return ResponseEntity.ok(billingService.getUsageDetails(orgId, period).stream()
                .map(u -> new UsageRecordResponse(
                        u.getId(), u.getInvoiceNumber(), u.getRecipientEmail(),
                        u.getOutcome().name(), u.isBillable(), u.getErpSource(),
                        u.getPdfSizeBytes(), u.getRecordedAt().toString()))
                .toList());
    }

    // ═══════════════════════════════════════
    //  Invoice Generation & Dispatch
    // ═══════════════════════════════════════

    @Operation(summary = "Generate and dispatch the platform invoice for a closed period",
            description = """
                    Renders the billing invoice email and sends it to the organisation's accounts email address.
                    The period must be in CLOSED status (run the close endpoint first if needed).
                    Transitions the period from CLOSED → INVOICED.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Invoice dispatched, period now INVOICED"),
                    @ApiResponse(responseCode = "400", description = "Period not found or not in CLOSED status"),
                    @ApiResponse(responseCode = "502", description = "Invoice email dispatch failed")
            })
    @PostMapping(value = "/invoice/{orgId}/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BillingSummaryResponse> generateInvoice(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {
        try {
            var summary = platformInvoiceService.generateAndSend(orgId, period);
            return ResponseEntity.ok(toSummaryResponse(summary));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException e) {
            return ResponseEntity.status(502).build();
        }
    }

    // ═══════════════════════════════════════
    //  Payment Recording
    // ═══════════════════════════════════════

    @Operation(summary = "Record payment received for an invoiced period",
            description = """
                    Transitions the billing period from INVOICED → PAID.
                    The period must already be in INVOICED status.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Payment recorded, period now PAID"),
                    @ApiResponse(responseCode = "400", description = "Period not found or not in INVOICED status")
            })
    @PostMapping(value = "/payment/{orgId}/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentRecordedResponse> recordPayment(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {
        try {
            var summary = billingService.recordPayment(orgId, period);
            return ResponseEntity.ok(new PaymentRecordedResponse(
                    summary.getOrganizationId(), summary.getBillingPeriod(),
                    summary.getStatus().name(), summary.getTotalAmount(), summary.getCurrency()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ═══════════════════════════════════════
    //  Revenue Reports & CSV Export
    // ═══════════════════════════════════════

    @Operation(summary = "Platform-wide revenue report for a billing period",
            description = """
                    Returns a summary of all organisations billed in the given period —
                    invoice volumes, charges per org, and a total revenue figure.
                    Intended for platform-owner admin use.
                    """)
    @GetMapping(value = "/report/{period}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RevenueReportResponse> getRevenueReport(
            @PathVariable @Schema(example = "2026-03") String period) {

        var summaries = billingService.getRevenueReport(period);
        if (summaries.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var entries = summaries.stream()
                .map(s -> new RevenueReportEntry(
                        s.getOrganizationId(), s.getBillingPeriod(),
                        s.getBillableCount(), s.getTotalAmount(),
                        s.getCurrency() != null ? s.getCurrency() : "USD",
                        s.getStatus().name()))
                .toList();

        BigDecimal totalRevenue = entries.stream()
                .map(RevenueReportEntry::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Use the currency from the first summary (all orgs on the same platform currency)
        String currency = summaries.getFirst().getCurrency() != null
                ? summaries.getFirst().getCurrency() : "USD";

        return ResponseEntity.ok(new RevenueReportResponse(
                period, entries.size(), totalRevenue, currency, entries));
    }

    @Operation(summary = "Export usage records for an org+period as CSV",
            description = """
                    Downloads a CSV file of every individual invoice delivery record
                    for the given organisation and billing period.
                    Columns: invoiceNumber, recipientEmail, outcome, billable, erpSource, pdfSizeBytes, recordedAt
                    """)
    @GetMapping(value = "/export/{orgId}/{period}")
    public ResponseEntity<byte[]> exportUsageCsv(
            @PathVariable UUID orgId,
            @PathVariable @Schema(example = "2026-03") String period) {

        String csv = billingService.exportUsageCsv(orgId, period);
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("usage-" + orgId + "-" + period + ".csv")
                .build());
        headers.setContentLength(bytes.length);

        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // ── Mappers ──

    private RateProfileResponse toResponse(RateProfile p) {
        return new RateProfileResponse(
                p.getId(), p.getName(), p.getDescription(), p.getCurrency(),
                p.getMonthlyBaseFee(),
                p.getTiers().stream().map(t -> new TierResponse(
                        t.getTierName(), t.getFromInvoice(), t.getToInvoice(), t.getRatePerInvoice()
                )).toList(),
                p.isActive());
    }

    private BillingSummaryResponse toSummaryResponse(BillingPeriodSummary s) {
        return new BillingSummaryResponse(
                s.getOrganizationId(), s.getBillingPeriod(),
                s.getTotalInvoicesSubmitted(), s.getDeliveredCount(),
                s.getFailedCount(), s.getSkippedCount(), s.getBillableCount(),
                s.getRateProfileName(), s.getBaseFee(), s.getUsageCharges(),
                s.getTotalAmount(), s.getCurrency(), s.getStatus().name());
    }
}

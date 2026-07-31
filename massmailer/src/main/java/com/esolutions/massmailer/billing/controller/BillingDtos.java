package com.esolutions.massmailer.billing.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {}

    // ── Rate Profile ──

    @Schema(description = "Create a tiered rate profile for billing organizations per invoice")
    public record CreateRateProfileRequest(
            @Schema(description = "Profile name", example = "Standard", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String name,
            @Schema(description = "Description", example = "Default tiered pricing for SMEs")
            String description,
            @Schema(description = "Billing currency (ISO 4217)", example = "USD")
            String currency,
            @Schema(description = "Flat monthly base fee charged regardless of volume", example = "25.00")
            BigDecimal monthlyBaseFee,
            @Schema(description = "Volume-based pricing tiers (ordered by fromInvoice)", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty @Valid List<TierRequest> tiers
    ) {}

    @Schema(description = "A single pricing tier within a rate profile")
    public record TierRequest(
            @Schema(description = "Tier label", example = "Base")
            String tierName,
            @Schema(description = "Start of volume range (1-based, inclusive)", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long fromInvoice,
            @Schema(description = "End of volume range (inclusive). Null = unbounded top tier.", example = "500")
            Long toInvoice,
            @Schema(description = "Cost per invoice in this tier", example = "0.10", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull BigDecimal ratePerInvoice
    ) {}

    @Schema(description = "Rate profile with tiered pricing details")
    public record RateProfileResponse(
            UUID id,
            String name,
            String description,
            String currency,
            BigDecimal monthlyBaseFee,
            List<TierResponse> tiers,
            boolean active
    ) {}

    public record TierResponse(String tierName, long fromInvoice, Long toInvoice, BigDecimal ratePerInvoice) {}

    // ── Billing Summary ──

    @Schema(description = "Monthly billing summary for an organization — shows invoice counts, tiered cost, and billing status")
    public record BillingSummaryResponse(
            @Schema(description = "Organization ID") UUID organizationId,
            @Schema(description = "Billing period (YYYY-MM)", example = "2026-03") String billingPeriod,
            @Schema(description = "Total invoices submitted") long totalSubmitted,
            @Schema(description = "Successfully delivered") long delivered,
            @Schema(description = "Failed deliveries") long failed,
            @Schema(description = "Skipped (not billable)") long skipped,
            @Schema(description = "Billable count (delivered + failed)") long billable,
            @Schema(description = "Rate profile applied") String rateProfileName,
            @Schema(description = "Monthly base fee") BigDecimal baseFee,
            @Schema(description = "Usage-based charges (tiered)") BigDecimal usageCharges,
            @Schema(description = "Total amount (base + usage)") BigDecimal totalAmount,
            @Schema(description = "Billing currency") String currency,
            @Schema(description = "Billing status: OPEN, CLOSED, INVOICED, PAID") String status
    ) {}

    // ── Usage Detail ──

    @Schema(description = "Individual invoice delivery record for audit/metering")
    public record UsageRecordResponse(
            UUID id,
            String invoiceNumber,
            String recipientEmail,
            @Schema(description = "DELIVERED, FAILED, or SKIPPED") String outcome,
            boolean billable,
            String erpSource,
            Long pdfSizeBytes,
            String recordedAt
    ) {}

    // ── Payment Recording ──

    @Schema(description = "Response after recording payment for a billing period")
    public record PaymentRecordedResponse(
            @Schema(description = "Organization ID") UUID organizationId,
            @Schema(description = "Billing period (YYYY-MM)") String billingPeriod,
            @Schema(description = "New status — always PAID") String status,
            @Schema(description = "Total amount that was paid") BigDecimal totalAmount,
            @Schema(description = "Billing currency") String currency
    ) {}

    // ── Revenue Report ──

    @Schema(description = "Per-organisation entry in a platform revenue report")
    public record RevenueReportEntry(
            @Schema(description = "Organization ID") UUID organizationId,
            @Schema(description = "Billing period (YYYY-MM)") String billingPeriod,
            @Schema(description = "Billable invoice count") long billableInvoices,
            @Schema(description = "Total charged amount") BigDecimal totalAmount,
            @Schema(description = "Billing currency") String currency,
            @Schema(description = "OPEN, CLOSED, INVOICED, or PAID") String status
    ) {}

    @Schema(description = "Platform-wide revenue summary for a billing period")
    public record RevenueReportResponse(
            @Schema(description = "Billing period (YYYY-MM)", example = "2026-03") String period,
            @Schema(description = "Number of organisations billed") int organisationCount,
            @Schema(description = "Total revenue across all organisations") BigDecimal totalRevenue,
            @Schema(description = "Currency (all entries must share the same currency)") String currency,
            @Schema(description = "Per-organisation breakdown") List<RevenueReportEntry> entries
    ) {}

    // ── Cost Estimate ──

    @Schema(description = "Estimate the cost for a given invoice volume against a rate profile")
    public record CostEstimateRequest(
            @Schema(description = "Rate profile ID", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull UUID rateProfileId,
            @Schema(description = "Number of invoices to estimate", example = "1500", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Long invoiceCount
    ) {}

    @Schema(description = "Cost estimate breakdown")
    public record CostEstimateResponse(
            String rateProfileName,
            long invoiceCount,
            BigDecimal baseFee,
            BigDecimal usageCharges,
            BigDecimal totalEstimate,
            String currency
    ) {}
}

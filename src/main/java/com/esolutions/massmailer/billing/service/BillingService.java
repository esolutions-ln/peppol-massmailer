package com.esolutions.massmailer.billing.service;

import com.esolutions.massmailer.billing.model.*;
import com.esolutions.massmailer.billing.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.io.StringWriter;
import java.io.PrintWriter;

/**
 * Billing query and management service.
 *
 * Handles rate profile CRUD, billing period lifecycle,
 * and usage summary queries for the admin/billing dashboard.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final RateProfileRepository rateProfileRepo;
    private final BillingPeriodSummaryRepository summaryRepo;
    private final UsageRecordRepository usageRepo;

    public BillingService(RateProfileRepository rateProfileRepo,
                           BillingPeriodSummaryRepository summaryRepo,
                           UsageRecordRepository usageRepo) {
        this.rateProfileRepo = rateProfileRepo;
        this.summaryRepo = summaryRepo;
        this.usageRepo = usageRepo;
    }

    // ══════════════════════════════════════════════
    //  Rate Profiles
    // ══════════════════════════════════════════════

    @Transactional
    public RateProfile createRateProfile(String name, String description, String currency,
                                          BigDecimal monthlyBaseFee, List<TierInput> tiers) {
        var profile = RateProfile.builder()
                .name(name)
                .description(description)
                .currency(currency != null ? currency : "USD")
                .monthlyBaseFee(monthlyBaseFee != null ? monthlyBaseFee : BigDecimal.ZERO)
                .build();

        rateProfileRepo.save(profile);

        for (var tier : tiers) {
            var rateTier = RateTier.builder()
                    .rateProfile(profile)
                    .tierName(tier.tierName())
                    .fromInvoice(tier.fromInvoice())
                    .toInvoice(tier.toInvoice())
                    .ratePerInvoice(tier.ratePerInvoice())
                    .build();
            profile.getTiers().add(rateTier);
        }

        rateProfileRepo.save(profile);
        log.info("Created rate profile '{}' with {} tiers", name, tiers.size());
        return profile;
    }

    @Transactional(readOnly = true)
    public List<RateProfile> listActiveProfiles() {
        return rateProfileRepo.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public RateProfile getProfile(UUID id) {
        return rateProfileRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rate profile not found: " + id));
    }

    // ══════════════════════════════════════════════
    //  Billing Period Summaries
    // ══════════════════════════════════════════════

    @Transactional(readOnly = true)
    public BillingPeriodSummary getSummary(UUID orgId, String period) {
        return summaryRepo.findByOrganizationIdAndBillingPeriod(orgId, period)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<BillingPeriodSummary> getOrgBillingHistory(UUID orgId) {
        return summaryRepo.findByOrganizationIdOrderByBillingPeriodDesc(orgId);
    }

    /**
     * Closes a billing period — finalises the tally, marks all usage records as billed,
     * and transitions the summary to CLOSED.
     * Typically called by the scheduled month-end job or manually via admin endpoint.
     */
    @Transactional
    public BillingPeriodSummary closePeriod(UUID orgId, String period) {
        var summary = summaryRepo.findByOrganizationIdAndBillingPeriod(orgId, period)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No billing summary for org " + orgId + " period " + period));

        if (summary.getStatus() != BillingPeriodSummary.BillingStatus.OPEN) {
            throw new IllegalStateException(
                    "Period " + period + " for org " + orgId + " is already " + summary.getStatus());
        }

        int marked = usageRepo.markBilledByOrgAndPeriod(orgId, period);
        summary.close();
        summaryRepo.save(summary);

        log.info("Closed billing period {} for org {}: {} billable invoices, {} usage records marked billed, total={}",
                period, orgId, summary.getBillableCount(), marked, summary.getTotalAmount());

        return summary;
    }

    /**
     * Records payment received for an invoiced period — transitions INVOICED → PAID.
     */
    @Transactional
    public BillingPeriodSummary recordPayment(UUID orgId, String period) {
        var summary = summaryRepo.findByOrganizationIdAndBillingPeriod(orgId, period)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No billing summary for org " + orgId + " period " + period));

        if (summary.getStatus() != BillingPeriodSummary.BillingStatus.INVOICED) {
            throw new IllegalStateException(
                    "Period must be INVOICED before recording payment. Current status: " + summary.getStatus());
        }

        summary.markPaid();
        summaryRepo.save(summary);

        log.info("Payment recorded for org {} period {}: amount={} {}",
                orgId, period, summary.getTotalAmount(), summary.getCurrency());

        return summary;
    }

    /**
     * Returns all billing summaries across all organisations for a given period.
     * Used for platform-wide revenue reporting.
     */
    @Transactional(readOnly = true)
    public List<BillingPeriodSummary> getRevenueReport(String period) {
        return summaryRepo.findByBillingPeriod(period);
    }

    /**
     * Exports all usage records for an org+period as a CSV string.
     * Columns: invoiceNumber, recipientEmail, outcome, billable, erpSource, pdfSizeBytes, recordedAt
     */
    @Transactional(readOnly = true)
    public String exportUsageCsv(UUID orgId, String period) {
        var records = usageRepo.findByOrganizationIdAndBillingPeriodOrderByRecordedAtDesc(orgId, period);
        var sw = new StringWriter();
        var pw = new PrintWriter(sw);
        pw.println("invoiceNumber,recipientEmail,outcome,billable,erpSource,pdfSizeBytes,recordedAt");
        for (var r : records) {
            pw.printf("%s,%s,%s,%b,%s,%s,%s%n",
                    escapeCsv(r.getInvoiceNumber()),
                    escapeCsv(r.getRecipientEmail()),
                    r.getOutcome().name(),
                    r.isBillable(),
                    r.getErpSource() != null ? r.getErpSource() : "",
                    r.getPdfSizeBytes() != null ? r.getPdfSizeBytes().toString() : "",
                    r.getRecordedAt().toString());
        }
        return sw.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ══════════════════════════════════════════════
    //  Usage Details
    // ══════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<UsageRecord> getUsageDetails(UUID orgId, String period) {
        return usageRepo.findByOrganizationIdAndBillingPeriodOrderByRecordedAtDesc(orgId, period);
    }

    // ── Input record for tier creation ──
    public record TierInput(String tierName, long fromInvoice, Long toInvoice, BigDecimal ratePerInvoice) {}
}

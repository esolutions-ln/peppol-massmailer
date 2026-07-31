package com.esolutions.massmailer.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Monthly billing summary per organization.
 *
 * Aggregates all UsageRecords for one org in one billing period,
 * applies the rate profile's tiered pricing, and produces the
 * billable amount. This is the "invoice to the customer" for
 * using the delivery network.
 */
@Entity
@Table(name = "billing_period_summaries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_org_period",
                columnNames = {"organizationId", "billingPeriod"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingPeriodSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    /** Billing period (YYYY-MM) */
    @Column(nullable = false, length = 7)
    private String billingPeriod;

    // ── Volume Counts ──

    /** Total invoices submitted to the channel */
    @Builder.Default
    private long totalInvoicesSubmitted = 0;

    /** Invoices successfully delivered */
    @Builder.Default
    private long deliveredCount = 0;

    /** Invoices that failed delivery */
    @Builder.Default
    private long failedCount = 0;

    /** Invoices skipped (not billable) */
    @Builder.Default
    private long skippedCount = 0;

    /** Billable invoice count (delivered + failed) */
    @Builder.Default
    private long billableCount = 0;

    // ── Financial ──

    /** Rate profile ID used for this period's calculation */
    private UUID rateProfileId;

    /** Rate profile name (denormalized for audit) */
    private String rateProfileName;

    /** Monthly base fee from rate profile */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal baseFee = BigDecimal.ZERO;

    /** Usage-based charges (tiered calculation) */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal usageCharges = BigDecimal.ZERO;

    /** Total amount = baseFee + usageCharges */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Billing currency */
    @Column(length = 3)
    @Builder.Default
    private String currency = "USD";

    // ── Status ──

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BillingStatus status = BillingStatus.OPEN;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant closedAt;
    private Instant invoicedAt;
    private Instant paidAt;

    public enum BillingStatus {
        /** Period is still accumulating usage */
        OPEN,
        /** Period closed, final tally calculated */
        CLOSED,
        /** Platform invoice generated and sent to the org */
        INVOICED,
        /** Payment received */
        PAID
    }

    // ── Domain Logic ──

    public void recalculate(RateProfile profile) {
        this.rateProfileId = profile.getId();
        this.rateProfileName = profile.getName();
        this.currency = profile.getCurrency();
        this.baseFee = profile.getMonthlyBaseFee();
        this.usageCharges = profile.calculateCost(billableCount).subtract(baseFee);
        this.totalAmount = baseFee.add(usageCharges);
    }

    public void close() {
        this.status = BillingStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public void markInvoiced() {
        this.status = BillingStatus.INVOICED;
        this.invoicedAt = Instant.now();
    }

    public void markPaid() {
        this.status = BillingStatus.PAID;
        this.paidAt = Instant.now();
    }
}

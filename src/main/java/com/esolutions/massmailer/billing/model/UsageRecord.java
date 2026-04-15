package com.esolutions.massmailer.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * An individual usage record — one per invoice delivered through the network.
 *
 * This is the metering atom: every invoice that enters the delivery pipeline
 * (regardless of success/failure) creates a UsageRecord. The billing system
 * aggregates these per organization per billing period.
 *
 * In PEPPOL terms, this is the equivalent of the Access Point's
 * "message delivery receipt" — proof that a document was processed.
 */
@Entity
@Table(name = "usage_records", indexes = {
        @Index(name = "idx_usage_org", columnList = "organizationId"),
        @Index(name = "idx_usage_period", columnList = "billingPeriod"),
        @Index(name = "idx_usage_org_period", columnList = "organizationId, billingPeriod"),
        @Index(name = "idx_usage_invoice", columnList = "invoiceNumber")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The organization that submitted this invoice for delivery */
    @Column(nullable = false)
    private UUID organizationId;

    /** Billing period (YYYY-MM format for month-based billing) */
    @Column(nullable = false, length = 7)
    private String billingPeriod;

    /** The invoice number that was delivered */
    @Column(nullable = false)
    private String invoiceNumber;

    /** Campaign ID (if part of a batch) */
    private UUID campaignId;

    /** Recipient email address */
    @Column(nullable = false)
    private String recipientEmail;

    /** Delivery outcome */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryOutcome outcome;

    /** ERP source that produced this invoice */
    @Column(length = 30)
    private String erpSource;

    /** Size of the PDF attachment in bytes */
    private Long pdfSizeBytes;

    /** Whether this usage record has been included in a billing calculation */
    @Builder.Default
    private boolean billed = false;

    @Column(nullable = false)
    @Builder.Default
    private Instant recordedAt = Instant.now();

    public enum DeliveryOutcome {
        /** Invoice email delivered — billable */
        DELIVERED,
        /** Delivery failed after retries — billable (we processed it) */
        FAILED,
        /** Skipped (e.g. no PDF) — not billable */
        SKIPPED
    }

    /** Returns true if this record should count toward billing */
    public boolean isBillable() {
        return outcome == DeliveryOutcome.DELIVERED || outcome == DeliveryOutcome.FAILED;
    }

    /** Derives billing period from the recorded timestamp */
    public static String deriveBillingPeriod(Instant timestamp) {
        var ym = YearMonth.from(timestamp.atZone(java.time.ZoneOffset.UTC));
        return ym.toString(); // "2026-03"
    }
}

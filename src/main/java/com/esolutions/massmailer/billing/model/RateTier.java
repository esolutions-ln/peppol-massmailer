package com.esolutions.massmailer.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single pricing tier within a {@link RateProfile}.
 *
 * Defines the per-invoice rate for a range of monthly volume.
 * Example: fromInvoice=1, toInvoice=500, ratePerInvoice=0.10
 * means the first 500 invoices cost $0.10 each.
 *
 * Set toInvoice=null for an unbounded top tier (e.g. "2001+ invoices").
 */
@Entity
@Table(name = "rate_tiers")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rate_profile_id", nullable = false)
    private RateProfile rateProfile;

    /** Tier label (e.g. "Standard", "Volume", "Enterprise") */
    private String tierName;

    /** Start of the volume range (1-based, inclusive) */
    @Column(nullable = false)
    private long fromInvoice;

    /** End of the volume range (inclusive). Null = unbounded (top tier). */
    private Long toInvoice;

    /** Cost per invoice in this tier */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal ratePerInvoice;
}

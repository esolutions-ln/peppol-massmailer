package com.esolutions.massmailer.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A pricing profile that defines how an organization is billed
 * for invoices delivered through the network.
 *
 * Supports tiered pricing: different rates per invoice depending
 * on the monthly volume. E.g.:
 * - Tier 1: 1–500 invoices → $0.10 each
 * - Tier 2: 501–2000 invoices → $0.07 each
 * - Tier 3: 2001+ invoices → $0.04 each
 *
 * Each profile can have multiple tiers (RateTier).
 */
@Entity
@Table(name = "rate_profiles")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Profile name (e.g. "Standard", "Enterprise", "Startup") */
    @Column(nullable = false, unique = true)
    private String name;

    /** Description of the profile */
    private String description;

    /** ISO 4217 currency code for billing */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    /** Flat monthly base fee (charged regardless of volume) */
    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal monthlyBaseFee = BigDecimal.ZERO;

    /** Volume-based pricing tiers */
    @OneToMany(mappedBy = "rateProfile", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("fromInvoice ASC")
    @Builder.Default
    private List<RateTier> tiers = new ArrayList<>();

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    // ── Domain Logic ──

    /**
     * Calculates the total cost for a given invoice count using tiered pricing.
     *
     * Example with tiers [1-500 @ $0.10, 501-2000 @ $0.07, 2001+ @ $0.04]:
     *   - 300 invoices  → 300 × $0.10 = $30.00
     *   - 800 invoices  → 500 × $0.10 + 300 × $0.07 = $71.00
     *   - 3000 invoices → 500 × $0.10 + 1500 × $0.07 + 1000 × $0.04 = $195.00
     *
     * Plus the monthly base fee.
     */
    public BigDecimal calculateCost(long invoiceCount) {
        BigDecimal total = monthlyBaseFee != null ? monthlyBaseFee : BigDecimal.ZERO;

        if (invoiceCount <= 0 || tiers.isEmpty()) {
            return total;
        }

        var sortedTiers = tiers.stream()
                .sorted(Comparator.comparingLong(RateTier::getFromInvoice))
                .toList();

        long remaining = invoiceCount;

        for (var tier : sortedTiers) {
            if (remaining <= 0) break;

            long tierCapacity;
            if (tier.getToInvoice() == null || tier.getToInvoice() == Long.MAX_VALUE) {
                // Unbounded top tier
                tierCapacity = remaining;
            } else {
                tierCapacity = tier.getToInvoice() - tier.getFromInvoice() + 1;
            }

            long countInTier = Math.min(remaining, tierCapacity);
            total = total.add(tier.getRatePerInvoice().multiply(BigDecimal.valueOf(countInTier)));
            remaining -= countInTier;
        }

        return total;
    }
}

package com.esolutions.massmailer.billing;

import com.esolutions.massmailer.billing.model.RateProfile;
import com.esolutions.massmailer.billing.model.RateTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RateProfileBillingTest {

    private RateProfile profile;

    @BeforeEach
    void setUp() {
        profile = RateProfile.builder()
                .name("Standard")
                .currency("USD")
                .monthlyBaseFee(new BigDecimal("25.00"))
                .build();

        // Tier 1: 1–500 @ $0.10
        profile.getTiers().add(RateTier.builder()
                .rateProfile(profile).tierName("Base")
                .fromInvoice(1).toInvoice(500L)
                .ratePerInvoice(new BigDecimal("0.10")).build());

        // Tier 2: 501–2000 @ $0.07
        profile.getTiers().add(RateTier.builder()
                .rateProfile(profile).tierName("Volume")
                .fromInvoice(501).toInvoice(2000L)
                .ratePerInvoice(new BigDecimal("0.07")).build());

        // Tier 3: 2001+ @ $0.04 (unbounded)
        profile.getTiers().add(RateTier.builder()
                .rateProfile(profile).tierName("Enterprise")
                .fromInvoice(2001).toInvoice(null)
                .ratePerInvoice(new BigDecimal("0.04")).build());
    }

    @Test
    @DisplayName("Zero invoices → only base fee")
    void shouldChargeOnlyBaseFeeForZeroVolume() {
        assertThat(profile.calculateCost(0))
                .isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("300 invoices → base + 300 × $0.10 = $55.00")
    void shouldCalculateSingleTier() {
        // 25.00 + 300 × 0.10 = 55.00
        assertThat(profile.calculateCost(300))
                .isEqualByComparingTo("55.00");
    }

    @Test
    @DisplayName("500 invoices → base + 500 × $0.10 = $75.00 (tier 1 boundary)")
    void shouldCalculateTier1Boundary() {
        // 25.00 + 500 × 0.10 = 75.00
        assertThat(profile.calculateCost(500))
                .isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("800 invoices → base + 500×$0.10 + 300×$0.07 = $96.00")
    void shouldSpanTwoTiers() {
        // 25.00 + 500×0.10 + 300×0.07 = 25 + 50 + 21 = 96.00
        assertThat(profile.calculateCost(800))
                .isEqualByComparingTo("96.00");
    }

    @Test
    @DisplayName("2000 invoices → base + 500×$0.10 + 1500×$0.07 = $180.00")
    void shouldFillTwoTiers() {
        // 25.00 + 500×0.10 + 1500×0.07 = 25 + 50 + 105 = 180.00
        assertThat(profile.calculateCost(2000))
                .isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("3000 invoices → base + 500×$0.10 + 1500×$0.07 + 1000×$0.04 = $220.00")
    void shouldSpanAllThreeTiers() {
        // 25.00 + 500×0.10 + 1500×0.07 + 1000×0.04 = 25 + 50 + 105 + 40 = 220.00
        assertThat(profile.calculateCost(3000))
                .isEqualByComparingTo("220.00");
    }

    @Test
    @DisplayName("10000 invoices → unbounded tier absorbs the excess")
    void shouldHandleLargeVolume() {
        // 25.00 + 500×0.10 + 1500×0.07 + 8000×0.04 = 25 + 50 + 105 + 320 = 500.00
        assertThat(profile.calculateCost(10000))
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("No base fee profile — pure usage-based")
    void shouldWorkWithoutBaseFee() {
        profile.setMonthlyBaseFee(BigDecimal.ZERO);
        // 0 + 500×0.10 + 300×0.07 = 71.00
        assertThat(profile.calculateCost(800))
                .isEqualByComparingTo("71.00");
    }

    @Test
    @DisplayName("Single flat-rate tier (no tiering)")
    void shouldWorkWithSingleTier() {
        var flat = RateProfile.builder()
                .name("Flat").currency("USD")
                .monthlyBaseFee(BigDecimal.ZERO).build();
        flat.getTiers().add(RateTier.builder()
                .rateProfile(flat).tierName("Flat")
                .fromInvoice(1).toInvoice(null)
                .ratePerInvoice(new BigDecimal("0.05")).build());

        // 5000 × 0.05 = 250.00
        assertThat(flat.calculateCost(5000))
                .isEqualByComparingTo("250.00");
    }
}

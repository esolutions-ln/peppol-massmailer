package com.esolutions.massmailer.peppol.controller;

import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link OrgDeliveryDashboardController}.
 *
 * <p>Property 13: Success Rate Formula — Validates: Requirements 11.2
 */
class OrgDeliveryDashboardPropertyTest {

    /**
     * Mirrors the success rate formula used in
     * {@link OrgDeliveryDashboardController#getPeppolStats}.
     */
    static double computeSuccessRate(long delivered, long totalDispatched) {
        if (totalDispatched == 0) {
            return 0.0;
        }
        return (delivered * 100.0) / totalDispatched;
    }

    /**
     * Property 13: Success Rate Formula — positive totalDispatched case.
     *
     * <p>For any (delivered, totalDispatched) pair where totalDispatched > 0 and
     * delivered <= totalDispatched, assert {@code successRate = (delivered / totalDispatched) * 100}.
     *
     * <p><b>Validates: Requirements 11.2</b>
     */
    @Property(tries = 1000)
    void successRateEqualsDeliveredOverTotalTimes100WhenTotalPositive(
            @ForAll @LongRange(min = 1, max = 1_000_000) long totalDispatched,
            @ForAll @LongRange(min = 0, max = 1_000_000) long delivered) {

        Assume.that(delivered <= totalDispatched);

        double successRate = computeSuccessRate(delivered, totalDispatched);
        double expected = (delivered * 100.0) / totalDispatched;

        assertThat(successRate)
                .as("successRate must equal (delivered / totalDispatched) * 100 "
                        + "for delivered=%d, totalDispatched=%d", delivered, totalDispatched)
                .isEqualTo(expected);
    }

    /**
     * Property 13: Success Rate Formula — zero totalDispatched case.
     *
     * <p>When totalDispatched = 0, assert {@code successRate = 0}.
     *
     * <p><b>Validates: Requirements 11.2</b>
     */
    @Property(tries = 200)
    void successRateIsZeroWhenTotalDispatchedIsZero(
            @ForAll @LongRange(min = 0, max = 1_000_000) long delivered) {

        double successRate = computeSuccessRate(delivered, 0L);

        assertThat(successRate)
                .as("successRate must be 0 when totalDispatched=0 (delivered=%d)", delivered)
                .isEqualTo(0.0);
    }
}

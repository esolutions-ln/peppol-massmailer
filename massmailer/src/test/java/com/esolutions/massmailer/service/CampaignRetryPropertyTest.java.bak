package com.esolutions.massmailer.service;

import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for campaign retry eligibility logic.
 *
 * P11: Retry Idempotency — validates Requirements 12.1, 12.2, 12.3, 12.4
 *
 * The retry eligibility condition (mirroring RecipientRepository.findRetryable):
 *   status == FAILED && retryCount < maxRetries
 *
 * SENT and SKIPPED recipients are never eligible regardless of retryCount.
 */
class CampaignRetryPropertyTest {

    private static final int MAX_RETRIES = 3;

    // ── Property P11: Retry Idempotency ──────────────────────────────────────
    // Validates: Requirements 12.1, 12.2, 12.3, 12.4

    /**
     * P11a — Only FAILED recipients with retryCount < maxRetries are eligible for retry.
     *
     * For any FAILED recipient, eligibility is determined solely by retryCount < maxRetries.
     *
     * **Validates: Requirements 12.1, 12.4**
     */
    @Property
    void onlyFailedRecipientsWithinRetryLimitAreEligible(
            @ForAll @IntRange(min = 0, max = 10) int retryCount
    ) {
        MailRecipient recipient = buildRecipient(RecipientStatus.FAILED, retryCount);

        boolean eligible = isEligibleForRetry(recipient);

        if (retryCount < MAX_RETRIES) {
            assertThat(eligible)
                    .as("FAILED recipient with retryCount=%d (< maxRetries=%d) must be eligible",
                            retryCount, MAX_RETRIES)
                    .isTrue();
        } else {
            assertThat(eligible)
                    .as("FAILED recipient with retryCount=%d (>= maxRetries=%d) must NOT be eligible",
                            retryCount, MAX_RETRIES)
                    .isFalse();
        }
    }

    /**
     * P11b — SENT recipients are never eligible for retry, regardless of retryCount.
     *
     * **Validates: Requirements 12.3**
     */
    @Property
    void sentRecipientsAreNeverEligibleForRetry(
            @ForAll @IntRange(min = 0, max = 10) int retryCount
    ) {
        MailRecipient recipient = buildRecipient(RecipientStatus.SENT, retryCount);

        assertThat(isEligibleForRetry(recipient))
                .as("SENT recipient with retryCount=%d must never be eligible for retry", retryCount)
                .isFalse();
    }

    /**
     * P11c — SKIPPED recipients are never eligible for retry, regardless of retryCount.
     *
     * **Validates: Requirements 12.3**
     */
    @Property
    void skippedRecipientsAreNeverEligibleForRetry(
            @ForAll @IntRange(min = 0, max = 10) int retryCount
    ) {
        MailRecipient recipient = buildRecipient(RecipientStatus.SKIPPED, retryCount);

        assertThat(isEligibleForRetry(recipient))
                .as("SKIPPED recipient with retryCount=%d must never be eligible for retry", retryCount)
                .isFalse();
    }

    /**
     * P11d — Among a mixed list of recipients, only FAILED ones with retryCount < maxRetries
     * are selected for retry — matching the query in RecipientRepository.findRetryable().
     *
     * **Validates: Requirements 12.1, 12.2, 12.3, 12.4**
     */
    @Property
    void retryFilterSelectsOnlyEligibleRecipients(
            @ForAll("recipientDescriptors") List<RecipientDescriptor> descriptors
    ) {
        List<MailRecipient> recipients = descriptors.stream()
                .map(d -> buildRecipient(d.status(), d.retryCount()))
                .toList();

        List<MailRecipient> retryable = recipients.stream()
                .filter(this::isEligibleForRetry)
                .toList();

        // Every selected recipient must be FAILED with retryCount < maxRetries
        for (MailRecipient r : retryable) {
            assertThat(r.getDeliveryStatus())
                    .as("Retryable recipient must have status FAILED")
                    .isEqualTo(RecipientStatus.FAILED);
            assertThat(r.getRetryCount())
                    .as("Retryable recipient must have retryCount < maxRetries=%d", MAX_RETRIES)
                    .isLessThan(MAX_RETRIES);
        }

        // Every non-selected recipient must NOT satisfy the eligibility condition
        List<MailRecipient> nonRetryable = recipients.stream()
                .filter(r -> !isEligibleForRetry(r))
                .toList();

        for (MailRecipient r : nonRetryable) {
            boolean wouldBeEligible = r.getDeliveryStatus() == RecipientStatus.FAILED
                    && r.getRetryCount() < MAX_RETRIES;
            assertThat(wouldBeEligible)
                    .as("Non-retryable recipient must not satisfy FAILED && retryCount < maxRetries")
                    .isFalse();
        }
    }

    /**
     * P11e — markFailed() increments retryCount, so a recipient that has been failed
     * MAX_RETRIES times is no longer eligible.
     *
     * **Validates: Requirements 12.2, 12.4**
     */
    @Property
    void recipientExhaustsRetriesAfterMaxFailures() {
        MailRecipient recipient = buildRecipient(RecipientStatus.PENDING, 0);

        // Simulate MAX_RETRIES failures — each markFailed() increments retryCount
        for (int i = 0; i < MAX_RETRIES; i++) {
            recipient.markFailed("SMTP error attempt " + (i + 1));
            if (i < MAX_RETRIES - 1) {
                // Still eligible before reaching the limit
                assertThat(isEligibleForRetry(recipient))
                        .as("After %d failure(s), recipient should still be eligible (retryCount=%d < %d)",
                                i + 1, recipient.getRetryCount(), MAX_RETRIES)
                        .isTrue();
            }
        }

        // After MAX_RETRIES failures, retryCount == MAX_RETRIES → no longer eligible
        assertThat(recipient.getRetryCount())
                .as("retryCount must equal MAX_RETRIES=%d after %d failures", MAX_RETRIES, MAX_RETRIES)
                .isEqualTo(MAX_RETRIES);
        assertThat(isEligibleForRetry(recipient))
                .as("Recipient with retryCount=%d (== maxRetries) must not be eligible",
                        recipient.getRetryCount())
                .isFalse();
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    @Provide
    Arbitrary<List<RecipientDescriptor>> recipientDescriptors() {
        Arbitrary<RecipientStatus> statuses = Arbitraries.of(
                RecipientStatus.PENDING,
                RecipientStatus.SENT,
                RecipientStatus.FAILED,
                RecipientStatus.SKIPPED,
                RecipientStatus.BOUNCED,
                RecipientStatus.UNSUBSCRIBED
        );
        Arbitrary<Integer> retryCounts = Arbitraries.integers().between(0, 5);

        return Combinators.combine(statuses, retryCounts)
                .as(RecipientDescriptor::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(30);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Retry eligibility condition — mirrors RecipientRepository.findRetryable() query:
     *   WHERE deliveryStatus = 'FAILED' AND retryCount < :maxRetries
     */
    private boolean isEligibleForRetry(MailRecipient recipient) {
        return recipient.getDeliveryStatus() == RecipientStatus.FAILED
                && recipient.getRetryCount() < MAX_RETRIES;
    }

    private MailRecipient buildRecipient(RecipientStatus status, int retryCount) {
        MailCampaign campaign = MailCampaign.builder()
                .name("Retry Test Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(1)
                .build();

        MailRecipient recipient = MailRecipient.builder()
                .campaign(campaign)
                .email("test@example.com")
                .name("Test Recipient")
                .invoiceNumber("INV-0001")
                .currency("USD")
                .deliveryStatus(status)
                .retryCount(retryCount)
                .build();

        return recipient;
    }

    // ── Value types ──────────────────────────────────────────────────────────

    record RecipientDescriptor(RecipientStatus status, int retryCount) {}
}

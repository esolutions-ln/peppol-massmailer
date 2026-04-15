package com.esolutions.massmailer.billing;

import com.esolutions.massmailer.billing.model.UsageRecord;
import com.esolutions.massmailer.billing.model.UsageRecord.DeliveryOutcome;
import com.esolutions.massmailer.billing.repository.BillingPeriodSummaryRepository;
import com.esolutions.massmailer.billing.repository.RateProfileRepository;
import com.esolutions.massmailer.billing.repository.UsageRecordRepository;
import com.esolutions.massmailer.billing.service.MeteringService;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.organization.model.Organization;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for UsageRecord billing outcomes and MeteringService coverage.
 *
 * P5: Billable Outcomes — validates Requirements 10.5, 10.6
 * P4: Metering Coverage — validates Requirements 10.1, 10.2, 10.3, 10.4
 *
 * Note: jqwik does not process JUnit 5 extensions (@ExtendWith), so mocks are
 * created manually via Mockito.mock() in buildService().
 */
class UsageRecordPropertyTest {

    // ── Property P5: Billable Outcomes ────────────────────────────────────────
    // Validates: Requirements 10.5, 10.6

    /**
     * P5a — isBillable() returns true iff outcome ∈ {DELIVERED, FAILED}.
     *
     * For every DeliveryOutcome value, the billable predicate must match
     * exactly the set {DELIVERED, FAILED}.
     *
     * **Validates: Requirements 10.5, 10.6**
     */
    @Property
    void billableIffDeliveredOrFailed(@ForAll("allOutcomes") DeliveryOutcome outcome) {
        UsageRecord record = UsageRecord.builder()
                .organizationId(UUID.randomUUID())
                .billingPeriod("2026-03")
                .invoiceNumber("INV-001")
                .recipientEmail("test@example.com")
                .outcome(outcome)
                .build();

        boolean expectedBillable = outcome == DeliveryOutcome.DELIVERED
                || outcome == DeliveryOutcome.FAILED;

        assertThat(record.isBillable())
                .as("isBillable() must be true iff outcome ∈ {DELIVERED, FAILED}, but was %s", outcome)
                .isEqualTo(expectedBillable);
    }

    /**
     * P5b — SKIPPED outcome is never billable.
     *
     * **Validates: Requirements 10.6**
     */
    @Property
    void skippedIsNeverBillable() {
        UsageRecord record = UsageRecord.builder()
                .organizationId(UUID.randomUUID())
                .billingPeriod("2026-03")
                .invoiceNumber("INV-SKIP-001")
                .recipientEmail("skip@example.com")
                .outcome(DeliveryOutcome.SKIPPED)
                .build();

        assertThat(record.isBillable())
                .as("SKIPPED outcome must never be billable")
                .isFalse();
    }

    // ── Property P4: Metering Coverage ───────────────────────────────────────
    // Validates: Requirements 10.1, 10.2, 10.3, 10.4

    /**
     * P4a — recordDelivery() for a Delivered result persists exactly one UsageRecord
     * with matching campaignId, recipientEmail, invoiceNumber, billed=false,
     * and billingPeriod matching current UTC YYYY-MM.
     *
     * **Validates: Requirements 10.1, 10.2, 10.3, 10.4**
     */
    @Property
    void deliveredResultPersistsOneUsageRecord(
            @ForAll("invoiceNumbers") String invoiceNumber,
            @ForAll("emails") String email
    ) {
        ServiceWithMocks ctx = buildService();
        UUID campaignId = UUID.randomUUID();
        Organization org = buildOrg();

        DeliveryResult result = new DeliveryResult.Delivered(email, invoiceNumber, "<msg-id>", 1024L);

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        when(ctx.summaryRepo().findByOrganizationIdAndBillingPeriod(any(), any()))
                .thenReturn(Optional.empty());

        ctx.service().recordDelivery(org, result, campaignId, 1024L, "EMAIL");

        verify(ctx.usageRepo(), times(1)).save(captor.capture());
        UsageRecord saved = captor.getValue();

        assertThat(saved.getCampaignId()).isEqualTo(campaignId);
        assertThat(saved.getRecipientEmail()).isEqualTo(email);
        assertThat(saved.getInvoiceNumber()).isEqualTo(invoiceNumber);
        assertThat(saved.isBilled()).isFalse();
        assertThat(saved.getBillingPeriod())
                .isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(saved.getOutcome()).isEqualTo(DeliveryOutcome.DELIVERED);
    }

    /**
     * P4b — recordDelivery() for a Failed result persists exactly one UsageRecord
     * with outcome=FAILED, billed=false, and correct billingPeriod.
     *
     * **Validates: Requirements 10.1, 10.2, 10.3, 10.4**
     */
    @Property
    void failedResultPersistsOneUsageRecord(
            @ForAll("invoiceNumbers") String invoiceNumber,
            @ForAll("emails") String email
    ) {
        ServiceWithMocks ctx = buildService();
        UUID campaignId = UUID.randomUUID();
        Organization org = buildOrg();

        DeliveryResult result = new DeliveryResult.Failed(email, invoiceNumber, "SMTP error", true);

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        when(ctx.summaryRepo().findByOrganizationIdAndBillingPeriod(any(), any()))
                .thenReturn(Optional.empty());

        ctx.service().recordDelivery(org, result, campaignId, 0L, "EMAIL");

        verify(ctx.usageRepo(), times(1)).save(captor.capture());
        UsageRecord saved = captor.getValue();

        assertThat(saved.getCampaignId()).isEqualTo(campaignId);
        assertThat(saved.getRecipientEmail()).isEqualTo(email);
        assertThat(saved.getInvoiceNumber()).isEqualTo(invoiceNumber);
        assertThat(saved.isBilled()).isFalse();
        assertThat(saved.getBillingPeriod())
                .isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(saved.getOutcome()).isEqualTo(DeliveryOutcome.FAILED);
    }

    /**
     * P4c — recordDelivery() for a Skipped result persists exactly one UsageRecord
     * with outcome=SKIPPED, billed=false, and correct billingPeriod.
     *
     * **Validates: Requirements 10.1, 10.2, 10.3, 10.4**
     */
    @Property
    void skippedResultPersistsOneUsageRecord(
            @ForAll("invoiceNumbers") String invoiceNumber,
            @ForAll("emails") String email
    ) {
        ServiceWithMocks ctx = buildService();
        UUID campaignId = UUID.randomUUID();
        Organization org = buildOrg();

        DeliveryResult result = new DeliveryResult.Skipped(email, invoiceNumber, "No PDF");

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        when(ctx.summaryRepo().findByOrganizationIdAndBillingPeriod(any(), any()))
                .thenReturn(Optional.empty());

        ctx.service().recordDelivery(org, result, campaignId, 0L, "EMAIL");

        verify(ctx.usageRepo(), times(1)).save(captor.capture());
        UsageRecord saved = captor.getValue();

        assertThat(saved.getCampaignId()).isEqualTo(campaignId);
        assertThat(saved.getRecipientEmail()).isEqualTo(email);
        assertThat(saved.getInvoiceNumber()).isEqualTo(invoiceNumber);
        assertThat(saved.isBilled()).isFalse();
        assertThat(saved.getBillingPeriod())
                .isEqualTo(YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(saved.getOutcome()).isEqualTo(DeliveryOutcome.SKIPPED);
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /** All three DeliveryOutcome enum values. */
    @Provide
    Arbitrary<DeliveryOutcome> allOutcomes() {
        return Arbitraries.of(DeliveryOutcome.values());
    }

    /** Realistic invoice number strings. */
    @Provide
    Arbitrary<String> invoiceNumbers() {
        return Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(s -> "INV-" + s);
    }

    /** Simple email-like strings. */
    @Provide
    Arbitrary<String> emails() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(10)
                .map(local -> local + "@example.com");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record ServiceWithMocks(
            MeteringService service,
            UsageRecordRepository usageRepo,
            BillingPeriodSummaryRepository summaryRepo,
            RateProfileRepository rateProfileRepo
    ) {}

    private ServiceWithMocks buildService() {
        UsageRecordRepository usageRepo = mock(UsageRecordRepository.class);
        BillingPeriodSummaryRepository summaryRepo = mock(BillingPeriodSummaryRepository.class);
        RateProfileRepository rateProfileRepo = mock(RateProfileRepository.class);
        return new ServiceWithMocks(
                new MeteringService(usageRepo, summaryRepo, rateProfileRepo),
                usageRepo, summaryRepo, rateProfileRepo
        );
    }

    private Organization buildOrg() {
        return Organization.builder()
                .id(UUID.randomUUID())
                .name("Test Org")
                .slug("test-org")
                .apiKey("test-key")
                .senderEmail("noreply@test.com")
                .senderDisplayName("Test")
                .build();
    }
}

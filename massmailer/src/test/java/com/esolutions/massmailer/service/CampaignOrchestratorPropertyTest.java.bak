package com.esolutions.massmailer.service;

import com.esolutions.massmailer.billing.service.MeteringService;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.dto.MailDtos.CampaignRequest;
import com.esolutions.massmailer.dto.MailDtos.InvoiceRecipientEntry;
import com.esolutions.massmailer.model.*;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.repository.CampaignRepository;
import com.esolutions.massmailer.repository.RecipientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for CampaignOrchestrator lifecycle invariants.
 *
 * P1: Campaign Completeness Counter Invariant — validates Requirements 3.1, 3.4
 * P2: Recipient Terminal Status After Completion — validates Requirements 3.3
 * P3: Delivery Correlation for SENT Recipients — validates Requirements 8.8
 *
 * Note: jqwik does not process JUnit 5 extensions (@ExtendWith), so mocks are
 * created manually via Mockito.mock() for each property invocation.
 * The @Async dispatchCampaign() is not tested here — P1 tests createCampaign()
 * directly, and P2/P3 test the MailRecipient domain model transitions.
 */
class CampaignOrchestratorPropertyTest {

    // ── Property P1: Campaign Completeness Counter Invariant ─────────────────
    // Validates: Requirements 3.1, 3.4

    /**
     * P1a — After createCampaign(), totalRecipients == request.recipients().size().
     *
     * For any non-empty list of recipients, the persisted campaign must record
     * exactly that count in totalRecipients.
     *
     * **Validates: Requirements 3.1**
     */
    @Property
    void totalRecipientsMatchesRequestSize(
            @ForAll("recipientLists") List<InvoiceRecipientEntry> recipients
    ) {
        OrchestratorWithMocks ctx = buildOrchestrator();

        CampaignRequest request = new CampaignRequest(
                "Test Campaign",
                "Test Subject",
                "invoice",
                Map.of("companyName", "Test Co"),
                UUID.randomUUID(),
                recipients
        );

        MailCampaign campaign = ctx.orchestrator().createCampaign(request);

        assertThat(campaign.getTotalRecipients())
                .as("totalRecipients must equal request.recipients().size() = %d", recipients.size())
                .isEqualTo(recipients.size());
    }

    /**
     * P1b — Counter invariant: sentCount + failedCount + skippedCount == totalRecipients
     * after all recipients have been processed.
     *
     * Simulates dispatch by directly applying markSent/markFailed/markSkipped to
     * recipients and incrementing campaign counters — mirrors what dispatchBatch() does.
     *
     * **Validates: Requirements 3.4**
     */
    @Property
    void counterInvariantHoldsAfterDispatch(
            @ForAll("outcomeLists") List<String> outcomes
    ) {
        // Build a campaign with totalRecipients matching the outcome list
        MailCampaign campaign = MailCampaign.builder()
                .name("Counter Invariant Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(outcomes.size())
                .status(CampaignStatus.IN_PROGRESS)
                .build();

        // Build recipients and apply the simulated dispatch outcomes
        List<MailRecipient> recipients = buildRecipients(campaign, outcomes.size());

        for (int i = 0; i < outcomes.size(); i++) {
            MailRecipient r = recipients.get(i);
            switch (outcomes.get(i)) {
                case "SENT" -> {
                    r.markSent("<msg-" + i + "@smtp.test>", 1024L);
                    campaign.incrementSent();
                }
                case "FAILED" -> {
                    r.markFailed("SMTP error");
                    campaign.incrementFailed();
                }
                case "SKIPPED" -> {
                    r.markSkipped("No PDF");
                    campaign.incrementSkipped();
                }
            }
        }

        int total = campaign.getSentCount() + campaign.getFailedCount() + campaign.getSkippedCount();

        assertThat(total)
                .as("sentCount(%d) + failedCount(%d) + skippedCount(%d) must == totalRecipients(%d)",
                        campaign.getSentCount(), campaign.getFailedCount(),
                        campaign.getSkippedCount(), campaign.getTotalRecipients())
                .isEqualTo(campaign.getTotalRecipients());
    }

    // ── Property P2: Recipient Terminal Status After Completion ───────────────
    // Validates: Requirements 3.3

    /**
     * P2a — After all recipients are processed, none remain PENDING.
     *
     * For any mix of SENT/FAILED/SKIPPED outcomes, every recipient must have
     * a terminal status — none may remain in PENDING.
     *
     * **Validates: Requirements 3.3**
     */
    @Property
    void noRecipientRemainsAfterCompletion(
            @ForAll("outcomeLists") List<String> outcomes
    ) {
        MailCampaign campaign = MailCampaign.builder()
                .name("Terminal Status Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(outcomes.size())
                .status(CampaignStatus.IN_PROGRESS)
                .build();

        List<MailRecipient> recipients = buildRecipients(campaign, outcomes.size());

        // Apply outcomes — simulating what dispatchBatch() does per recipient
        for (int i = 0; i < outcomes.size(); i++) {
            MailRecipient r = recipients.get(i);
            switch (outcomes.get(i)) {
                case "SENT"    -> r.markSent("<msg-" + i + "@smtp.test>", 512L);
                case "FAILED"  -> r.markFailed("Delivery error");
                case "SKIPPED" -> r.markSkipped("No PDF attachment");
            }
        }

        campaign.markCompleted();

        // Assert campaign reached a terminal status
        assertThat(campaign.getStatus())
                .as("Campaign must be COMPLETED or PARTIALLY_FAILED after markCompleted()")
                .isIn(CampaignStatus.COMPLETED, CampaignStatus.PARTIALLY_FAILED);

        // Assert no recipient remains PENDING
        for (MailRecipient r : recipients) {
            assertThat(r.getDeliveryStatus())
                    .as("Recipient %s must have terminal status, not PENDING", r.getEmail())
                    .isIn(
                            MailRecipient.RecipientStatus.SENT,
                            MailRecipient.RecipientStatus.FAILED,
                            MailRecipient.RecipientStatus.SKIPPED
                    );
            assertThat(r.getDeliveryStatus())
                    .as("Recipient %s must not remain PENDING", r.getEmail())
                    .isNotEqualTo(MailRecipient.RecipientStatus.PENDING);
        }
    }

    /**
     * P2b — Campaign status is COMPLETED when failedCount == 0, PARTIALLY_FAILED otherwise.
     *
     * **Validates: Requirements 3.3**
     */
    @Property
    void campaignStatusReflectsFailedCount(
            @ForAll @IntRange(min = 0, max = 10) int sentCount,
            @ForAll @IntRange(min = 0, max = 10) int failedCount,
            @ForAll @IntRange(min = 0, max = 10) int skippedCount
    ) {
        Assume.that(sentCount + failedCount + skippedCount > 0);

        MailCampaign campaign = MailCampaign.builder()
                .name("Status Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(sentCount + failedCount + skippedCount)
                .status(CampaignStatus.IN_PROGRESS)
                .build();

        for (int i = 0; i < sentCount; i++)   campaign.incrementSent();
        for (int i = 0; i < failedCount; i++)  campaign.incrementFailed();
        for (int i = 0; i < skippedCount; i++) campaign.incrementSkipped();

        campaign.markCompleted();

        if (failedCount == 0) {
            assertThat(campaign.getStatus())
                    .as("Campaign with no failures must be COMPLETED")
                    .isEqualTo(CampaignStatus.COMPLETED);
        } else {
            assertThat(campaign.getStatus())
                    .as("Campaign with %d failures must be PARTIALLY_FAILED", failedCount)
                    .isEqualTo(CampaignStatus.PARTIALLY_FAILED);
        }
    }

    // ── Property P3: Delivery Correlation for SENT Recipients ────────────────
    // Validates: Requirements 8.8

    /**
     * P3a — For every recipient with status=SENT, messageId != null, sentAt != null,
     * and attachmentSizeBytes >= 0.
     *
     * **Validates: Requirements 8.8**
     */
    @Property
    void sentRecipientsHaveDeliveryCorrelationFields(
            @ForAll("messageIdAndSizePairs") List<MessageIdAndSize> pairs
    ) {
        MailCampaign campaign = MailCampaign.builder()
                .name("Correlation Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(pairs.size())
                .status(CampaignStatus.IN_PROGRESS)
                .build();

        List<MailRecipient> recipients = buildRecipients(campaign, pairs.size());

        for (int i = 0; i < pairs.size(); i++) {
            recipients.get(i).markSent(pairs.get(i).messageId(), pairs.get(i).sizeBytes());
        }

        for (MailRecipient r : recipients) {
            assertThat(r.getDeliveryStatus())
                    .as("Recipient must be SENT")
                    .isEqualTo(MailRecipient.RecipientStatus.SENT);
            assertThat(r.getMessageId())
                    .as("SENT recipient must have non-null messageId")
                    .isNotNull();
            assertThat(r.getSentAt())
                    .as("SENT recipient must have non-null sentAt")
                    .isNotNull();
            assertThat(r.getAttachmentSizeBytes())
                    .as("SENT recipient must have attachmentSizeBytes >= 0")
                    .isGreaterThanOrEqualTo(0L);
        }
    }

    /**
     * P3b — Recipients that are FAILED or SKIPPED do NOT have messageId set.
     *
     * Ensures delivery correlation fields are only populated for SENT recipients.
     *
     * **Validates: Requirements 8.8**
     */
    @Property
    void nonSentRecipientsHaveNoMessageId(
            @ForAll("nonSentOutcomeLists") List<String> outcomes
    ) {
        MailCampaign campaign = MailCampaign.builder()
                .name("Non-Sent Correlation Campaign")
                .subject("Subject")
                .templateName("invoice")
                .totalRecipients(outcomes.size())
                .status(CampaignStatus.IN_PROGRESS)
                .build();

        List<MailRecipient> recipients = buildRecipients(campaign, outcomes.size());

        for (int i = 0; i < outcomes.size(); i++) {
            MailRecipient r = recipients.get(i);
            switch (outcomes.get(i)) {
                case "FAILED"  -> r.markFailed("SMTP error");
                case "SKIPPED" -> r.markSkipped("No PDF");
            }
        }

        for (MailRecipient r : recipients) {
            assertThat(r.getMessageId())
                    .as("FAILED/SKIPPED recipient must not have a messageId")
                    .isNull();
            assertThat(r.getSentAt())
                    .as("FAILED/SKIPPED recipient must not have sentAt set")
                    .isNull();
        }
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /** Generates a non-empty list of InvoiceRecipientEntry (1–20 entries). */
    @Provide
    Arbitrary<List<InvoiceRecipientEntry>> recipientLists() {
        Arbitrary<String> localParts = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3)
                .ofMaxLength(8);
        Arbitrary<String> invoiceNums = Arbitraries.strings()
                .withCharRange('0', '9')
                .ofMinLength(3)
                .ofMaxLength(6)
                .map(n -> "INV-" + n);

        return Combinators.combine(localParts, invoiceNums)
                .as((local, inv) -> new InvoiceRecipientEntry(
                        local + "@example.com",
                        "Recipient " + local,
                        inv,
                        null, null,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(15),
                        "USD",
                        null, null, null, null, null,
                        null,
                        Base64.getEncoder().encodeToString(
                                ("%PDF-1.4 fake content for " + inv).getBytes()),
                        inv + ".pdf",
                        Map.of()
                ))
                .list()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    /** Generates a non-empty list of terminal outcome strings (SENT/FAILED/SKIPPED). */
    @Provide
    Arbitrary<List<String>> outcomeLists() {
        return Arbitraries.of("SENT", "FAILED", "SKIPPED")
                .list()
                .ofMinSize(1)
                .ofMaxSize(30);
    }

    /** Generates a non-empty list of non-SENT outcome strings (FAILED/SKIPPED). */
    @Provide
    Arbitrary<List<String>> nonSentOutcomeLists() {
        return Arbitraries.of("FAILED", "SKIPPED")
                .list()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    /** Generates a non-empty list of (messageId, sizeBytes) pairs for P3a. */
    @Provide
    Arbitrary<List<MessageIdAndSize>> messageIdAndSizePairs() {
        Arbitrary<String> msgIds = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(6)
                .ofMaxLength(12)
                .map(s -> "<" + s + "@smtp.test>");
        Arbitrary<Long> sizes = Arbitraries.longs().between(0L, 5_000_000L);

        return Combinators.combine(msgIds, sizes)
                .as(MessageIdAndSize::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    // ── Value types ──────────────────────────────────────────────────────────

    record MessageIdAndSize(String messageId, long sizeBytes) {}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record OrchestratorWithMocks(
            CampaignOrchestrator orchestrator,
            CampaignRepository campaignRepo,
            RecipientRepository recipientRepo
    ) {}

    private OrchestratorWithMocks buildOrchestrator() {
        CampaignRepository campaignRepo = mock(CampaignRepository.class);
        RecipientRepository recipientRepo = mock(RecipientRepository.class);
        SmtpSendService smtpService = mock(SmtpSendService.class);
        TemplateRenderService templateService = mock(TemplateRenderService.class);
        PdfAttachmentResolver pdfResolver = mock(PdfAttachmentResolver.class);
        ZimraFiscalValidator fiscalValidator = mock(ZimraFiscalValidator.class);
        MeteringService meteringService = mock(MeteringService.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);

        MailerProperties props = new MailerProperties(
                "noreply@test.com", "Test", 100, 5000, 3, 2000L, false
        );

        // campaignRepo.save() returns the campaign passed to it
        when(campaignRepo.save(any(MailCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // recipientRepo.saveAll() returns the list passed to it
        when(recipientRepo.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        CampaignOrchestrator orchestrator = new CampaignOrchestrator(
                campaignRepo, recipientRepo, smtpService, templateService,
                pdfResolver, fiscalValidator, props, new ObjectMapper(),
                meteringService, orgRepo
        );

        return new OrchestratorWithMocks(orchestrator, campaignRepo, recipientRepo);
    }

    /**
     * Builds a list of PENDING MailRecipient instances attached to the given campaign.
     */
    private List<MailRecipient> buildRecipients(MailCampaign campaign, int count) {
        List<MailRecipient> recipients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            recipients.add(MailRecipient.builder()
                    .campaign(campaign)
                    .email("recipient" + i + "@example.com")
                    .name("Recipient " + i)
                    .invoiceNumber("INV-" + String.format("%04d", i))
                    .currency("USD")
                    .build());
        }
        return recipients;
    }
}

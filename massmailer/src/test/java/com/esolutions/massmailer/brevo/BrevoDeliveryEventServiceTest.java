package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.brevo.BrevoDeliveryEventService.Kind;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.model.CampaignStatus;
import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.model.MailRecipient;
import com.esolutions.massmailer.model.MailRecipient.RecipientStatus;
import com.esolutions.massmailer.repository.CampaignRepository;
import com.esolutions.massmailer.repository.RecipientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BrevoDeliveryEventServiceTest {

    private static final String MSG = "<202610060413.1@smtp-relay.mailin.fr>";

    private final ObjectMapper json = new ObjectMapper();
    private RecipientRepository recipients;
    private CampaignRepository campaigns;
    private TaskScheduler scheduler;
    private BrevoDeliveryEventService service;

    @BeforeEach
    void setUp() {
        recipients = mock(RecipientRepository.class);
        campaigns = mock(CampaignRepository.class);
        scheduler = mock(TaskScheduler.class);
        var props = new MailerProperties("no-reply@platform.test", "Platform",
                0, 0, 3, 0, false, null, 0, null);
        service = new BrevoDeliveryEventService(recipients, campaigns,
                TransactionOperations.withoutTransaction(), scheduler, props);
    }

    private MailRecipient sentRecipient(MailCampaign campaign) {
        var r = MailRecipient.builder()
                .campaign(campaign).email("john@example.com").invoiceNumber("INV-1")
                .deliveryStatus(RecipientStatus.SENT).messageId(MSG).build();
        when(recipients.findFirstByMessageId(MSG)).thenReturn(Optional.of(r));
        return r;
    }

    private MailCampaign completedCampaign() {
        return MailCampaign.builder().name("c").subject("s").templateName("invoice")
                .status(CampaignStatus.COMPLETED).totalRecipients(2).sentCount(2).build();
    }

    @Test
    void classifiesWebhookAndStatisticsApiEventNames() {
        assertThat(BrevoDeliveryEventService.classify("hard_bounce")).isEqualTo(Kind.PERMANENT_FAILURE);
        assertThat(BrevoDeliveryEventService.classify("hardBounces")).isEqualTo(Kind.PERMANENT_FAILURE);
        assertThat(BrevoDeliveryEventService.classify("invalid_email")).isEqualTo(Kind.PERMANENT_FAILURE);
        assertThat(BrevoDeliveryEventService.classify("blocked")).isEqualTo(Kind.PERMANENT_FAILURE);
        assertThat(BrevoDeliveryEventService.classify("error")).isEqualTo(Kind.PERMANENT_FAILURE);
        assertThat(BrevoDeliveryEventService.classify("soft_bounce")).isEqualTo(Kind.TRANSIENT);
        assertThat(BrevoDeliveryEventService.classify("deferred")).isEqualTo(Kind.TRANSIENT);
        assertThat(BrevoDeliveryEventService.classify("delivered")).isEqualTo(Kind.DELIVERED);
        assertThat(BrevoDeliveryEventService.classify("spam")).isEqualTo(Kind.COMPLAINT);
        assertThat(BrevoDeliveryEventService.classify("opened")).isEqualTo(Kind.IGNORED);
        assertThat(BrevoDeliveryEventService.classify("request")).isEqualTo(Kind.IGNORED);
    }

    @Test
    void hardBounceWebhookMarksSentRecipientFailedAndAdjustsCampaign() throws Exception {
        var campaign = completedCampaign();
        var r = sentRecipient(campaign);

        int n = service.handle(json.readTree("""
                {"event":"hard_bounce","email":"john@example.com","message-id":"%s",
                 "reason":"550 mailbox unavailable","ts_event":1598034509}""".formatted(MSG)));

        assertThat(n).isEqualTo(1);
        assertThat(r.getDeliveryStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(r.getErrorMessage()).isEqualTo("Brevo hard_bounce: 550 mailbox unavailable");
        assertThat(r.getRetryCount()).isEqualTo(3); // excluded from campaign retry
        assertThat(campaign.getSentCount()).isEqualTo(1);
        assertThat(campaign.getFailedCount()).isEqualTo(1);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PARTIALLY_FAILED);
        verify(recipients).save(r);
        verify(campaigns).save(campaign);
    }

    @Test
    void repeatedFailureEventIsIdempotent() throws Exception {
        var campaign = completedCampaign();
        sentRecipient(campaign);
        var event = json.readTree("""
                {"event":"blocked","email":"john@example.com","message-id":"%s"}""".formatted(MSG));

        service.handle(event);
        service.handle(event);

        assertThat(campaign.getSentCount()).isEqualTo(1);
        assertThat(campaign.getFailedCount()).isEqualTo(1);
    }

    @Test
    void statisticsApiBatchWithMessageIdFieldIsApplied() throws Exception {
        var campaign = completedCampaign();
        var r = sentRecipient(campaign);

        int n = service.handle(json.readTree("""
                [{"event":"requests","email":"john@example.com","messageId":"%1$s"},
                 {"event":"error","email":"john@example.com","messageId":"%1$s",
                  "reason":"Sending has been rejected because the sender you used is not valid"}]
                """.formatted(MSG)), false);

        assertThat(n).isEqualTo(2);
        assertThat(r.getDeliveryStatus()).isEqualTo(RecipientStatus.FAILED);
        assertThat(r.getErrorMessage()).startsWith("Brevo error: Sending has been rejected");
    }

    @Test
    void deliveredAndTransientEventsLeaveRecipientSent() throws Exception {
        var campaign = completedCampaign();
        var r = sentRecipient(campaign);

        service.handle(json.readTree("""
                [{"event":"delivered","email":"john@example.com","message-id":"%1$s"},
                 {"event":"soft_bounce","email":"john@example.com","message-id":"%1$s"},
                 {"event":"spam","email":"john@example.com","message-id":"%1$s"}]
                """.formatted(MSG)));

        assertThat(r.getDeliveryStatus()).isEqualTo(RecipientStatus.SENT);
        assertThat(campaign.getFailedCount()).isZero();
        verify(recipients, never()).save(any());
    }

    @Test
    void unmatchedFailureFromWebhookIsRetriedLater() throws Exception {
        when(recipients.findFirstByMessageId(anyString())).thenReturn(Optional.empty());

        service.handle(json.readTree("""
                {"event":"hard_bounce","email":"x@example.com","message-id":"<unknown@relay>"}"""));

        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void unmatchedFailureFromReconcilerIsNotRetried() throws Exception {
        when(recipients.findFirstByMessageId(anyString())).thenReturn(Optional.empty());

        service.handle(json.readTree("""
                {"event":"error","email":"x@example.com","messageId":"<unknown@relay>"}"""), false);

        verifyNoInteractions(scheduler);
    }

    @Test
    void unmatchedDeliveredEventIsNotRetried() throws Exception {
        when(recipients.findFirstByMessageId(anyString())).thenReturn(Optional.empty());

        service.handle(json.readTree("""
                {"event":"delivered","email":"x@example.com","message-id":"<single-send@relay>"}"""));

        verifyNoInteractions(scheduler);
    }
}

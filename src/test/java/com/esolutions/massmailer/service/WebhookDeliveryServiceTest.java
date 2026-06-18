package com.esolutions.massmailer.service;

import com.esolutions.massmailer.model.CampaignStatus;
import com.esolutions.massmailer.model.MailCampaign;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for the campaign completion webhook delivery service.
 */
class WebhookDeliveryServiceTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);

    @Test
    void sendsSignedWebhookOnCampaignCompletion() throws Exception {
        WebhookDeliveryService service = new WebhookDeliveryService(
                restTemplate, objectMapper, "super-secret-webhook-key-32chars");

        UUID campaignId = UUID.randomUUID();
        var campaign = MailCampaign.builder()
                .id(campaignId)
                .name("Test Campaign")
                .status(CampaignStatus.COMPLETED)
                .totalRecipients(10)
                .sentCount(9)
                .failedCount(1)
                .skippedCount(0)
                .callbackUrl("https://example.com/webhooks/campaign")
                .build();

        mockServer.expect(requestTo("https://example.com/webhooks/campaign"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Webhook-Event", "campaign.completed"))
                .andExpect(header("X-Webhook-Signature", org.hamcrest.Matchers.notNullValue()))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{\"received\":true}", MediaType.APPLICATION_JSON));

        service.sendCampaignCompleted(campaign);

        mockServer.verify();
    }

    @Test
    void skipsWebhookWhenCallbackUrlIsMissing() {
        WebhookDeliveryService service = new WebhookDeliveryService(
                restTemplate, objectMapper, "super-secret-webhook-key-32chars");

        var campaign = MailCampaign.builder()
                .id(UUID.randomUUID())
                .name("No Callback")
                .status(CampaignStatus.COMPLETED)
                .build();

        service.sendCampaignCompleted(campaign);
        // No exception, no network call
        mockServer.verify(); // verifies no expectations were set, so no calls made
    }

    @Test
    void skipsWebhookWhenSecretIsMissing() {
        WebhookDeliveryService service = new WebhookDeliveryService(
                restTemplate, objectMapper, "");

        var campaign = MailCampaign.builder()
                .id(UUID.randomUUID())
                .name("No Secret")
                .status(CampaignStatus.COMPLETED)
                .callbackUrl("https://example.com/webhooks/campaign")
                .build();

        service.sendCampaignCompleted(campaign);
        mockServer.verify();
    }
}

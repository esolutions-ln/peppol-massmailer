package com.esolutions.massmailer.service;

import com.esolutions.massmailer.model.MailCampaign;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers signed webhook callbacks to customer ERPs when campaigns complete.
 *
 * <p>Security:</p>
 * <ul>
 *   <li>Payload signed with HMAC-SHA256 using a server-side secret</li>
 *   <li>Signature delivered in {@code X-Webhook-Signature} header</li>
 *   <li>Event type in {@code X-Webhook-Event} header</li>
 * </ul>
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);
    private static final String HMAC_ALGO = "HmacSHA256";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookDeliveryService(RestTemplate restTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${webhook.secret:}") String webhookSecret) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Sends a campaign-completed webhook to the callback URL registered on the campaign.
     * Best-effort: exceptions are swallowed and logged; the caller still has polling.
     */
    public void sendCampaignCompleted(MailCampaign campaign) {
        if (campaign.getCallbackUrl() == null || campaign.getCallbackUrl().isBlank()) {
            return;
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook secret not configured — skipping campaign completion webhook for campaign {}",
                    campaign.getId());
            return;
        }

        try {
            var payload = new CampaignWebhookPayload(
                    campaign.getId(),
                    campaign.getName(),
                    campaign.getStatus().name(),
                    campaign.getTotalRecipients(),
                    campaign.getSentCount(),
                    campaign.getFailedCount(),
                    campaign.getSkippedCount(),
                    campaign.getCompletedAt() != null ? campaign.getCompletedAt().toString() : Instant.now().toString()
            );

            String bodyJson = objectMapper.writeValueAsString(payload);
            String signature = sign(bodyJson);

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Signature", signature);
            headers.set("X-Webhook-Event", "campaign.completed");
            headers.set("User-Agent", "MassMailer-Webhook/1.0");

            var request = new HttpEntity<>(bodyJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    campaign.getCallbackUrl(),
                    HttpMethod.POST,
                    request,
                    String.class
            );

            log.info("Webhook delivered for campaign {} → {} (status={})",
                    campaign.getId(), campaign.getCallbackUrl(), response.getStatusCode());

        } catch (ResourceAccessException e) {
            log.warn("Webhook delivery timed out for campaign {} → {}",
                    campaign.getId(), campaign.getCallbackUrl());
        } catch (Exception e) {
            log.error("Webhook delivery failed for campaign {}: {}",
                    campaign.getId(), e.getMessage());
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign webhook payload", e);
        }
    }

    /**
     * Payload sent to the customer's callback URL when a campaign finishes.
     */
    public record CampaignWebhookPayload(
            UUID campaignId,
            String campaignName,
            String status,
            int totalRecipients,
            int sent,
            int failed,
            int skipped,
            String completedAt
    ) {}
}

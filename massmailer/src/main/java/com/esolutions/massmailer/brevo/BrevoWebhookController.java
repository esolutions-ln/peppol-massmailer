package com.esolutions.massmailer.brevo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Receives Brevo transactional email events (delivered, hardBounce, blocked, …).
 *
 * <pre>
 *   POST https://ap.invoicedirect.biz/webhooks/brevo/transactional
 *   Authorization: Bearer ${BREVO_WEBHOOK_TOKEN}     (or ?token=… on the URL)
 * </pre>
 *
 * Register it with {@code scripts/register-brevo-webhook.sh}. {@code /webhooks/**} is
 * public in SecurityConfig, so the shared token is the only authentication: requests
 * are rejected unless {@code BREVO_WEBHOOK_TOKEN} is configured and matches.
 */
@RestController
@RequestMapping("/webhooks/brevo")
@Tag(name = "Brevo Webhooks")
public class BrevoWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BrevoWebhookController.class);

    private final BrevoDeliveryEventService events;
    private final ObjectMapper json;
    private final byte[] expectedToken;

    public BrevoWebhookController(BrevoDeliveryEventService events,
                                  ObjectMapper json,
                                  @Value("${massmailer.brevo.webhook-token:}") String token) {
        this.events = events;
        this.json = json;
        this.expectedToken = token == null || token.isBlank()
                ? null : token.getBytes(StandardCharsets.UTF_8);
        if (this.expectedToken == null) {
            log.warn("BREVO_WEBHOOK_TOKEN is not set — /webhooks/brevo/transactional will reject all events");
        }
    }

    @PostMapping("/transactional")
    @Operation(summary = "Brevo transactional event webhook",
            description = "Updates invoice recipients from Brevo delivery events. Requires "
                    + "`Authorization: Bearer <BREVO_WEBHOOK_TOKEN>` or `?token=`.")
    public ResponseEntity<Map<String, Object>> transactional(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String queryToken,
            @RequestBody String body) {

        if (expectedToken == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Brevo webhook token not configured"));
        }
        String presented = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : queryToken;
        if (presented == null
                || !MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected Brevo webhook call with missing/invalid token");
            return ResponseEntity.status(401).body(Map.of("error", "invalid token"));
        }

        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid JSON"));
        }
        int received = events.handle(payload);
        return ResponseEntity.ok(Map.of("received", received));
    }
}

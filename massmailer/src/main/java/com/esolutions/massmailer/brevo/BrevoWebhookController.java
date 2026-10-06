package com.esolutions.massmailer.brevo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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

    @PostMapping(value = "/transactional", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Receive Brevo transactional delivery events",
            security = @SecurityRequirement(name = "BrevoWebhookToken"),
            description = """
                    Called by **Brevo**, not by integrators. Register once per environment with \
                    `scripts/register-brevo-webhook.sh` (bearer auth with `BREVO_WEBHOOK_TOKEN`).

                    Accepts a single event object or an array (Brevo *batched* webhooks). Each \
                    event is matched to an invoice recipient by its `message-id` (the `messageId` \
                    returned when the email was sent):

                    | Event | Effect on recipient |
                    |---|---|
                    | `hard_bounce`, `invalid_email`, `blocked`, `error` | `SENT` → `FAILED`, Brevo reason stored in `errorMessage`, excluded from campaign retry, campaign `sent`/`failed` adjusted |
                    | `delivered` | none (confirms `SENT`) |
                    | `soft_bounce`, `deferred` | none (Brevo keeps retrying) |
                    | `spam`, `unsubscribed` | none — logged as a complaint |
                    | `request`, `opened`, `click`, … | ignored |

                    Idempotent: repeated events are harmless. Brevo cannot push `error` events \
                    (e.g. rejected/unverified sender) by webhook; a 15-minute reconciliation \
                    against Brevo's events API applies those.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Events accepted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"received\": 1}"))),
            @ApiResponse(responseCode = "400", description = "Body is not valid JSON",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"error\": \"invalid JSON\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing or wrong token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"error\": \"invalid token\"}"))),
            @ApiResponse(responseCode = "503", description = "BREVO_WEBHOOK_TOKEN not configured on the server",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"error\": \"Brevo webhook token not configured\"}")))
    })
    public ResponseEntity<Map<String, Object>> transactional(
            @Parameter(hidden = true)
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Parameter(description = "Alternative to the bearer header: the BREVO_WEBHOOK_TOKEN value")
            @RequestParam(value = "token", required = false) String queryToken,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "One Brevo transactional event, or an array of them",
                    required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            examples = {
                                    @ExampleObject(name = "Hard bounce", value = """
                                            {
                                              "event": "hard_bounce",
                                              "email": "customer@acmecorp.co.zw",
                                              "id": 26224,
                                              "date": "2026-10-06 10:25:03",
                                              "ts_event": 1791275103,
                                              "message-id": "<202610060825.58962143428@smtp-relay.mailin.fr>",
                                              "subject": "Your Invoice INV-2026-0042",
                                              "reason": "550 5.1.1 mailbox does not exist",
                                              "tags": ["invoice"]
                                            }
                                            """),
                                    @ExampleObject(name = "Batched", value = """
                                            [
                                              {"event": "delivered", "email": "a@acme.co.zw",
                                               "message-id": "<202610060825.1@smtp-relay.mailin.fr>"},
                                              {"event": "blocked", "email": "b@acme.co.zw",
                                               "message-id": "<202610060825.2@smtp-relay.mailin.fr>",
                                               "reason": "blocked by recipient server"}
                                            ]
                                            """)
                            }))
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

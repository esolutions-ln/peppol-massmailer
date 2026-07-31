package com.esolutions.massmailer.infrastructure.adapters.sage;

import com.esolutions.massmailer.infrastructure.adapters.sage.model.SageEInvoiceStatus;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Webhook endpoint for Sage Network e-Invoice status push notifications.
 *
 * Configure this URL in the Sage Network portal as your webhook endpoint:
 *   POST https://ap.invoicedirect.biz/webhooks/sage/einvoice-status/{orgId}
 *
 * Sage Network sends a POST with the same JSON structure as the
 * GET /v1/e-invoices/{id} response whenever the e-invoice status changes.
 *
 * Status transitions that trigger a webhook:
 *   New → Sending → Sent → Acknowledged
 *                        → Rejected
 *              → Failed
 *
 * Security: Sage signs webhook payloads with HMAC-SHA256.
 * The X-Sage-Signature header contains the signature.
 * TODO: Implement signature verification using the webhook secret.
 */
@RestController
@RequestMapping("/webhooks/sage")
@Tag(name = "Sage Network Webhooks")
@ConditionalOnBean(SageNetworkAdapter.class)
public class SageNetworkWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SageNetworkWebhookController.class);

    private final SageNetworkAdapter sageNetworkAdapter;
    private final PeppolDeliveryRecordRepository deliveryRepo;

    public SageNetworkWebhookController(SageNetworkAdapter sageNetworkAdapter,
                                         PeppolDeliveryRecordRepository deliveryRepo) {
        this.sageNetworkAdapter = sageNetworkAdapter;
        this.deliveryRepo = deliveryRepo;
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /webhooks/sage/einvoice-status/{orgId}
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Receive Sage Network e-invoice status update",
            description = """
                    Webhook endpoint for Sage Network push notifications.
                    Configure this URL in the Sage Network portal.

                    Sage sends a POST whenever an e-invoice status changes:
                    `New → Sending → Sent → Acknowledged` (or `Rejected` / `Failed`)

                    The payload is the same JSON structure as
                    `GET /v1/e-invoices/{id}`.

                    **Configure in Sage Network portal:**
                    ```
                    Webhook URL: https://ap.invoicedirect.biz/webhooks/sage/einvoice-status/{orgId}
                    Events: e-invoice.status.changed
                    ```
                    """
    )
    @PostMapping(
            value = "/einvoice-status/{orgId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveStatusUpdate(
            @PathVariable UUID orgId,
            @RequestBody SageEInvoiceStatus payload,
            @RequestHeader(value = "X-Sage-Signature", required = false) String signature) {

        log.info("Sage Network webhook received: orgId={} eInvoiceId={} erpInvoiceId={} status={}",
                orgId,
                payload.eInvoiceId(),
                payload.erpInvoiceId(),
                payload.currentStatus() != null ? payload.currentStatus().status() : "unknown");

        // TODO: Verify HMAC-SHA256 signature from X-Sage-Signature header
        // String expectedSig = hmacSha256(webhookSecret, requestBody);
        // if (!expectedSig.equals(signature)) return ResponseEntity.status(401).build();

        // Process the status update
        sageNetworkAdapter.processWebhook(payload, orgId);

        // Return 200 immediately — Sage retries on non-2xx
        return ResponseEntity.ok(Map.of(
                "received", true,
                "eInvoiceId", payload.eInvoiceId() != null ? payload.eInvoiceId() : "unknown",
                "erpInvoiceId", payload.erpInvoiceId() != null ? payload.erpInvoiceId() : "unknown",
                "status", payload.currentStatus() != null ? payload.currentStatus().status() : "unknown"
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /webhooks/sage/einvoice-status/{orgId}/poll
    //  Manual status poll — trigger a sync for a specific invoice
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Manually poll Sage Network for e-invoice status",
            description = """
                    Triggers an immediate status sync from Sage Network for a specific invoice.
                    Useful for debugging or when a webhook was missed.

                    Provide either `eInvoiceId` (Sage's UUID) or `invoiceNumber` (your ERP invoice ID).
                    """
    )
    @PostMapping(
            value = "/einvoice-status/{orgId}/poll",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pollStatus(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String eInvoiceId,
            @RequestParam(required = false) String invoiceNumber) {

        if (eInvoiceId == null && invoiceNumber == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Provide either eInvoiceId or invoiceNumber"));
        }

        String lookupInvoiceNumber = invoiceNumber != null ? invoiceNumber : eInvoiceId;
        var result = sageNetworkAdapter.syncStatus(orgId, lookupInvoiceNumber, eInvoiceId);

        return result.map(record -> ResponseEntity.ok(Map.<String, Object>of(
                "invoiceNumber", record.getInvoiceNumber(),
                "status", record.getStatus().name(),
                "senderParticipantId", record.getSenderParticipantId(),
                "receiverParticipantId", record.getReceiverParticipantId(),
                "deliveryReceipt", record.getDeliveryReceipt() != null ? record.getDeliveryReceipt() : "",
                "errorMessage", record.getErrorMessage() != null ? record.getErrorMessage() : "",
                "retryCount", record.getRetryCount(),
                "acknowledgedAt", record.getAcknowledgedAt() != null ? record.getAcknowledgedAt().toString() : null
        ))).orElse(ResponseEntity.notFound().build());
    }
}

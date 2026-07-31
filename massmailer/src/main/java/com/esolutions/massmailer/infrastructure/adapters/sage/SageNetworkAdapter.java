package com.esolutions.massmailer.infrastructure.adapters.sage;

import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterProperties;
import com.esolutions.massmailer.infrastructure.adapters.sage.model.SageEInvoiceStatus;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sage Network e-Invoice status adapter.
 *
 * Calls the Sage Network REST API to retrieve e-invoice delivery status
 * and syncs the result into our {@link PeppolDeliveryRecord}.
 *
 * <h3>Sage Network API</h3>
 * <pre>
 * Base URL: https://api.sageone.com/network/v1   (or configured base-url)
 *
 * GET  /v1/e-invoices/{eInvoiceId}          — get status for one e-invoice
 * GET  /v1/e-invoices?erpInvoiceId={id}     — look up by ERP invoice ID
 * POST /v1/e-invoices                       — submit a new e-invoice
 * </pre>
 *
 * <h3>Authentication</h3>
 * Bearer token (OAuth 2.0 client credentials):
 * <pre>
 * POST https://oauth.sageone.com/oauth2/token
 * grant_type=client_credentials
 * client_id={sage-client-id}
 * client_secret={sage-client-secret}
 * scope=network
 * </pre>
 *
 * <h3>Status Lifecycle</h3>
 * <pre>
 * New → Sending → Sent → Acknowledged   (happy path)
 *                      → Rejected        (buyer rejected)
 *             → Failed                   (transmission error)
 * </pre>
 *
 * Activated when {@code erp.sage.network-base-url} is configured.
 */
@Component
@ConditionalOnProperty(prefix = "erp.sage", name = "network-base-url")
public class SageNetworkAdapter {

    private static final Logger log = LoggerFactory.getLogger(SageNetworkAdapter.class);

    private final ErpAdapterProperties.SageConfig config;
    private final PeppolDeliveryRecordRepository deliveryRepo;
    private final RestTemplate restTemplate;

    public SageNetworkAdapter(ErpAdapterProperties props,
                               PeppolDeliveryRecordRepository deliveryRepo,
                               RestTemplate restTemplate) {
        this.config = props.sage();
        this.deliveryRepo = deliveryRepo;
        this.restTemplate = restTemplate;
        log.info("Sage Network adapter initialised — networkBaseUrl={}", config.networkBaseUrl());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Fetch status by Sage e-Invoice ID
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetches the current e-invoice status from Sage Network by Sage's own e-invoice UUID.
     *
     * @param eInvoiceId  Sage Network e-invoice UUID (from the delivery record or webhook)
     * @return the parsed status response
     */
    public Optional<SageEInvoiceStatus> fetchStatus(String eInvoiceId) {
        String url = config.networkBaseUrl() + "/v1/e-invoices/" + eInvoiceId;
        log.debug("Fetching Sage e-invoice status: {}", url);

        try {
            var response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    SageEInvoiceStatus.class);

            return Optional.ofNullable(response.getBody());

        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Sage e-invoice not found: {}", eInvoiceId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to fetch Sage e-invoice status for {}: {}", eInvoiceId, e.getMessage());
            throw new SageNetworkException("Failed to fetch e-invoice status: " + e.getMessage(), e);
        }
    }

    /**
     * Looks up e-invoice status by the ERP's own invoice ID (e.g. INV-2026-0042).
     * Sage Network stores the erpInvoiceId we submitted with the document.
     */
    public Optional<SageEInvoiceStatus> fetchStatusByErpInvoiceId(String erpInvoiceId) {
        String url = config.networkBaseUrl() + "/v1/e-invoices?erpInvoiceId=" + erpInvoiceId;
        log.debug("Looking up Sage e-invoice by ERP ID: {}", erpInvoiceId);

        try {
            var response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    SageEInvoiceStatusListResponse.class);

            if (response.getBody() == null || response.getBody().data() == null
                    || response.getBody().data().isEmpty()) {
                return Optional.empty();
            }

            // Return the most recent one
            var first = response.getBody().data().getFirst();
            // Wrap in the single-item response shape
            return Optional.of(new SageEInvoiceStatus(
                    new SageEInvoiceStatus.Data(first.type(), first.id(), first.attributes())));

        } catch (Exception e) {
            log.error("Failed to look up Sage e-invoice by ERP ID {}: {}", erpInvoiceId, e.getMessage());
            return Optional.empty();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Sync status into PeppolDeliveryRecord
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetches the latest status from Sage Network and updates the matching
     * {@link PeppolDeliveryRecord} in our database.
     *
     * Called by:
     *   - The status polling job (for non-terminal records)
     *   - The webhook handler (on push notification from Sage)
     *
     * @param organizationId  the org that submitted the invoice
     * @param invoiceNumber   our invoice number (used to find the delivery record)
     * @param eInvoiceId      Sage Network's UUID for this e-invoice (may be null — will look up by invoiceNumber)
     * @return the updated delivery record, or empty if not found
     */
    public Optional<PeppolDeliveryRecord> syncStatus(UUID organizationId,
                                                      String invoiceNumber,
                                                      String eInvoiceId) {
        // Find our delivery record
        var recordOpt = deliveryRepo.findByInvoiceNumberAndOrganizationId(invoiceNumber, organizationId);
        if (recordOpt.isEmpty()) {
            log.warn("No PeppolDeliveryRecord found for invoice={} org={}", invoiceNumber, organizationId);
            return Optional.empty();
        }

        var record = recordOpt.get();

        // Skip if already in a terminal state
        if (record.getStatus() == PeppolDeliveryRecord.DeliveryStatus.DELIVERED
                || record.getStatus() == PeppolDeliveryRecord.DeliveryStatus.FAILED) {
            log.debug("Skipping sync for terminal record: invoice={} status={}", invoiceNumber, record.getStatus());
            return Optional.of(record);
        }

        // Fetch from Sage Network
        Optional<SageEInvoiceStatus> statusOpt = eInvoiceId != null
                ? fetchStatus(eInvoiceId)
                : fetchStatusByErpInvoiceId(invoiceNumber);

        if (statusOpt.isEmpty()) {
            log.warn("No Sage e-invoice status found for invoice={}", invoiceNumber);
            return Optional.of(record);
        }

        SageEInvoiceStatus sageStatus = statusOpt.get();
        SageEInvoiceStatus.StatusEntry current = sageStatus.currentStatus();

        if (current == null) {
            return Optional.of(record);
        }

        // Map Sage status → our delivery status
        var newStatus = current.toDeliveryStatus();
        var oldStatus = record.getStatus();

        if (newStatus != oldStatus) {
            log.info("Sage e-invoice status change: invoice={} {} → {} (Sage: {})",
                    invoiceNumber, oldStatus, newStatus, current.status());

            record.setStatus(newStatus);

            if (newStatus == PeppolDeliveryRecord.DeliveryStatus.DELIVERED) {
                record.setDeliveryReceipt("Sage Network: " + current.status()
                        + (current.notes() != null ? " — " + current.notes() : ""));
                record.setAcknowledgedAt(Instant.now());
            } else if (newStatus == PeppolDeliveryRecord.DeliveryStatus.FAILED) {
                String error = current.status();
                if (current.rejectionReason() != null) error += ": " + current.rejectionReason();
                if (current.notes() != null) error += " (" + current.notes() + ")";
                record.setErrorMessage("Sage Network: " + error);
                record.setRetryCount(record.getRetryCount() + 1);
            }

            // Store the Sage e-invoice ID for future lookups
            if (sageStatus.eInvoiceId() != null && record.getDeliveryReceipt() == null) {
                record.setDeliveryReceipt("sageEInvoiceId=" + sageStatus.eInvoiceId());
            }

            deliveryRepo.save(record);
        }

        return Optional.of(record);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Process webhook payload from Sage Network
    // ═══════════════════════════════════════════════════════════════

    /**
     * Processes a status update pushed by Sage Network via webhook.
     * Sage sends the same JSON structure as the GET /v1/e-invoices/{id} response.
     *
     * @param payload  the parsed webhook payload
     * @param orgId    the organization this webhook is for (from URL path or header)
     */
    public void processWebhook(SageEInvoiceStatus payload, UUID orgId) {
        String erpInvoiceId = payload.erpInvoiceId();
        String eInvoiceId = payload.eInvoiceId();

        if (erpInvoiceId == null || erpInvoiceId.isBlank()) {
            log.warn("Sage webhook received with no erpInvoiceId — eInvoiceId={}", eInvoiceId);
            return;
        }

        log.info("Processing Sage Network webhook: erpInvoiceId={} eInvoiceId={} status={}",
                erpInvoiceId, eInvoiceId,
                payload.currentStatus() != null ? payload.currentStatus().status() : "unknown");

        syncStatus(orgId, erpInvoiceId, eInvoiceId);
    }

    // ── Helpers ──

    private HttpHeaders buildHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (config.networkApiKey() != null && !config.networkApiKey().isBlank()) {
            headers.setBearerAuth(config.networkApiKey());
        }
        return headers;
    }

    /** Wrapper for the list response from GET /v1/e-invoices?erpInvoiceId=... */
    record SageEInvoiceStatusListResponse(
            List<SageEInvoiceStatus.Data> data
    ) {}

    public static class SageNetworkException extends RuntimeException {
        public SageNetworkException(String message, Throwable cause) { super(message, cause); }
    }
}

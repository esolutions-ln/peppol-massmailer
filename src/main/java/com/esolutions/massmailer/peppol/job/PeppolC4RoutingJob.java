package com.esolutions.massmailer.peppol.job;

import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.model.InboundDocument.RoutingStatus;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Scheduled job that polls {@code InboundDocument} records in {@code RECEIVED} status
 * and forwards them to the receiving organisation's configured C4 ERP webhook.
 *
 * <p>Implements Requirements 10.1 – 10.9.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeppolC4RoutingJob {

    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 50;

    private final InboundDocumentRepository inboundDocumentRepository;
    private final OrganizationRepository organizationRepository;
    private final RestTemplate restTemplate;

    /**
     * Runs every 30 seconds (fixed delay after previous execution completes).
     * Fetches up to 50 pending documents and attempts C4 webhook delivery for each.
     */
    @Scheduled(fixedDelay = 30_000)
    public void routePendingDocuments() {
        List<InboundDocument> pending = inboundDocumentRepository
                .findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
                        RoutingStatus.RECEIVED, MAX_RETRIES, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.debug("C4 routing job: processing {} pending document(s)", pending.size());

        for (InboundDocument doc : pending) {
            if (doc.getId() == null) {
                log.warn("C4 routing: skipping document with null id");
                continue;
            }
            processSingleDocument(doc.getId());
        }
    }

    /**
     * Processes a single inbound document in its own transaction so that
     * failures are isolated and do not roll back other documents in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleDocument(java.util.UUID documentId) {
        InboundDocument doc = inboundDocumentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("C4 routing: document {} not found, skipping", documentId);
            return;
        }

        Organization org = organizationRepository.findById(doc.getReceiverOrganizationId()).orElse(null);

        // Requirement 10.8: skip (no retry increment) when webhook URL is absent
        if (org == null || isBlank(org.getC4WebhookUrl())) {
            log.warn("C4 routing: no c4WebhookUrl configured for org {} (document {}), skipping",
                    doc.getReceiverOrganizationId(), doc.getId());
            return;
        }

        String webhookUrl = org.getC4WebhookUrl();
        String authToken = org.getC4WebhookAuthToken();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_XML);
            if (!isBlank(authToken)) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
            }

            HttpEntity<String> request = new HttpEntity<>(doc.getUblXmlPayload(), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                // Requirement 10.4: mark delivered on HTTP 2xx
                doc.markRoutedToC4(webhookUrl, response.getBody());
                inboundDocumentRepository.save(doc);
                log.info("C4 routing: document {} delivered to {} (status {})",
                        doc.getId(), webhookUrl, response.getStatusCode().value());
            } else {
                // Non-2xx treated as failure
                handleRoutingFailure(doc, "Non-2xx response: " + response.getStatusCode().value(), webhookUrl);
            }
        } catch (Exception ex) {
            // Requirement 10.5: increment retry count and mark failed on any error
            handleRoutingFailure(doc, ex.getMessage(), webhookUrl);
        }
    }

    private void handleRoutingFailure(InboundDocument doc, String error, String webhookUrl) {
        doc.markRoutingFailed(error);
        inboundDocumentRepository.save(doc);

        // Requirement 10.6: log permanent failure when retry limit reached
        if (doc.getRoutingRetryCount() >= MAX_RETRIES) {
            log.error("C4 routing: permanent failure for document {} (org {}) after {} attempts — endpoint: {}, error: {}",
                    doc.getId(), doc.getReceiverOrganizationId(), doc.getRoutingRetryCount(), webhookUrl, error);
        } else {
            log.warn("C4 routing: transient failure for document {} (attempt {}): {}",
                    doc.getId(), doc.getRoutingRetryCount(), error);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

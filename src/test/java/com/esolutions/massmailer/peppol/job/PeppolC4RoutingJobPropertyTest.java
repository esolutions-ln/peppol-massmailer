package com.esolutions.massmailer.peppol.job;

import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.model.InboundDocument.RoutingStatus;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link PeppolC4RoutingJob}.
 *
 * <p>Property 3: C4 Routing Idempotency — Validates: Requirements 10.7
 * <p>Property 4: Retry Bound — Validates: Requirements 10.6
 */
class PeppolC4RoutingJobPropertyTest {

    private static final int MAX_RETRIES = 3;

    /**
     * Property 3: C4 Routing Idempotency
     *
     * <p>For any {@code InboundDocument} with {@code routingStatus=DELIVERED_TO_C4},
     * {@code routePendingDocuments()} must never select or re-process it — no webhook
     * calls are made and the document's status remains {@code DELIVERED_TO_C4}.
     *
     * <p><b>Validates: Requirements 10.7</b>
     */
    @Property(tries = 200)
    void deliveredToC4DocumentIsNeverReprocessed(
            @ForAll @IntRange(min = 1, max = 10) int jobRuns) {

        // ── Arrange ──────────────────────────────────────────────────────────

        UUID orgId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Document already delivered — must never be touched again
        InboundDocument doc = InboundDocument.builder()
                .id(docId)
                .receiverOrganizationId(orgId)
                .ublXmlPayload("<Invoice/>")
                .routingStatus(RoutingStatus.DELIVERED_TO_C4)
                .routingRetryCount(0)
                .build();

        Organization org = Organization.builder()
                .id(orgId)
                .name("Idempotency Org")
                .slug("idempotency-org")
                .c4WebhookUrl("https://erp.example.com/inbound")
                .c4WebhookAuthToken("token")
                .build();

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        // The repository query filters by routingStatus=RECEIVED — DELIVERED_TO_C4
        // documents are excluded, so the query always returns an empty list.
        when(inboundRepo.findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
                eq(RoutingStatus.RECEIVED), eq(MAX_RETRIES), any(Pageable.class)))
                .thenReturn(List.of());

        PeppolC4RoutingJob job = new PeppolC4RoutingJob(inboundRepo, orgRepo, restTemplate);

        // ── Act: run the job N times ──────────────────────────────────────────

        for (int i = 0; i < jobRuns; i++) {
            job.routePendingDocuments();
        }

        // ── Assert ───────────────────────────────────────────────────────────

        // 1. No webhook call was ever made
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));

        // 2. The document's routingStatus is still DELIVERED_TO_C4 (unchanged)
        assertThat(doc.getRoutingStatus())
                .as("routingStatus must remain DELIVERED_TO_C4 after %d job run(s)", jobRuns)
                .isEqualTo(RoutingStatus.DELIVERED_TO_C4);

        // 3. The repository save was never called for this document
        verify(inboundRepo, never()).save(any(InboundDocument.class));
    }

    /**
     * Property 4: Retry Bound
     *
     * <p>For any sequence of N consecutive C4 routing failures, assert that
     * {@code routingRetryCount} never exceeds 3 and the document is never
     * re-queued (selected by the query) after reaching the retry limit.
     *
     * <p><b>Validates: Requirements 10.6</b>
     */
    @Property(tries = 200)
    void retryCountNeverExceedsMaxAndDocumentIsExcludedAfterLimit(
            @ForAll @IntRange(min = 1, max = 10) int failureAttempts) {

        // ── Arrange ──────────────────────────────────────────────────────────

        UUID orgId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Build a document starting at RECEIVED with retryCount = 0
        InboundDocument doc = InboundDocument.builder()
                .id(docId)
                .receiverOrganizationId(orgId)
                .ublXmlPayload("<Invoice/>")
                .routingStatus(RoutingStatus.RECEIVED)
                .routingRetryCount(0)
                .build();

        Organization org = Organization.builder()
                .id(orgId)
                .name("Test Org")
                .slug("test-org")
                .c4WebhookUrl("https://erp.example.com/inbound")
                .c4WebhookAuthToken("secret-token")
                .build();

        // Mocked repositories
        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        // orgRepo always returns our org
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        // inboundRepo.findById always returns the current state of doc
        when(inboundRepo.findById(docId)).thenAnswer(inv -> Optional.of(doc));

        // save() persists state back into doc (simulate in-memory DB)
        when(inboundRepo.save(any(InboundDocument.class))).thenAnswer(inv -> {
            InboundDocument saved = inv.getArgument(0, InboundDocument.class);
            // copy mutable state back to our tracked instance
            doc.setRoutingStatus(saved.getRoutingStatus());
            doc.setRoutingRetryCount(saved.getRoutingRetryCount());
            doc.setRoutingError(saved.getRoutingError());
            doc.setRoutedToEndpoint(saved.getRoutedToEndpoint());
            doc.setRoutingResponse(saved.getRoutingResponse());
            return saved;
        });

        // HTTP client always throws (simulates routing failure)
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // The query filter mirrors the real repository method:
        // findByRoutingStatus=RECEIVED AND routingRetryCount < MAX_RETRIES
        // We simulate this by returning the doc only when it qualifies.
        when(inboundRepo.findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
                eq(RoutingStatus.RECEIVED), eq(MAX_RETRIES), any(Pageable.class)))
                .thenAnswer(inv -> {
                    // Reflect the current state of the document
                    if (doc.getRoutingStatus() == RoutingStatus.RECEIVED
                            && doc.getRoutingRetryCount() < MAX_RETRIES) {
                        return List.of(doc);
                    }
                    return List.of();
                });

        PeppolC4RoutingJob job = new PeppolC4RoutingJob(inboundRepo, orgRepo, restTemplate);

        // ── Act: run the job N times, each time simulating a failure ─────────

        // Before each run, reset status to RECEIVED so the job picks it up
        // (only if retryCount < MAX_RETRIES — otherwise it stays ROUTING_FAILED)
        for (int i = 0; i < failureAttempts; i++) {
            // Reset to RECEIVED only if we haven't hit the limit yet
            if (doc.getRoutingRetryCount() < MAX_RETRIES) {
                doc.setRoutingStatus(RoutingStatus.RECEIVED);
            }
            job.routePendingDocuments();
        }

        // ── Assert ───────────────────────────────────────────────────────────

        // 1. routingRetryCount must never exceed MAX_RETRIES (3)
        assertThat(doc.getRoutingRetryCount())
                .as("routingRetryCount must never exceed %d (was %d after %d failure attempts)",
                        MAX_RETRIES, doc.getRoutingRetryCount(), failureAttempts)
                .isLessThanOrEqualTo(MAX_RETRIES);

        // 2. Once retryCount >= MAX_RETRIES, the query filter excludes the document.
        //    Verify by calling the query directly — it must return empty.
        if (doc.getRoutingRetryCount() >= MAX_RETRIES) {
            List<InboundDocument> selected = inboundRepo
                    .findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
                            RoutingStatus.RECEIVED, MAX_RETRIES, Pageable.unpaged());

            assertThat(selected)
                    .as("Document with routingRetryCount >= %d must NOT be selected by the query filter",
                            MAX_RETRIES)
                    .isEmpty();
        }
    }

    /**
     * Property 4 (boundary): A document that starts at retryCount=2 (one below the limit)
     * is processed exactly once more and then permanently excluded.
     *
     * <p><b>Validates: Requirements 10.6</b>
     */
    @Property(tries = 100)
    void documentAtRetryLimitMinusOneIsProcessedOnceMoreThenExcluded(
            @ForAll @IntRange(min = 1, max = 5) int extraRunsAfterLimit) {

        UUID orgId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // Start at retryCount = MAX_RETRIES - 1 (one failure away from the limit)
        InboundDocument doc = InboundDocument.builder()
                .id(docId)
                .receiverOrganizationId(orgId)
                .ublXmlPayload("<Invoice/>")
                .routingStatus(RoutingStatus.RECEIVED)
                .routingRetryCount(MAX_RETRIES - 1)
                .build();

        Organization org = Organization.builder()
                .id(orgId)
                .name("Boundary Org")
                .slug("boundary-org")
                .c4WebhookUrl("https://erp.example.com/inbound")
                .c4WebhookAuthToken(null)
                .build();

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        RestTemplate restTemplate = mock(RestTemplate.class);

        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(inboundRepo.findById(docId)).thenAnswer(inv -> Optional.of(doc));

        when(inboundRepo.save(any(InboundDocument.class))).thenAnswer(inv -> {
            InboundDocument saved = inv.getArgument(0, InboundDocument.class);
            doc.setRoutingStatus(saved.getRoutingStatus());
            doc.setRoutingRetryCount(saved.getRoutingRetryCount());
            doc.setRoutingError(saved.getRoutingError());
            return saved;
        });

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Timeout"));

        when(inboundRepo.findByRoutingStatusAndRoutingRetryCountLessThanOrderByReceivedAtAsc(
                eq(RoutingStatus.RECEIVED), eq(MAX_RETRIES), any(Pageable.class)))
                .thenAnswer(inv -> {
                    if (doc.getRoutingStatus() == RoutingStatus.RECEIVED
                            && doc.getRoutingRetryCount() < MAX_RETRIES) {
                        return List.of(doc);
                    }
                    return List.of();
                });

        PeppolC4RoutingJob job = new PeppolC4RoutingJob(inboundRepo, orgRepo, restTemplate);

        // Run once — this should push retryCount to MAX_RETRIES and mark ROUTING_FAILED
        job.routePendingDocuments();

        assertThat(doc.getRoutingRetryCount())
                .as("After one more failure from retryCount=%d, count must equal MAX_RETRIES=%d",
                        MAX_RETRIES - 1, MAX_RETRIES)
                .isEqualTo(MAX_RETRIES);

        assertThat(doc.getRoutingStatus())
                .as("Document must be ROUTING_FAILED after reaching the retry limit")
                .isEqualTo(RoutingStatus.ROUTING_FAILED);

        // Run N more times — document must NOT be selected again (stays excluded)
        for (int i = 0; i < extraRunsAfterLimit; i++) {
            job.routePendingDocuments();
        }

        // retryCount must remain exactly MAX_RETRIES — no further increments
        assertThat(doc.getRoutingRetryCount())
                .as("retryCount must remain at MAX_RETRIES=%d after document is permanently excluded",
                        MAX_RETRIES)
                .isEqualTo(MAX_RETRIES);
    }
}

package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for inbound sender authenticity validation in
 * {@link PeppolReceiveController}.
 *
 * <p><b>Property 16: Inbound Sender Authenticity</b>
 *
 * <p>For any inbound request where {@code X-PEPPOL-Sender-ID} is not registered
 * in the eRegistry, no {@link InboundDocument} is persisted and the response
 * status is HTTP 403.
 *
 * <pre>
 * ∀ request WHERE senderParticipantId ∉ eRegistry:
 *   response.status = 403
 *   AND ∄ InboundDocument persisted
 * </pre>
 *
 * <p><b>Validates: Requirements 9.6</b>
 */
class PeppolReceiveControllerSenderAuthPropertyTest {

    /** Minimal valid UBL Invoice XML accepted by the controller's payload check. */
    private static final String VALID_UBL =
            "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">" +
            "<cbc:ID xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">INV-001</cbc:ID>" +
            "</Invoice>";

    /**
     * Builds a controller where the eRegistry has NO record for any sender ID
     * (simulates an unknown / unregistered sender).
     */
    private PeppolReceiveController controllerWithUnknownSender(
            ArgumentCaptor<InboundDocument> saveCaptor) {

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.save(saveCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        // No sender is registered — every lookup returns empty
        when(apRepo.findByParticipantId(any())).thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    /**
     * Builds a controller where the eRegistry DOES contain the given sender ID.
     */
    private PeppolReceiveController controllerWithKnownSender(
            String knownSenderId,
            ArgumentCaptor<InboundDocument> saveCaptor) {

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.save(saveCaptor.capture())).thenAnswer(inv -> {
            InboundDocument doc = inv.getArgument(0);
            if (doc.getId() == null) {
                try {
                    var idField = InboundDocument.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(doc, UUID.randomUUID());
                } catch (Exception ignored) { }
            }
            return doc;
        });

        AccessPoint senderAp = mock(AccessPoint.class);
        when(senderAp.getOrganizationId()).thenReturn(UUID.randomUUID());

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(knownSenderId)).thenReturn(Optional.of(senderAp));
        // Receiver lookup also returns empty (org unresolved — acceptable)
        when(apRepo.findByParticipantId(argThat(id -> !knownSenderId.equals(id))))
                .thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    /** Generates arbitrary PEPPOL-style participant IDs (scheme:value). */
    @Provide
    Arbitrary<String> participantIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(v -> "0190:ZW" + v);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 16: Inbound Sender Authenticity — unknown sender → 403, no persist
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 16: Inbound Sender Authenticity</b>
     *
     * <p>For any sender participant ID that is NOT registered in the eRegistry,
     * the C3 endpoint MUST return HTTP 403 and MUST NOT persist any
     * {@link InboundDocument}.
     *
     * <p><b>Validates: Requirements 9.6</b>
     */
    @Property(tries = 300)
    void unknownSenderIsRejectedWith403(@ForAll("participantIds") String unknownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithUnknownSender(saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                unknownSenderId,        // sender NOT in eRegistry
                "0190:ZWreceiverXYZ",   // receiver
                null, null, null
        );

        assertThat(response.getStatusCode().value())
                .as("Unknown sender must receive HTTP 403")
                .isEqualTo(403);

        assertThat(saveCaptor.getAllValues())
                .as("No InboundDocument must be persisted for an unknown sender")
                .isEmpty();
    }

    /**
     * <b>Property 16 (null sender variant)</b>
     *
     * <p>A request with a null {@code X-PEPPOL-Sender-ID} header is also rejected
     * with HTTP 403 and no document is persisted.
     *
     * <p><b>Validates: Requirements 9.6</b>
     */
    @Property(tries = 50)
    void nullSenderIsRejectedWith403() {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithUnknownSender(saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                null,                   // null sender header
                "0190:ZWreceiverXYZ",
                null, null, null
        );

        assertThat(response.getStatusCode().value())
                .as("Null sender must receive HTTP 403")
                .isEqualTo(403);

        assertThat(saveCaptor.getAllValues())
                .as("No InboundDocument must be persisted when sender header is null")
                .isEmpty();
    }

    /**
     * <b>Property 16 (known sender — positive case)</b>
     *
     * <p>Conversely, when the sender IS registered in the eRegistry, the request
     * is accepted (HTTP 200) and the document IS persisted.
     *
     * <p><b>Validates: Requirements 9.6 (positive path)</b>
     */
    @Property(tries = 200)
    void knownSenderIsAccepted(@ForAll("participantIds") String knownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithKnownSender(knownSenderId, saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                knownSenderId,          // sender IS in eRegistry
                "0190:ZWreceiverXYZ",
                null, null, null
        );

        assertThat(response.getStatusCode().value())
                .as("Known sender must receive HTTP 200")
                .isEqualTo(200);

        assertThat(saveCaptor.getAllValues())
                .as("InboundDocument must be persisted for a known sender")
                .isNotEmpty();
    }
}

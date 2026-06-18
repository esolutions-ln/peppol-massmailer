package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for inbound sender authenticity in {@link PeppolReceiveController}.
 *
 * <p><b>Property 16: Inbound Sender Authenticity</b> — unknown or unsigned senders
 * MUST NOT result in a persisted {@link InboundDocument}; the response MUST be 401/403.
 */
class PeppolReceiveControllerSenderAuthPropertyTest {

    private static final String VALID_UBL =
            "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">" +
            "<cbc:ID xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">INV-001</cbc:ID>" +
            "</Invoice>";

    private static final String SHARED_SECRET = "auth-test-shared-secret-32-bytes-min";

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PeppolReceiveController controllerWithUnknownSender(
            ArgumentCaptor<InboundDocument> saveCaptor) {

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.findByPayloadHash(any())).thenReturn(Optional.empty());
        when(inboundRepo.save(saveCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(any())).thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    private PeppolReceiveController controllerWithKnownSender(
            String knownSenderId, ArgumentCaptor<InboundDocument> saveCaptor) {

        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.findByPayloadHash(any())).thenReturn(Optional.empty());
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
        when(senderAp.isActive()).thenReturn(true);
        when(senderAp.getInboundSharedSecret()).thenReturn(SHARED_SECRET);
        when(senderAp.getOrganizationId()).thenReturn(UUID.randomUUID());

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(knownSenderId)).thenReturn(Optional.of(senderAp));
        when(apRepo.findByParticipantId(argThat(id -> !knownSenderId.equals(id))))
                .thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    @Provide
    Arbitrary<String> participantIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(20)
                .map(v -> "0190:ZW" + v);
    }

    /** Unknown sender → 403 (or 401 if the header is missing). Never 2xx; never persisted. */
    @Property(tries = 200)
    void unknownSenderIsRejected(@ForAll("participantIds") String unknownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithUnknownSender(saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                unknownSenderId,
                "0190:ZWreceiverXYZ",
                null, null, null,
                hmac(SHARED_SECRET, VALID_UBL) // signature is irrelevant — sender is unknown
        );

        assertThat(response.getStatusCode().value())
                .as("Unknown sender must be rejected")
                .isEqualTo(403);
        assertThat(saveCaptor.getAllValues())
                .as("No InboundDocument persisted for unknown sender")
                .isEmpty();
    }

    /** Null sender header → 401 (missing). Never persisted. */
    @Property(tries = 50)
    void nullSenderIsRejected() {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithUnknownSender(saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                null,
                "0190:ZWreceiverXYZ",
                null, null, null,
                null
        );

        assertThat(response.getStatusCode().value())
                .as("Missing sender header must be rejected with 401")
                .isEqualTo(401);
        assertThat(saveCaptor.getAllValues()).isEmpty();
    }

    /** Known sender + valid HMAC signature → 200 and document persisted. */
    @Property(tries = 100)
    void knownSenderWithValidSignatureIsAccepted(@ForAll("participantIds") String knownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithKnownSender(knownSenderId, saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                knownSenderId,
                "0190:ZWreceiverXYZ",
                null, null, null,
                hmac(SHARED_SECRET, VALID_UBL)
        );

        assertThat(response.getStatusCode().value())
                .as("Known sender with valid signature must be accepted")
                .isEqualTo(200);
        assertThat(saveCaptor.getAllValues()).isNotEmpty();
    }

    /** Known sender + missing signature → 401. */
    @Property(tries = 50)
    void knownSenderWithoutSignatureIsRejected(@ForAll("participantIds") String knownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithKnownSender(knownSenderId, saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                knownSenderId,
                "0190:ZWreceiverXYZ",
                null, null, null,
                null
        );

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(saveCaptor.getAllValues()).isEmpty();
    }

    /** Known sender + WRONG signature → 401. */
    @Property(tries = 50)
    void knownSenderWithBadSignatureIsRejected(@ForAll("participantIds") String knownSenderId) {
        ArgumentCaptor<InboundDocument> saveCaptor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = controllerWithKnownSender(knownSenderId, saveCaptor);

        ResponseEntity<?> response = controller.receive(
                VALID_UBL,
                knownSenderId,
                "0190:ZWreceiverXYZ",
                null, null, null,
                hmac("wrong-secret-value-here", VALID_UBL)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(saveCaptor.getAllValues()).isEmpty();
    }
}

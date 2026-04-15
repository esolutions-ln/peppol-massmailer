package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link PeppolReceiveController}.
 *
 * <p><b>Property 12: Payload Hash Correctness</b>
 *
 * <p>For any inbound UBL XML payload, the {@code payloadHash} stored on the
 * {@link InboundDocument} record equals the SHA-256 hex digest of that payload.
 *
 * <pre>
 * ∀ payload ∈ received_ubl_payloads:
 *   inboundDocument.payloadHash = sha256hex(payload)
 * </pre>
 *
 * <p><b>Validates: Requirements 9.5</b>
 */
class PeppolReceiveControllerPropertyTest {

    /**
     * Builds a {@link PeppolReceiveController} with mocked repositories.
     * The {@code InboundDocumentRepository.save()} is wired to capture the saved document.
     *
     * @param captor captures the {@link InboundDocument} passed to {@code save()}
     */
    private PeppolReceiveController buildController(ArgumentCaptor<InboundDocument> captor) {
        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.save(captor.capture())).thenAnswer(inv -> {
            InboundDocument doc = inv.getArgument(0);
            // Simulate JPA UUID generation so getId() is non-null
            if (doc.getId() == null) {
                try {
                    var idField = InboundDocument.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(doc, java.util.UUID.randomUUID());
                } catch (Exception e) {
                    // ignore — test will still work via captor
                }
            }
            return doc;
        });

        AccessPoint senderAp = mock(AccessPoint.class);
        when(senderAp.getOrganizationId()).thenReturn(java.util.UUID.randomUUID());

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        // Sender "0190:ZW111111111" is registered; all other lookups (receiver) return empty
        when(apRepo.findByParticipantId("0190:ZW111111111")).thenReturn(Optional.of(senderAp));
        when(apRepo.findByParticipantId(argThat(id -> !"0190:ZW111111111".equals(id))))
                .thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    /**
     * Computes the expected SHA-256 hex digest of a string encoded as UTF-8,
     * matching the logic in {@link PeppolReceiveController#sha256(String)}.
     */
    private String expectedSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generator that produces valid UBL-like XML strings containing an {@code <Invoice}
     * element (required by the controller's payload validation) with arbitrary content.
     *
     * <p>The body content is drawn from printable ASCII to avoid encoding edge cases
     * while still exercising a wide range of inputs.
     */
    @Provide
    Arbitrary<String> ublPayloads() {
        return Arbitraries.strings()
                .withCharRange(' ', '~')   // printable ASCII
                .ofMinLength(0)
                .ofMaxLength(500)
                .map(body -> "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">"
                        + body
                        + "</Invoice>");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 12: Payload Hash Correctness
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 12: Payload Hash Correctness</b>
     *
     * <p>For any inbound UBL XML payload accepted by the C3 endpoint, the
     * {@code payloadHash} persisted on the {@link InboundDocument} equals the
     * SHA-256 hex digest of the raw payload string (UTF-8 encoded).
     *
     * <p><b>Validates: Requirements 9.5</b>
     */
    @Property(tries = 500)
    void payloadHashCorrectness(@ForAll("ublPayloads") String payload) {
        ArgumentCaptor<InboundDocument> captor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = buildController(captor);

        controller.receive(
                payload,
                "0190:ZW111111111",   // senderParticipantId
                "0190:ZW999999999",   // receiverParticipantId
                null,                 // documentType
                null,                 // processId
                null                  // invoiceNumber
        );

        assertThat(captor.getAllValues())
                .as("InboundDocument must be saved for every valid inbound payload")
                .isNotEmpty();

        InboundDocument saved = captor.getValue();
        String expectedHash = expectedSha256(payload);

        assertThat(saved.getPayloadHash())
                .as("payloadHash must equal SHA-256 hex digest of the payload")
                .isEqualTo(expectedHash);
    }

    /**
     * <b>Property 12 (byte-level variant): Payload Hash Correctness for arbitrary byte content</b>
     *
     * <p>Verifies that the hash is computed over the exact UTF-8 byte representation of the
     * payload string — not over any transformed or normalised version. Uses a wider character
     * range including multi-byte Unicode characters to exercise the UTF-8 encoding path.
     *
     * <p><b>Validates: Requirements 9.5</b>
     */
    @Property(tries = 300)
    void payloadHashCorrectnessUnicode(
            @ForAll @net.jqwik.api.constraints.StringLength(min = 0, max = 200) String arbitraryContent) {

        // Wrap in a valid Invoice element so the controller accepts it
        String payload = "<Invoice>" + arbitraryContent + "</Invoice>";

        ArgumentCaptor<InboundDocument> captor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = buildController(captor);

        controller.receive(
                payload,
                "0190:ZW111111111",
                "0190:ZW999999999",
                null,
                null,
                null
        );

        assertThat(captor.getAllValues())
                .as("InboundDocument must be saved for every valid inbound payload")
                .isNotEmpty();

        InboundDocument saved = captor.getValue();
        String expectedHash = expectedSha256(payload);

        assertThat(saved.getPayloadHash())
                .as("payloadHash must equal SHA-256 hex digest of the UTF-8 encoded payload")
                .isEqualTo(expectedHash);
    }
}

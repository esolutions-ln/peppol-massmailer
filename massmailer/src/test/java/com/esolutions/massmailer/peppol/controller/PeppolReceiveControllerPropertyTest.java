package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.InboundDocument;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.InboundDocumentRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link PeppolReceiveController}.
 *
 * <p><b>Property 12: Payload Hash Correctness</b> — for any accepted inbound UBL XML payload,
 * the {@code payloadHash} stored on the {@link InboundDocument} equals the SHA-256 hex digest
 * of that payload (UTF-8 encoded).
 */
class PeppolReceiveControllerPropertyTest {

    private static final String SENDER_ID = "0190:ZW111111111";
    private static final String SHARED_SECRET = "test-shared-secret-32-chars-min-len";

    private PeppolReceiveController buildController(ArgumentCaptor<InboundDocument> captor) {
        InboundDocumentRepository inboundRepo = mock(InboundDocumentRepository.class);
        when(inboundRepo.findByPayloadHash(any())).thenReturn(Optional.empty());
        when(inboundRepo.save(captor.capture())).thenAnswer(inv -> {
            InboundDocument doc = inv.getArgument(0);
            if (doc.getId() == null) {
                try {
                    var idField = InboundDocument.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(doc, java.util.UUID.randomUUID());
                } catch (Exception ignored) { }
            }
            return doc;
        });

        AccessPoint senderAp = mock(AccessPoint.class);
        when(senderAp.isActive()).thenReturn(true);
        when(senderAp.getInboundSharedSecret()).thenReturn(SHARED_SECRET);
        when(senderAp.getOrganizationId()).thenReturn(java.util.UUID.randomUUID());

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(SENDER_ID)).thenReturn(Optional.of(senderAp));
        when(apRepo.findByParticipantId(argThat(id -> !SENDER_ID.equals(id))))
                .thenReturn(Optional.empty());

        return new PeppolReceiveController(inboundRepo, apRepo);
    }

    private String expectedSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Provide
    Arbitrary<String> ublPayloads() {
        return Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(0)
                .ofMaxLength(500)
                .map(body -> "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">"
                        + body
                        + "</Invoice>");
    }

    @Property(tries = 200)
    void payloadHashCorrectness(@ForAll("ublPayloads") String payload) {
        ArgumentCaptor<InboundDocument> captor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = buildController(captor);

        controller.receive(
                payload,
                SENDER_ID,
                "0190:ZW999999999",
                null, null, null,
                hmac(SHARED_SECRET, payload)
        );

        assertThat(captor.getAllValues()).isNotEmpty();
        InboundDocument saved = captor.getValue();
        assertThat(saved.getPayloadHash()).isEqualTo(expectedSha256(payload));
    }

    @Property(tries = 200)
    void payloadHashCorrectnessUnicode(
            @ForAll @net.jqwik.api.constraints.StringLength(min = 0, max = 200) String arbitraryContent) {

        String payload = "<Invoice>" + arbitraryContent + "</Invoice>";
        ArgumentCaptor<InboundDocument> captor = ArgumentCaptor.forClass(InboundDocument.class);
        PeppolReceiveController controller = buildController(captor);

        controller.receive(
                payload,
                SENDER_ID,
                "0190:ZW999999999",
                null, null, null,
                hmac(SHARED_SECRET, payload)
        );

        assertThat(captor.getAllValues()).isNotEmpty();
        InboundDocument saved = captor.getValue();
        assertThat(saved.getPayloadHash()).isEqualTo(expectedSha256(payload));
    }
}

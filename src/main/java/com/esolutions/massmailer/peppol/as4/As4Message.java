package com.esolutions.massmailer.peppol.as4;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Encapsulates all data required to send a single AS4 ebMS 3.0 message.
 */
public record As4Message(
        String senderParticipantId,
        String receiverParticipantId,
        String documentTypeId,
        String processId,
        String ublXmlPayload,
        X509Certificate senderCert,
        PrivateKey senderPrivateKey,
        X509Certificate receiverCert,
        String receiverEndpointUrl
) {}

package com.esolutions.massmailer.peppol.smp;

import java.security.cert.X509Certificate;
import java.util.List;

public record SmpServiceMetadata(
        String participantId,
        String documentTypeId,
        String processId,
        String transportProfile,
        String endpointUrl,
        X509Certificate certificate,
        List<String> serviceDescriptions
) {}

package com.esolutions.massmailer.invitation.service.dto;

/**
 * Response returned after a customer successfully completes PEPPOL self-registration.
 *
 * @param participantId the registered PEPPOL participant ID
 * @param endpointUrl   the registered PEPPOL endpoint URL
 */
public record CompleteRegistrationResponse(
        String participantId,
        String endpointUrl
) {}

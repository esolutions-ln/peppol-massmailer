package com.esolutions.massmailer.invitation.service.dto;

/**
 * Request payload for completing a PEPPOL self-registration via an invitation token.
 *
 * @param participantId          PEPPOL participant ID in format {scheme}:{value}, e.g. "0190:ZW123456789"
 * @param endpointUrl            HTTPS URL of the customer's PEPPOL AS4 endpoint
 * @param deliveryAuthToken      Optional bearer token for simplified HTTP delivery authentication
 * @param simplifiedHttpDelivery When true, POST UBL XML directly over HTTPS without full AS4 envelope
 */
public record CompleteRegistrationRequest(
        String participantId,
        String endpointUrl,
        String deliveryAuthToken,
        boolean simplifiedHttpDelivery
) {}

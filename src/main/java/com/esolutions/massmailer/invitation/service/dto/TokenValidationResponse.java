package com.esolutions.massmailer.invitation.service.dto;

/**
 * Response returned by {@code InvitationService.validateToken()} for a valid PENDING token.
 *
 * NOTE: intentionally excludes the organisation's API key and internal UUID (Requirement 8.4).
 */
public record TokenValidationResponse(
        String customerEmail,
        String organisationName
) {}

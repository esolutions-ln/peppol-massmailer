package com.esolutions.massmailer.invitation.service.dto;

/**
 * Request body for the POST /api/v1/my/invitations endpoint.
 */
public record SendInvitationRequest(String customerEmail) {}

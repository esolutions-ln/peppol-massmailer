package com.esolutions.massmailer.invitation.service.dto;

import com.esolutions.massmailer.invitation.model.InvitationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO returned by the list-invitations endpoint.
 *
 * <p>The {@code status} field is virtual: if the stored status is {@code PENDING} and
 * {@code expiresAt} is in the past, the response reports {@code EXPIRED} without mutating
 * the underlying entity.
 */
public record InvitationResponse(
        UUID id,
        String customerEmail,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant completedAt
) {}

package com.esolutions.massmailer.security;

import com.esolutions.massmailer.invitation.model.InvitationStatus;

import java.time.Instant;
import java.util.UUID;

public class AdminDtos {

    public record AdminLoginRequest(String username, String password) {}

    public record AdminLoginResponse(String token, String name) {}

    public record CreateAdminUserRequest(String username, String password, String displayName) {}

    public record AdminUserDto(UUID id, String username, String displayName, String role,
                               boolean active, Instant createdAt) {}

    // ── Admin invitations ──────────────────────────────────────────────────

    public record SendAdminInvitationRequest(String email, String displayName) {}

    public record AdminInvitationDto(UUID id, String email, String displayName, String invitedBy,
                                     InvitationStatus status, Instant createdAt,
                                     Instant expiresAt, Instant completedAt) {}

    /** Excludes token/id — only what the invitee-facing page needs to render. */
    public record AdminInvitationTokenValidationResponse(String email, String displayName, String invitedBy) {}

    public record CompleteAdminInvitationRequest(String username, String password) {}

    public record CompleteAdminInvitationResponse(String username) {}
}

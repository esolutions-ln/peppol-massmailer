package com.esolutions.massmailer.security.model;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a tokenised invitation for a new platform-administrator account.
 *
 * The token is a cryptographically random UUID v4 embedded in the invitation email link.
 * It is single-use: once the invitee completes registration the status transitions to
 * COMPLETED and the token cannot be reused.
 */
@Entity
@Table(name = "admin_invitations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_invitation_token",
                columnNames = {"token"}),
        indexes = {
                @Index(name = "idx_admin_inv_token", columnList = "token"),
                @Index(name = "idx_admin_inv_status", columnList = "status")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Email address the invitation was sent to */
    @Column(nullable = false)
    private String email;

    /** Display name to pre-fill for the invitee, set by the inviting admin */
    private String displayName;

    /** Username of the admin who sent this invitation */
    @Column(nullable = false)
    private String invitedBy;

    /** Cryptographically random UUID v4 — single-use */
    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvitationStatus status;

    /** createdAt + 72 hours */
    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** Set when status transitions to COMPLETED */
    private Instant completedAt;
}

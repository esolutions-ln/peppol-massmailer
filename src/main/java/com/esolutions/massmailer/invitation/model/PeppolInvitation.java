package com.esolutions.massmailer.invitation.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a tokenised PEPPOL self-registration invitation sent by a C1 supplier
 * to one of their CustomerContacts.
 *
 * The token is a cryptographically random UUID v4 embedded in the invitation email link.
 * It is single-use: once the customer completes registration the status transitions to
 * COMPLETED and the token cannot be reused.
 */
@Entity
@Table(name = "peppol_invitations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_invitation_token",
                columnNames = {"token"}),
        indexes = {
                @Index(name = "idx_inv_org", columnList = "organizationId"),
                @Index(name = "idx_inv_token", columnList = "token"),
                @Index(name = "idx_inv_status", columnList = "status")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeppolInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The C1 organisation that sent this invitation */
    @Column(nullable = false)
    private UUID organizationId;

    /** The CustomerContact this invitation was sent to */
    @Column(nullable = false)
    private UUID customerContactId;

    /** Email address the invitation was sent to */
    @Column(nullable = false)
    private String customerEmail;

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

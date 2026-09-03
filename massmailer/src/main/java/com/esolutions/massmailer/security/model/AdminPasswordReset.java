package com.esolutions.massmailer.security.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a tokenised "forgot password" reset link for a platform admin.
 *
 * The token is a cryptographically random UUID v4 embedded in the reset email link.
 * It is single-use: once the admin sets a new password, {@code usedAt} is set and the
 * token cannot be reused. Short-lived (1 hour) compared to account invitations (72 hours),
 * matching standard password-reset conventions.
 */
@Entity
@Table(name = "admin_password_resets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_pw_reset_token",
                columnNames = {"token"}),
        indexes = {
                @Index(name = "idx_admin_pw_reset_admin_user", columnList = "adminUserId"),
                @Index(name = "idx_admin_pw_reset_token", columnList = "token")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminPasswordReset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID adminUserId;

    /** Cryptographically random UUID v4 — single-use */
    @Column(nullable = false, unique = true, length = 36)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    /** Set once the admin completes the reset — a null value means still usable. */
    private Instant usedAt;
}

package com.esolutions.massmailer.security.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Stateful admin session token.
 *
 * Issued on successful login and stored in {@code admin_session_tokens}.
 * Presented as the {@code X-API-Key} header on subsequent requests.
 * Deactivating the owning {@link AdminUser} deletes all their tokens immediately.
 */
@Entity
@Table(
        name = "admin_session_tokens",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_session_token",
                columnNames = {"token"}),
        indexes = @Index(
                name = "idx_admin_session_token",
                columnList = "token"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminSessionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_user_id", nullable = false)
    private AdminUser adminUser;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

package com.esolutions.massmailer.security.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent admin user account.
 *
 * Replaces the single env-var admin account with a proper multi-user
 * system. Each admin user authenticates via username/password and
 * receives a stateful session token stored in {@code admin_session_tokens}.
 */
@Entity
@Table(name = "admin_users",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_admin_username",
                columnNames = {"username"}))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    @Builder.Default
    private String role = "ADMIN";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

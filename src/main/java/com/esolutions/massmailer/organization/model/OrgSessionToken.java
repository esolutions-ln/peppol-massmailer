package com.esolutions.massmailer.organization.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_session_tokens",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_org_session_token",
                columnNames = {"token"}),
        indexes = @Index(name = "idx_org_session_token", columnList = "token"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgSessionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "org_member_id", nullable = false)
    private OrgMember orgMember;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

package com.esolutions.massmailer.organization.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "org_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_org_member_org_email",
                columnNames = {"organization_id", "email"}),
        indexes = {
                @Index(name = "idx_org_member_email", columnList = "email"),
                @Index(name = "idx_org_member_org", columnList = "organization_id")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrgMemberRole role;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant lastLoginAt;
}

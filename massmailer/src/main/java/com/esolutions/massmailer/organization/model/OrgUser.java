package com.esolutions.massmailer.organization.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The primary contact user for an Organisation.
 *
 * Mirrors the Sage Intacct AccessPointConnection user block:
 * firstName, lastName, jobTitle, emailAddress.
 *
 * One OrgUser per Organisation — the person who registered the org
 * and is responsible for its PEPPOL access point configuration.
 */
@Entity
@Table(name = "org_users", indexes = {
        @Index(name = "idx_org_user_org", columnList = "organizationId"),
        @Index(name = "idx_org_user_email", columnList = "email")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK to the owning organisation */
    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    /** Job title / role within the organisation (e.g. "Finance Manager") */
    @Column(length = 150)
    private String jobTitle;

    /** Work email address for this contact */
    @Column(nullable = false, length = 255)
    private String email;

    /** IP address captured at registration time (audit trail) */
    @Column(length = 45)   // supports IPv6
    private String registrationIp;

    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Convenience: full name */
    public String fullName() {
        return firstName + " " + lastName;
    }
}

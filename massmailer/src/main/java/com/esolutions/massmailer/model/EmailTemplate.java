package com.esolutions.massmailer.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-organisation customisable email body template used when emailing invoices.
 * The body is plain text/HTML with {{placeholder}} tokens substituted at send time.
 */
@Entity
@Table(name = "email_templates", indexes = {
        @Index(name = "idx_email_template_org", columnList = "organization_id"),
        @Index(name = "idx_email_template_org_default", columnList = "organization_id,is_default")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

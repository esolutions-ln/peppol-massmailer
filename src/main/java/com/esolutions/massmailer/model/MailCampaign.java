package com.esolutions.massmailer.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
@Entity
@Table(name = "mail_campaigns")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String templateName;

    @Column(columnDefinition = "TEXT")
    private String templateVariablesJson;

    /** The organization that owns this campaign — used for metering and source tracking */
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.CREATED;

    @Column(nullable = false)
    @Builder.Default
    private int totalRecipients = 0;

    @Builder.Default
    private int sentCount = 0;

    @Builder.Default
    private int failedCount = 0;

    @Builder.Default
    private int skippedCount = 0;

    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<MailRecipient> recipients = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant startedAt;
    private Instant completedAt;

    // ── Domain logic ──

    public void markInProgress() {
        this.status = CampaignStatus.IN_PROGRESS;
        this.startedAt = Instant.now();
    }

    public void markCompleted() {
        this.completedAt = Instant.now();
        this.status = (failedCount == 0)
                ? CampaignStatus.COMPLETED
                : CampaignStatus.PARTIALLY_FAILED;
    }

    public void incrementSent() { this.sentCount++; }
    public void incrementFailed() { this.failedCount++; }
    public void incrementSkipped() { this.skippedCount++; }
}

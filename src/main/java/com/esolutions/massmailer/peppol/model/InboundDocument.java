package com.esolutions.massmailer.peppol.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists every inbound PEPPOL document received at the C3 endpoint.
 *
 * As an Access Point provider, we must be able to prove receipt of every
 * document delivered to us, and track its onward routing to C4 (buyer ERP).
 *
 * Lifecycle: RECEIVED → ROUTING → DELIVERED_TO_C4 | ROUTING_FAILED
 */
@Entity
@Table(name = "peppol_inbound_documents",
        indexes = {
                @Index(name = "idx_inbound_invoice", columnList = "invoiceNumber"),
                @Index(name = "idx_inbound_sender", columnList = "senderParticipantId"),
                @Index(name = "idx_inbound_receiver", columnList = "receiverParticipantId"),
                @Index(name = "idx_inbound_status", columnList = "routingStatus"),
                @Index(name = "idx_inbound_received", columnList = "receivedAt")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboundDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Source (C2 — sending AP) ──
    @Column(length = 200)
    private String senderParticipantId;

    // ── Destination (C3 — us, then C4 — buyer ERP) ──
    @Column(length = 200)
    private String receiverParticipantId;

    /** The organization in our registry that this document is addressed to */
    private UUID receiverOrganizationId;

    // ── Document identity ──
    @Column(length = 200)
    private String invoiceNumber;

    @Column(columnDefinition = "TEXT")
    private String documentTypeId;

    @Column(length = 200)
    private String processId;

    // ── Raw payload ──
    @Column(columnDefinition = "TEXT", nullable = false)
    private String ublXmlPayload;

    /** SHA-256 hash of the payload for integrity verification */
    @Column(length = 64)
    private String payloadHash;

    // ── C4 routing ──
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RoutingStatus routingStatus = RoutingStatus.RECEIVED;

    /** The C4 endpoint URL this was forwarded to */
    @Column(length = 500)
    private String routedToEndpoint;

    /** HTTP response or error from C4 delivery attempt */
    @Column(columnDefinition = "TEXT")
    private String routingResponse;

    @Column(length = 2000)
    private String routingError;

    @Builder.Default
    private int routingRetryCount = 0;

    // ── Timestamps ──
    @Column(nullable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();

    private Instant routedAt;

    public enum RoutingStatus {
        /** Document received and persisted, awaiting C4 routing */
        RECEIVED,
        /** Actively being forwarded to C4 */
        ROUTING,
        /** Successfully delivered to buyer's ERP (C4) */
        DELIVERED_TO_C4,
        /** C4 routing failed — needs retry or manual intervention */
        ROUTING_FAILED
    }

    public void markRoutedToC4(String endpoint, String response) {
        this.routingStatus = RoutingStatus.DELIVERED_TO_C4;
        this.routedToEndpoint = endpoint;
        this.routingResponse = response;
        this.routedAt = Instant.now();
    }

    public void markRoutingFailed(String error) {
        this.routingStatus = RoutingStatus.ROUTING_FAILED;
        this.routingError = error;
        this.routingRetryCount++;
    }
}

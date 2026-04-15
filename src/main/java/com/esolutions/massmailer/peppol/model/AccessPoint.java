package com.esolutions.massmailer.peppol.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a registered PEPPOL Access Point (AP) in the eRegistry.
 *
 * In the PEPPOL 4-corner model:
 *
 *   Corner 1 (C1) — Supplier ERP (sender)
 *   Corner 2 (C2) — Sender's Access Point  ← this system can act as C2
 *   Corner 3 (C3) — Receiver's Access Point ← stored here for routing
 *   Corner 4 (C4) — Buyer ERP (receiver)
 *
 * Each AccessPoint record defines one participant in the network.
 * When an invoice is dispatched via PEPPOL channel, the router looks up
 * the receiver's C3 endpoint here and POSTs the UBL document to it.
 *
 * The eRegistry is the local equivalent of the PEPPOL SMP (Service Metadata Publisher).
 */
@Entity
@Table(name = "peppol_access_points",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ap_participant_id",
                columnNames = {"participantId"}),
        indexes = {
                @Index(name = "idx_ap_org", columnList = "organizationId"),
                @Index(name = "idx_ap_participant", columnList = "participantId"),
                @Index(name = "idx_ap_role", columnList = "role")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * The organization this access point belongs to.
     * Null for external (third-party) access points registered for routing only.
     */
    private UUID organizationId;

    /**
     * PEPPOL Participant Identifier — globally unique.
     * Format: {scheme}:{value}
     * Examples:
     *   - "0190:ZW123456789"  (Zimbabwe VAT number scheme)
     *   - "0088:1234567890128" (GLN scheme)
     *   - "9915:esolutions"   (test scheme)
     */
    @Column(nullable = false, unique = true, length = 200)
    private String participantId;

    /** Human-readable name of the participant (company name) */
    @Column(nullable = false)
    private String participantName;

    /**
     * Role of this access point in the 4-corner model.
     * SENDER = C2 (our outbound AP), RECEIVER = C3 (their inbound AP)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessPointRole role;

    /**
     * The AS4/AS2 endpoint URL where PEPPOL documents are delivered.
     * For RECEIVER APs: this is where we POST the UBL invoice.
     * For SENDER APs: this is our own inbound URL for receiving responses.
     * Example: "https://ap.invoicedirect.biz/peppol/as4/receive"
     */
    @Column(nullable = false, length = 500)
    private String endpointUrl;

    /**
     * Document types this AP can receive.
     * Stored as comma-separated PEPPOL document type identifiers.
     * Example: "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2::Invoice##urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0::2.1"
     */
    @Column(columnDefinition = "TEXT")
    private String supportedDocumentTypes;

    /**
     * Transport profile — AS4 is the PEPPOL standard.
     * "peppol-transport-as4-v2_0" for production.
     */
    @Column(length = 100)
    @Builder.Default
    private String transportProfile = "peppol-transport-as4-v2_0";

    /**
     * X.509 certificate (PEM) for AS4 message signing/encryption.
     * Required for production PEPPOL; optional for simplified HTTP delivery.
     */
    @Column(columnDefinition = "TEXT")
    private String certificate;

    /**
     * Simplified HTTP delivery — when true, the system POSTs the UBL XML
     * directly to endpointUrl over HTTPS without full AS4 envelope.
     * Use for internal/private networks or development.
     * Set to false for production PEPPOL compliance.
     */
    @Builder.Default
    private boolean simplifiedHttpDelivery = true;

    /** API key or Bearer token for simplified HTTP delivery authentication */
    @Column(length = 256)
    private String deliveryAuthToken;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AccessPointStatus status = AccessPointStatus.ACTIVE;

    @Builder.Default
    private Instant registeredAt = Instant.now();

    private Instant updatedAt;

    public enum AccessPointRole {
        /** C2 — This system's outbound access point (sender side) */
        SENDER,
        /** C3 — External receiver's access point (buyer side) */
        RECEIVER,
        /** Acts as both sender and receiver (e.g. our own AP gateway) */
        GATEWAY
    }

    public enum AccessPointStatus {
        ACTIVE,
        SUSPENDED,
        DECOMMISSIONED
    }

    public boolean isActive() {
        return status == AccessPointStatus.ACTIVE;
    }
}

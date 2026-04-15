package com.esolutions.massmailer.peppol.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a CustomerContact (invoice recipient) to their PEPPOL Access Point.
 *
 * When a supplier dispatches an invoice via PEPPOL channel, the router
 * looks up the buyer's participant ID here, then resolves their C3
 * endpoint from the AccessPoint registry.
 *
 * This is the local SMP lookup table — the equivalent of querying
 * the PEPPOL SML/SMP for a participant's registered AP.
 *
 * Supplier side:
 *   Organization → AccessPoint (role=SENDER or GATEWAY)
 *
 * Buyer side:
 *   CustomerContact → PeppolParticipantLink → AccessPoint (role=RECEIVER)
 */
@Entity
@Table(name = "peppol_participant_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_participant_link",
                columnNames = {"organizationId", "customerContactId"}),
        indexes = {
                @Index(name = "idx_ppl_org", columnList = "organizationId"),
                @Index(name = "idx_ppl_customer", columnList = "customerContactId"),
                @Index(name = "idx_ppl_participant", columnList = "participantId")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeppolParticipantLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The sending organization (supplier) */
    @Column(nullable = false)
    private UUID organizationId;

    /** The customer contact (buyer) this link is for */
    @Column(nullable = false)
    private UUID customerContactId;

    /**
     * The buyer's PEPPOL participant identifier.
     * Format: {scheme}:{value}  e.g. "0190:ZW987654321"
     */
    @Column(nullable = false, length = 200)
    private String participantId;

    /**
     * The receiver's Access Point ID in our eRegistry.
     * Resolved from participantId → AccessPoint.
     */
    @Column(nullable = false)
    private UUID receiverAccessPointId;

    /**
     * Preferred delivery channel for this buyer.
     * PEPPOL = send via BIS 3.0 UBL to their AP endpoint.
     * EMAIL  = fall back to PDF email delivery.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeliveryChannel preferredChannel = DeliveryChannel.PEPPOL;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    public enum DeliveryChannel {
        /** PEPPOL BIS 3.0 UBL XML delivery to buyer's AP endpoint */
        PEPPOL,
        /** PDF email delivery via SMTP */
        EMAIL
    }
}

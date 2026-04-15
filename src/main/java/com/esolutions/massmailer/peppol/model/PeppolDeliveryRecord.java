package com.esolutions.massmailer.peppol.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Audit record for every PEPPOL document transmission attempt.
 *
 * Tracks the full lifecycle of a PEPPOL delivery:
 *   PENDING → TRANSMITTING → DELIVERED | FAILED
 *
 * In production PEPPOL, the AS4 protocol provides MDN (Message Disposition
 * Notification) receipts. This record stores those receipts for audit.
 */
@Entity
@Table(name = "peppol_delivery_records",
        indexes = {
                @Index(name = "idx_pdr_invoice", columnList = "invoiceNumber"),
                @Index(name = "idx_pdr_org", columnList = "organizationId"),
                @Index(name = "idx_pdr_status", columnList = "status"),
                @Index(name = "idx_pdr_participant", columnList = "receiverParticipantId")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeppolDeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String invoiceNumber;

    /** Sender's PEPPOL participant ID (C1/C2) */
    @Column(nullable = false, length = 200)
    private String senderParticipantId;

    /** Receiver's PEPPOL participant ID (C3/C4) */
    @Column(nullable = false, length = 200)
    private String receiverParticipantId;

    /** The AP endpoint URL the document was sent to */
    @Column(length = 500)
    private String deliveredToEndpoint;

    /** PEPPOL document type identifier */
    @Column(columnDefinition = "TEXT")
    private String documentTypeId;

    /** PEPPOL process identifier (e.g. urn:fdc:peppol.eu:2017:poacc:billing:01:1.0) */
    @Column(length = 200)
    private String processId;

    /** The UBL XML document that was transmitted (stored for audit/resend) */
    @Column(columnDefinition = "TEXT")
    private String ublXmlPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    /** AS4 MDN receipt or HTTP response body */
    @Column(columnDefinition = "TEXT")
    private String deliveryReceipt;

    /** Error message if delivery failed */
    @Column(length = 2000)
    private String errorMessage;

    /** AS4 MDN message ID returned by the receiver's Access Point */
    @Column(length = 500)
    private String mdnMessageId;

    /** AS4 MDN status: "processed" | "failed" | null for HTTP delivery */
    @Column(length = 50)
    private String mdnStatus;

    /** Whether Schematron validation was run and passed */
    @Builder.Default
    private boolean schematronPassed = false;

    /** Non-fatal Schematron warning violations stored as JSON array */
    @Column(columnDefinition = "TEXT")
    private String schematronWarnings;

    @Builder.Default
    private int retryCount = 0;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant transmittedAt;
    private Instant acknowledgedAt;

    public enum DeliveryStatus {
        PENDING,
        TRANSMITTING,
        DELIVERED,
        FAILED,
        RETRYING
    }

    public void markDelivered(String receipt) {
        this.status = DeliveryStatus.DELIVERED;
        this.deliveryReceipt = receipt;
        this.acknowledgedAt = Instant.now();
    }

    /**
     * Mark as delivered with an optional AS4 MDN message ID.
     * Use this overload when delivering via AS4 transport.
     */
    public void markDelivered(String receipt, String mdnMessageId) {
        markDelivered(receipt);
        this.mdnMessageId = mdnMessageId;
    }

    public void markFailed(String error) {
        this.status = DeliveryStatus.FAILED;
        this.errorMessage = error;
        this.retryCount++;
    }

    /**
     * Mark Schematron validation as failed and store the violations as a JSON array.
     * Sets schematronPassed=false and serialises the violations list.
     */
    public void markSchematronFailed(List<String> violations) {
        this.schematronPassed = false;
        if (violations == null || violations.isEmpty()) {
            this.schematronWarnings = "[]";
        } else {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < violations.size(); i++) {
                sb.append("\"").append(violations.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                if (i < violations.size() - 1) sb.append(",");
            }
            sb.append("]");
            this.schematronWarnings = sb.toString();
        }
    }
}

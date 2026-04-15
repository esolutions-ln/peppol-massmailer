package com.esolutions.massmailer.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "mail_recipients", indexes = {
        @Index(name = "idx_recipient_email", columnList = "email"),
        @Index(name = "idx_recipient_status", columnList = "deliveryStatus"),
        @Index(name = "idx_recipient_invoice", columnList = "invoiceNumber")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private MailCampaign campaign;

    // ── Contact ──
    @Column(nullable = false)
    private String email;

    private String name;

    // ── Invoice identity ──
    @Column(nullable = false)
    private String invoiceNumber;

    private LocalDate invoiceDate;
    private LocalDate dueDate;

    // ── Financial ──
    @Column(precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(precision = 18, scale = 2)
    private BigDecimal vatAmount;

    @Column(length = 3)
    private String currency; // ISO 4217 — Zimbabwe: USD, ZWG, ZAR, GBP, EUR

    // ── Fiscal device / ZIMRA compliance ──
    private String fiscalDeviceSerialNumber;
    private String fiscalDayNumber;
    private String globalInvoiceCounter;
    private String verificationCode;

    @Column(length = 1024)
    private String qrCodeUrl;

    // ── PDF Attachment ──
    /** File system path to the generated invoice PDF */
    @Column(length = 1024)
    private String pdfFilePath;

    /** Base64-encoded PDF (if supplied via API, stored temporarily) */
    @Column(columnDefinition = "TEXT")
    private String pdfBase64;

    /** Attachment filename sent to the recipient */
    private String pdfFileName;

    // ── Per-recipient template merge fields (JSON) ──
    @Column(columnDefinition = "TEXT")
    private String mergeFieldsJson;

    // ── Delivery tracking ──
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RecipientStatus deliveryStatus = RecipientStatus.PENDING;

    private String messageId;

    @Column(length = 2048)
    private String errorMessage;

    @Builder.Default
    private int retryCount = 0;

    private Instant sentAt;

    /** Size of the attached PDF in bytes — useful for audit logs */
    private Long attachmentSizeBytes;

    public enum RecipientStatus {
        PENDING, SENT, FAILED, SKIPPED, BOUNCED, UNSUBSCRIBED
    }

    // ── Domain behaviour ──

    public void markSent(String messageId, long attachmentSize) {
        this.deliveryStatus = RecipientStatus.SENT;
        this.messageId = messageId;
        this.attachmentSizeBytes = attachmentSize;
        this.sentAt = Instant.now();
    }

    public void markFailed(String error) {
        this.deliveryStatus = RecipientStatus.FAILED;
        this.errorMessage = error;
        this.retryCount++;
    }

    public void markSkipped(String reason) {
        this.deliveryStatus = RecipientStatus.SKIPPED;
        this.errorMessage = reason;
    }

    /**
     * Returns true if this recipient has a PDF attachment available
     * (either as a file path or Base64 content).
     */
    public boolean hasPdfAttachment() {
        return (pdfFilePath != null && !pdfFilePath.isBlank())
                || (pdfBase64 != null && !pdfBase64.isBlank());
    }
}

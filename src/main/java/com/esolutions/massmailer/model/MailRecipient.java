package com.esolutions.massmailer.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "mail_recipients")
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

    @Column(nullable = false)
    private String email;

    private String name;

    @Column(nullable = false)
    private String invoiceNumber;

    private LocalDate invoiceDate;
    private LocalDate dueDate;

    private BigDecimal totalAmount;
    private BigDecimal vatAmount;
    private String currency;

    private String fiscalDeviceSerialNumber;
    private String fiscalDayNumber;
    private String globalInvoiceCounter;
    private String verificationCode;
    private String qrCodeUrl;

    private UUID customerId;
    
    @Column(name = "account_number")
    private String accountNumber;

    private String pdfFilePath;
    @Column(columnDefinition = "TEXT")
    private String pdfBase64;
    private String pdfFileName;

    @Column(columnDefinition = "TEXT")
    private String mergeFieldsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RecipientStatus deliveryStatus = RecipientStatus.PENDING;

    @Builder.Default
    private int retryCount = 0;

    private String errorMessage;
    private String messageId;
    private Instant sentAt;
    private Long attachmentSizeBytes;

    public enum RecipientStatus {
        PENDING, SENT, FAILED, SKIPPED
    }

    public void markSent(String messageId, long attachmentSizeBytes) {
        this.deliveryStatus = RecipientStatus.SENT;
        this.messageId = messageId;
        this.sentAt = Instant.now();
        this.attachmentSizeBytes = attachmentSizeBytes;
    }

    public void markFailed(String errorMessage) {
        this.deliveryStatus = RecipientStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
    }

    public void markSkipped(String reason) {
        this.deliveryStatus = RecipientStatus.SKIPPED;
        this.errorMessage = reason;
    }

    public boolean hasPdfAttachment() {
        return pdfFilePath != null && !pdfFilePath.isBlank()
                || pdfBase64 != null && !pdfBase64.isBlank();
    }
}

package com.esolutions.massmailer.customer.model;

import com.esolutions.massmailer.model.DeliveryMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent customer (invoice recipient) registry entry.
 *
 * A CustomerContact is scoped to an Organization — the same email address
 * at two different orgs creates two separate registry entries.
 *
 * This is the "customer master" that persists across campaigns. Every time
 * an invoice is dispatched to a recipient, their contact record is upserted
 * here before the email is sent.
 */
@Entity
@Table(name = "customer_contacts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_org_email",
                columnNames = {"organizationId", "email"}),
        indexes = {
                @Index(name = "idx_customer_org", columnList = "organizationId"),
                @Index(name = "idx_customer_email", columnList = "email"),
                @Index(name = "idx_customer_erp_ref", columnList = "erpCustomerId")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerContact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The organization that owns this customer relationship */
    @Column(nullable = false)
    private UUID organizationId;

    /** Customer email address — unique per org */
    @Column(nullable = false)
    private String email;

    /** Customer display name */
    private String name;

    /** Company / trading name */
    private String companyName;

    /** Phone number for contact records */
    private String phone;

    /** ERP-native customer ID (e.g. Sage CUSTOMERID, QB CustomerId, D365 AccountNumber) */
    @Column(length = 100)
    private String erpCustomerId;

    /** ERP source that this customer was first registered from */
    @Column(length = 30)
    private String erpSource;

    // ── Delivery preferences ──

    /**
     * Delivery mode override for this customer.
     * Null = inherit from the organization's default deliveryMode.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private DeliveryMode deliveryMode;

    /** Customer's PEPPOL participant ID (e.g. 0190:ZW12345678) */
    @Column(length = 100)
    private String peppolParticipantId;

    /** Zimbabwe VAT number */
    @Column(length = 50)
    private String vatNumber;

    /** Zimbabwe TIN number — fallback if no VAT */
    @Column(length = 50)
    private String tinNumber;

    /** Whether this customer has unsubscribed from invoice emails */
    @Builder.Default
    private boolean unsubscribed = false;

    /** Reason for unsubscribe (if applicable) */
    private String unsubscribeReason;

    // ── Delivery statistics ──

    /** Total invoices sent to this customer across all campaigns */
    @Builder.Default
    private long totalInvoicesSent = 0;

    /** Total invoices that failed delivery */
    @Builder.Default
    private long totalDeliveryFailures = 0;

    /** Timestamp of the last invoice sent to this customer */
    private Instant lastInvoiceSentAt;

    // ── Lifecycle ──

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    // ── Domain logic ──

    public void recordDelivery(boolean success) {
        if (success) {
            this.totalInvoicesSent++;
            this.lastInvoiceSentAt = Instant.now();
        } else {
            this.totalDeliveryFailures++;
        }
        this.updatedAt = Instant.now();
    }

    public void updateContact(String name, String companyName, String erpCustomerId) {
        if (name != null && !name.isBlank()) this.name = name;
        if (companyName != null && !companyName.isBlank()) this.companyName = companyName;
        if (erpCustomerId != null && !erpCustomerId.isBlank()) this.erpCustomerId = erpCustomerId;
        this.updatedAt = Instant.now();
    }
}

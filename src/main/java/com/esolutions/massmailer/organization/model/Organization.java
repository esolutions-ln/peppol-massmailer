package com.esolutions.massmailer.organization.model;

import com.esolutions.massmailer.model.DeliveryMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered organization (tenant) in the invoice delivery network.
 *
 * In the PEPPOL model, this is the "Sender" — the entity whose ERP
 * produces invoices that flow through our access point to recipients.
 * Each org has its own:
 * - Sender identity (no-reply email, display name, branding)
 * - ERP connection(s) — Sage, QuickBooks, D365, or direct API
 * - Rate profile for billing
 * - Usage metering
 */
@Entity
@Table(name = "organizations", indexes = {
        @Index(name = "idx_org_api_key", columnList = "apiKey", unique = true),
        @Index(name = "idx_org_status", columnList = "status")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Organization name (e.g. "eSolutions", "FML Group") */
    @Column(nullable = false)
    private String name;

    /** Unique slug / short code for API routing (e.g. "esolutions", "fml-group") */
    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    /** API key for authenticating ERP-to-mailer API calls */
    @Column(nullable = false, unique = true, length = 128)
    private String apiKey;

    // ── Sender Identity (no-reply config per org) ──

    /** No-reply email address used as From header */
    @Column(nullable = false)
    private String senderEmail;

    /** Display name in From header (e.g. "eSolutions Accounts") */
    @Column(nullable = false)
    private String senderDisplayName;

    /** Reply-to address (typically same as senderEmail for no-reply) */
    private String replyToEmail;

    // ── Branding ──

    /** Company name shown in email template header/footer */
    private String companyName;

    /** Company address for email footer */
    private String companyAddress;

    /** Contact email for invoice queries (shown in email body) */
    private String accountsEmail;

    /** Support phone number */
    private String supportPhone;

    // ── ERP Configuration ──

    /** Primary ERP source: SAGE_INTACCT, QUICKBOOKS_ONLINE, DYNAMICS_365, GENERIC_API */
    @Column(length = 30)
    private String primaryErpSource;

    /** ERP tenant/company identifier */
    private String erpTenantId;

    // ── Billing ──

    /** Foreign key to the rate profile applied to this org */
    @Column(name = "rate_profile_id")
    private UUID rateProfileId;

    // ── Delivery Configuration ──

    /**
     * Default invoice delivery channel for this organization.
     * EMAIL = PDF email, AS4 = PEPPOL network, BOTH = email + PEPPOL.
     * Can be overridden per CustomerContact.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private DeliveryMode deliveryMode = DeliveryMode.EMAIL;

    /** Organization's own PEPPOL participant ID (e.g. 0190:ZW12345678) */
    @Column(length = 100)
    private String peppolParticipantId;

    /** Zimbabwe VAT number — used to derive peppolParticipantId */
    @Column(length = 50)
    private String vatNumber;

    /** Zimbabwe TIN number — fallback if no VAT number */
    @Column(length = 50)
    private String tinNumber;

    // ── C4 ERP Webhook (inbound document routing) ──

    /**
     * HTTPS URL of the buyer ERP webhook to which inbound PEPPOL documents are forwarded.
     * Nullable — organisations without C4 routing configured will have this unset.
     * Must be a valid HTTPS URL when present.
     */
    @Column(length = 2048)
    private String c4WebhookUrl;

    /**
     * Bearer token used to authenticate POST requests to {@link #c4WebhookUrl}.
     * Nullable — only required when the C4 webhook endpoint demands authentication.
     *
     * SECURITY NOTE (Requirement 15.5): This value should be encrypted at rest using
     * AES-256 before storage. Encryption is a planned enhancement; treat this field
     * as sensitive and avoid logging its value.
     */
    @Column(length = 512)
    private String c4WebhookAuthToken;

    // ── Lifecycle ──

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OrgStatus status = OrgStatus.ACTIVE;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant suspendedAt;

    public enum OrgStatus {
        ACTIVE,
        SUSPENDED,
        DEACTIVATED
    }

    public boolean isActive() {
        return status == OrgStatus.ACTIVE;
    }
}

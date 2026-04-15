package com.esolutions.massmailer.customer.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.model.DeliveryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages the persistent customer contact registry.
 *
 * Before any invoice is dispatched, the recipient is upserted into this
 * registry — creating a new record on first contact, or updating existing
 * contact details if they've changed in the ERP.
 *
 * This ensures every customer who receives an invoice has a traceable,
 * auditable record in the system independent of campaign lifecycle.
 */
@Service
public class CustomerContactService {

    private static final Logger log = LoggerFactory.getLogger(CustomerContactService.class);

    private final CustomerContactRepository repo;

    public CustomerContactService(CustomerContactRepository repo) {
        this.repo = repo;
    }

    /**
     * Upserts a customer contact from a canonical invoice.
     * Creates the record if the email is new for this org, otherwise updates contact details.
     *
     * @return the persisted (new or updated) CustomerContact
     * @throws IllegalArgumentException if the invoice has no recipient email
     */
    @Transactional
    public CustomerContact upsert(UUID organizationId, CanonicalInvoice invoice) {
        String email = invoice.recipientEmail();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Invoice " + invoice.invoiceNumber() + " has no recipient email — cannot register customer");
        }

        String normalizedEmail = email.trim().toLowerCase();

        return repo.findByOrganizationIdAndEmail(organizationId, normalizedEmail)
                .map(existing -> {
                    existing.updateContact(
                            invoice.recipientName(),
                            invoice.recipientCompany(),
                            invoice.erpInvoiceId()
                    );
                    log.debug("Updated customer contact: {} [org={}]", normalizedEmail, organizationId);
                    return repo.save(existing);
                })
                .orElseGet(() -> {
                    var contact = CustomerContact.builder()
                            .organizationId(organizationId)
                            .email(normalizedEmail)
                            .name(invoice.recipientName())
                            .companyName(invoice.recipientCompany())
                            .erpCustomerId(invoice.erpInvoiceId())
                            .erpSource(invoice.erpSource() != null ? invoice.erpSource().name() : null)
                            .build();
                    log.info("Registered new customer contact: {} [org={}]", normalizedEmail, organizationId);
                    return repo.save(contact);
                });
    }

    /**
     * Upserts a customer from raw fields (used for multipart/upload flow where
     * there is no CanonicalInvoice — the caller provides metadata directly).
     */
    @Transactional
    public CustomerContact upsert(UUID organizationId, String email, String name,
                                   String companyName, String erpSource) {
        return upsert(organizationId, email, name, companyName, erpSource, null, null, null, null);
    }

    /**
     * Full upsert with delivery mode and PEPPOL identity fields.
     * Used by the customer registration API endpoint.
     */
    @Transactional
    public CustomerContact upsert(UUID organizationId, String email, String name,
                                   String companyName, String erpSource,
                                   DeliveryMode deliveryMode,
                                   String vatNumber, String tinNumber,
                                   String peppolParticipantId) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Recipient email is required to register customer");
        }

        String normalizedEmail = email.trim().toLowerCase();

        // Derive participant ID from VAT/TIN if not explicitly provided
        String resolvedParticipantId = peppolParticipantId;
        if (resolvedParticipantId == null || resolvedParticipantId.isBlank()) {
            if (vatNumber != null && !vatNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + vatNumber.trim();
            } else if (tinNumber != null && !tinNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + tinNumber.trim();
            }
        }

        final String finalParticipantId = resolvedParticipantId;

        return repo.findByOrganizationIdAndEmail(organizationId, normalizedEmail)
                .map(existing -> {
                    existing.updateContact(name, companyName, null);
                    if (deliveryMode != null) existing.setDeliveryMode(deliveryMode);
                    if (vatNumber != null) existing.setVatNumber(vatNumber.trim());
                    if (tinNumber != null) existing.setTinNumber(tinNumber.trim());
                    if (finalParticipantId != null) existing.setPeppolParticipantId(finalParticipantId);
                    return repo.save(existing);
                })
                .orElseGet(() -> {
                    var contact = CustomerContact.builder()
                            .organizationId(organizationId)
                            .email(normalizedEmail)
                            .name(name)
                            .companyName(companyName)
                            .erpSource(erpSource)
                            .deliveryMode(deliveryMode)
                            .vatNumber(vatNumber != null ? vatNumber.trim() : null)
                            .tinNumber(tinNumber != null ? tinNumber.trim() : null)
                            .peppolParticipantId(finalParticipantId)
                            .build();
                    log.info("Registered new customer contact: {} [org={}]", normalizedEmail, organizationId);
                    return repo.save(contact);
                });
    }

    /**
     * Bulk upsert for all recipients in a canonical invoice list.
     * Validates that every invoice has a recipient email before any DB write,
     * then checks that no existing contact is unsubscribed before writing.
     *
     * @throws IllegalArgumentException if any invoice is missing a recipient email,
     *                                  or if any existing contact has unsubscribed == true
     */
    @Transactional
    public List<CustomerContact> upsertAll(UUID organizationId, List<CanonicalInvoice> invoices) {
        // Pass 1: Validate all emails first — fail fast before touching the DB
        var missing = invoices.stream()
                .filter(inv -> inv.recipientEmail() == null || inv.recipientEmail().isBlank())
                .map(CanonicalInvoice::invoiceNumber)
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "The following invoices are missing recipient email addresses: " + missing
                            + ". Register customer contacts before dispatching.");
        }

        // Pass 2: Check for unsubscribed contacts — fail fast before any DB writes
        var unsubscribed = invoices.stream()
                .map(inv -> inv.recipientEmail().trim().toLowerCase())
                .distinct()
                .filter(email -> repo.findByOrganizationIdAndEmail(organizationId, email)
                        .map(CustomerContact::isUnsubscribed)
                        .orElse(false))
                .toList();

        if (!unsubscribed.isEmpty()) {
            throw new IllegalArgumentException(
                    "The following recipients have unsubscribed and cannot receive emails: " + unsubscribed);
        }

        return invoices.stream()
                .map(inv -> upsert(organizationId, inv))
                .toList();
    }

    /** Records a delivery outcome against the customer's stats */
    @Transactional
    public void recordDelivery(UUID organizationId, String email, boolean success) {
        String normalizedEmail = email.trim().toLowerCase();
        repo.findByOrganizationIdAndEmail(organizationId, normalizedEmail)
                .ifPresent(contact -> {
                    contact.recordDelivery(success);
                    repo.save(contact);
                });
    }

    @Transactional(readOnly = true)
    public List<CustomerContact> listByOrg(UUID organizationId) {
        return repo.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    @Transactional(readOnly = true)
    public CustomerContact getByEmail(UUID organizationId, String email) {
        return repo.findByOrganizationIdAndEmail(organizationId, email.trim().toLowerCase())
                .orElse(null);
    }
}

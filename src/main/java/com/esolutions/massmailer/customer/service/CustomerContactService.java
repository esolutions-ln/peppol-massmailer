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

    /**
     * Look up a customer by BPN first, then VAT, then TIN, scoped to the organization.
     * BPN (ZIMRA Business Partner Number) is the most stable identifier on fiscal tax invoices.
     *
     * VAT / TIN / BPN are NOT unique per org — the same buyer entity may run multiple
     * accounts across locations or branches sharing the same fiscal identifiers. When
     * several rows match, the most recently registered one is returned. Use
     * {@link #listByTaxId(UUID, String, String, String)} when you need all matches.
     *
     * Returns null if no identifier matches or all inputs are blank.
     */
    @Transactional(readOnly = true)
    public CustomerContact getByTaxId(UUID organizationId, String bpn,
                                      String vatNumber, String tinNumber) {
        if (bpn != null && !bpn.isBlank()) {
            var byBpn = repo.findFirstByOrganizationIdAndBpnOrderByCreatedAtDesc(
                    organizationId, bpn.trim());
            if (byBpn.isPresent()) return byBpn.get();
        }
        if (vatNumber != null && !vatNumber.isBlank()) {
            var byVat = repo.findFirstByOrganizationIdAndVatNumberOrderByCreatedAtDesc(
                    organizationId, vatNumber.trim());
            if (byVat.isPresent()) return byVat.get();
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            var byTin = repo.findFirstByOrganizationIdAndTinNumberOrderByCreatedAtDesc(
                    organizationId, tinNumber.trim());
            if (byTin.isPresent()) return byTin.get();
        }
        return null;
    }

    /**
     * Returns all customers in the org whose BPN / VAT / TIN match — used when a buyer
     * has multiple branch accounts under shared fiscal identifiers. Matches are merged
     * across the supplied identifiers; duplicate rows are de-duplicated by id.
     */
    @Transactional(readOnly = true)
    public List<CustomerContact> listByTaxId(UUID organizationId, String bpn,
                                             String vatNumber, String tinNumber) {
        var seen = new java.util.LinkedHashMap<UUID, CustomerContact>();
        if (bpn != null && !bpn.isBlank()) {
            for (var c : repo.findByOrganizationIdAndBpn(organizationId, bpn.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        if (vatNumber != null && !vatNumber.isBlank()) {
            for (var c : repo.findByOrganizationIdAndVatNumber(organizationId, vatNumber.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            for (var c : repo.findByOrganizationIdAndTinNumber(organizationId, tinNumber.trim())) {
                seen.putIfAbsent(c.getId(), c);
            }
        }
        return List.copyOf(seen.values());
    }

    /**
     * Upsert by ERP customer id — the unique key used for CSV imports.
     * Matches on (orgId, erpCustomerId); if no match, creates a new record.
     * Email is optional here (the registry no longer requires it).
     *
     * @return a pair of (contact, wasCreated)
     */
    @Transactional
    public UpsertResult upsertByErpCustomerId(UUID organizationId, String erpCustomerId,
                                              String email, String name, String phone,
                                              String companyName, String tradingName,
                                              String erpSource,
                                              DeliveryMode deliveryMode,
                                              String vatNumber, String tinNumber, String bpn,
                                              String peppolParticipantId,
                                              String addressLine1, String addressLine2,
                                              String city, String country) {
        if (erpCustomerId == null || erpCustomerId.isBlank()) {
            throw new IllegalArgumentException("erpCustomerId is required");
        }
        String customerId = erpCustomerId.trim();
        String normalizedEmail = (email != null && !email.isBlank()) ? email.trim().toLowerCase() : null;

        String resolvedParticipantId = peppolParticipantId;
        if (resolvedParticipantId == null || resolvedParticipantId.isBlank()) {
            if (vatNumber != null && !vatNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + vatNumber.trim();
            } else if (tinNumber != null && !tinNumber.isBlank()) {
                resolvedParticipantId = "0190:ZW" + tinNumber.trim();
            }
        }
        final String finalParticipantId = resolvedParticipantId;

        var existing = repo.findByOrganizationIdAndErpCustomerId(organizationId, customerId);
        if (existing.isPresent()) {
            var c = existing.get();
            if (normalizedEmail != null) c.setEmail(normalizedEmail);
            if (name != null) c.setName(name.trim());
            if (phone != null) c.setPhone(phone.trim());
            if (companyName != null) c.setCompanyName(companyName.trim());
            if (tradingName != null) c.setTradingName(tradingName.trim());
            if (erpSource != null) c.setErpSource(erpSource.trim());
            if (deliveryMode != null) c.setDeliveryMode(deliveryMode);
            if (vatNumber != null) c.setVatNumber(vatNumber.trim());
            if (tinNumber != null) c.setTinNumber(tinNumber.trim());
            if (bpn != null) c.setBpn(bpn.trim());
            if (finalParticipantId != null) c.setPeppolParticipantId(finalParticipantId);
            if (addressLine1 != null) c.setAddressLine1(addressLine1.trim());
            if (addressLine2 != null) c.setAddressLine2(addressLine2.trim());
            if (city != null) c.setCity(city.trim());
            if (country != null) c.setCountry(country.trim());
            return new UpsertResult(repo.save(c), false);
        }

        var contact = CustomerContact.builder()
                .organizationId(organizationId)
                .erpCustomerId(customerId)
                .email(normalizedEmail)
                .name(name != null ? name.trim() : null)
                .phone(phone != null ? phone.trim() : null)
                .companyName(companyName != null ? companyName.trim() : null)
                .tradingName(tradingName != null ? tradingName.trim() : null)
                .erpSource(erpSource)
                .deliveryMode(deliveryMode)
                .vatNumber(vatNumber != null ? vatNumber.trim() : null)
                .tinNumber(tinNumber != null ? tinNumber.trim() : null)
                .bpn(bpn != null ? bpn.trim() : null)
                .peppolParticipantId(finalParticipantId)
                .addressLine1(addressLine1 != null ? addressLine1.trim() : null)
                .addressLine2(addressLine2 != null ? addressLine2.trim() : null)
                .city(city != null ? city.trim() : null)
                .country(country != null ? country.trim() : null)
                .build();
        log.info("Registered new customer contact: erpCustomerId={} [org={}]", customerId, organizationId);
        return new UpsertResult(repo.save(contact), true);
    }

    public record UpsertResult(CustomerContact contact, boolean created) {}

    /**
     * Updates an existing customer by id. Email is normalised; null fields are ignored,
     * blank strings clear the field. Throws if no customer with that id exists in the org.
     */
    @Transactional
    public CustomerContact updateById(UUID organizationId, UUID id,
                                       String email, String name, String phone,
                                       String companyName, String tradingName,
                                       String erpSource, String erpCustomerId,
                                       DeliveryMode deliveryMode,
                                       String vatNumber, String tinNumber, String bpn,
                                       String peppolParticipantId,
                                       String addressLine1, String addressLine2,
                                       String city, String country,
                                       Boolean unsubscribed) {
        var existing = repo.findById(id)
                .filter(c -> c.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer " + id + " not found in organization " + organizationId));

        if (email != null && !email.isBlank()) existing.setEmail(email.trim().toLowerCase());
        if (name != null) existing.setName(name.isBlank() ? null : name.trim());
        if (phone != null) existing.setPhone(phone.isBlank() ? null : phone.trim());
        if (companyName != null) existing.setCompanyName(companyName.isBlank() ? null : companyName.trim());
        if (tradingName != null) existing.setTradingName(tradingName.isBlank() ? null : tradingName.trim());
        if (erpSource != null) existing.setErpSource(erpSource.isBlank() ? null : erpSource.trim());
        if (erpCustomerId != null) existing.setErpCustomerId(erpCustomerId.isBlank() ? null : erpCustomerId.trim());
        if (deliveryMode != null) existing.setDeliveryMode(deliveryMode);
        if (vatNumber != null) existing.setVatNumber(vatNumber.isBlank() ? null : vatNumber.trim());
        if (tinNumber != null) existing.setTinNumber(tinNumber.isBlank() ? null : tinNumber.trim());
        if (bpn != null) existing.setBpn(bpn.isBlank() ? null : bpn.trim());
        if (addressLine1 != null) existing.setAddressLine1(addressLine1.isBlank() ? null : addressLine1.trim());
        if (addressLine2 != null) existing.setAddressLine2(addressLine2.isBlank() ? null : addressLine2.trim());
        if (city != null) existing.setCity(city.isBlank() ? null : city.trim());
        if (country != null) existing.setCountry(country.isBlank() ? null : country.trim());
        if (unsubscribed != null) existing.setUnsubscribed(unsubscribed);

        // Auto-derive PEPPOL participant id from VAT/TIN when caller didn't supply one
        if (peppolParticipantId != null && !peppolParticipantId.isBlank()) {
            existing.setPeppolParticipantId(peppolParticipantId.trim());
        } else if (vatNumber != null || tinNumber != null) {
            String vat = existing.getVatNumber();
            String tin = existing.getTinNumber();
            if (vat != null && !vat.isBlank()) {
                existing.setPeppolParticipantId("0190:ZW" + vat);
            } else if (tin != null && !tin.isBlank()) {
                existing.setPeppolParticipantId("0190:ZW" + tin);
            }
        }

        log.info("Updated customer contact {} [org={}]", id, organizationId);
        return repo.save(existing);
    }

    /** Back-compat overload — no BPN supplied. */
    @Transactional(readOnly = true)
    public CustomerContact getByTaxId(UUID organizationId, String vatNumber, String tinNumber) {
        return getByTaxId(organizationId, null, vatNumber, tinNumber);
    }

    /**
     * Full upsert including ZIMRA BPN, trading name, and address fields.
     * Used by the registration API when a fiscal invoice surfaces buyer details
     * not yet on the registry record.
     */
    @Transactional
    public CustomerContact upsertFull(UUID organizationId, String email, String name,
                                       String companyName, String tradingName,
                                       String erpSource, String erpCustomerId,
                                       String phone,
                                       DeliveryMode deliveryMode,
                                       String vatNumber, String tinNumber, String bpn,
                                       String peppolParticipantId,
                                       String addressLine1, String addressLine2,
                                       String city, String country) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Recipient email is required to register customer");
        }
        String normalizedEmail = email.trim().toLowerCase();

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
                    existing.updateContact(name, companyName, erpCustomerId);
                    if (phone != null) existing.setPhone(phone.trim());
                    if (tradingName != null) existing.setTradingName(tradingName.trim());
                    if (deliveryMode != null) existing.setDeliveryMode(deliveryMode);
                    if (vatNumber != null) existing.setVatNumber(vatNumber.trim());
                    if (tinNumber != null) existing.setTinNumber(tinNumber.trim());
                    if (bpn != null) existing.setBpn(bpn.trim());
                    if (finalParticipantId != null) existing.setPeppolParticipantId(finalParticipantId);
                    if (addressLine1 != null) existing.setAddressLine1(addressLine1.trim());
                    if (addressLine2 != null) existing.setAddressLine2(addressLine2.trim());
                    if (city != null) existing.setCity(city.trim());
                    if (country != null) existing.setCountry(country.trim());
                    return repo.save(existing);
                })
                .orElseGet(() -> {
                    var contact = CustomerContact.builder()
                            .organizationId(organizationId)
                            .email(normalizedEmail)
                            .name(name)
                            .companyName(companyName)
                            .tradingName(tradingName != null ? tradingName.trim() : null)
                            .phone(phone != null ? phone.trim() : null)
                            .erpCustomerId(erpCustomerId != null ? erpCustomerId.trim() : null)
                            .erpSource(erpSource)
                            .deliveryMode(deliveryMode)
                            .vatNumber(vatNumber != null ? vatNumber.trim() : null)
                            .tinNumber(tinNumber != null ? tinNumber.trim() : null)
                            .bpn(bpn != null ? bpn.trim() : null)
                            .peppolParticipantId(finalParticipantId)
                            .addressLine1(addressLine1 != null ? addressLine1.trim() : null)
                            .addressLine2(addressLine2 != null ? addressLine2.trim() : null)
                            .city(city != null ? city.trim() : null)
                            .country(country != null ? country.trim() : null)
                            .build();
                    log.info("Registered new customer contact: {} [org={}, bpn={}, vat={}]",
                            normalizedEmail, organizationId, bpn, vatNumber);
                    return repo.save(contact);
                });
    }
}

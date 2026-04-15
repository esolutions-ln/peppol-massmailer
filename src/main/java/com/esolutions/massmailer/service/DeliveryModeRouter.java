package com.esolutions.massmailer.service;

import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves the effective {@link DeliveryMode} for a given recipient within an organization.
 *
 * <p>Precedence (highest to lowest):
 * <ol>
 *   <li>Customer-level override — {@code CustomerContact.deliveryMode} when non-null</li>
 *   <li>Organization default — {@code Organization.deliveryMode}</li>
 *   <li>System fallback — {@code EMAIL}</li>
 * </ol>
 *
 * <p>Satisfies Requirements 11.1, 11.2, 11.3.
 */
@Service
public class DeliveryModeRouter {

    private static final Logger log = LoggerFactory.getLogger(DeliveryModeRouter.class);

    private final OrganizationRepository orgRepo;
    private final CustomerContactRepository customerRepo;

    public DeliveryModeRouter(OrganizationRepository orgRepo,
                              CustomerContactRepository customerRepo) {
        this.orgRepo = orgRepo;
        this.customerRepo = customerRepo;
    }

    /**
     * Resolves the effective delivery mode for a recipient.
     *
     * @param organizationId the owning organization's UUID (must not be null)
     * @param recipientEmail the recipient's email address
     * @return the effective {@link DeliveryMode} — never null
     */
    public DeliveryMode resolveDeliveryMode(UUID organizationId, String recipientEmail) {
        // Determine org-level default (fallback to EMAIL if org not found or mode is null)
        DeliveryMode orgMode = orgRepo.findById(organizationId)
                .map(org -> org.getDeliveryMode() != null ? org.getDeliveryMode() : DeliveryMode.EMAIL)
                .orElse(DeliveryMode.EMAIL);

        // Check for customer-level override
        return customerRepo.findByOrganizationIdAndEmail(organizationId, recipientEmail)
                .filter(contact -> contact.getDeliveryMode() != null)
                .map(contact -> {
                    log.debug("Customer override for {}: {}", recipientEmail, contact.getDeliveryMode());
                    return contact.getDeliveryMode();
                })
                .orElseGet(() -> {
                    log.debug("Using org default for {}: {}", recipientEmail, orgMode);
                    return orgMode;
                });
    }
}

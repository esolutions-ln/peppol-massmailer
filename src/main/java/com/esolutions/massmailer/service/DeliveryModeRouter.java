package com.esolutions.massmailer.service;

import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves the effective delivery mode for an invoice recipient.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>Customer-level override (if non-null)</li>
 *   <li>Organisation default (if non-null)</li>
 *   <li>Fallback to {@code EMAIL}</li>
 * </ol>
 */
@Service
public class DeliveryModeRouter {

    private final OrganizationRepository orgRepo;
    private final CustomerContactRepository customerRepo;

    public DeliveryModeRouter(OrganizationRepository orgRepo,
                               CustomerContactRepository customerRepo) {
        this.orgRepo = orgRepo;
        this.customerRepo = customerRepo;
    }

    /**
     * Resolves the delivery mode for a recipient.
     *
     * @param orgId organisation UUID
     * @param email recipient email address
     * @return effective delivery mode
     */
    public DeliveryMode resolveDeliveryMode(UUID orgId, String email) {
        var customerOpt = customerRepo.findByOrganizationIdAndEmail(orgId, email);
        if (customerOpt.isPresent()) {
            var customerMode = customerOpt.get().getDeliveryMode();
            if (customerMode != null) {
                return customerMode;
            }
        }

        var orgOpt = orgRepo.findById(orgId);
        if (orgOpt.isPresent()) {
            var orgMode = orgOpt.get().getDeliveryMode();
            if (orgMode != null) {
                return orgMode;
            }
        }

        return DeliveryMode.EMAIL;
    }
}

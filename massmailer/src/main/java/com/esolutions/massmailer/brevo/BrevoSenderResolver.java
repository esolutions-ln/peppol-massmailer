package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.security.OrgPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the "From" identity for an outbound mail.
 *
 * Lookup order (first hit wins):
 *  1. Explicit organisation id supplied by the caller (e.g. the campaign's owning org on
 *     async dispatch, where there is no SecurityContext) → its senderEmail.
 *  2. Currently-authenticated organisation → use {@link Organization#getSenderEmail()}.
 *  3. Customer record by erpCustomerId (= account number) → its org's senderEmail.
 *  4. Customer record by tinNumber → its org's senderEmail.
 *  5. Fallback to {@link MailerProperties#fromAddress()} + {@link MailerProperties#fromName()}.
 *
 * Note: when running under the platform's single Brevo account, the resolved email
 * must be a verified sender in Brevo or the API will reject the send.
 */
@Component
public class BrevoSenderResolver {

    private static final Logger log = LoggerFactory.getLogger(BrevoSenderResolver.class);

    private final OrganizationRepository orgs;
    private final CustomerContactRepository customers;
    private final MailerProperties props;

    public BrevoSenderResolver(OrganizationRepository orgs,
                               CustomerContactRepository customers,
                               MailerProperties props) {
        this.orgs = orgs;
        this.customers = customers;
        this.props = props;
    }

    /** Sender identity to use on an outbound mail. */
    public record Sender(String email, String name, String replyTo) {}

    /**
     * Resolve the sender by current auth and optional customer lookup hints.
     *
     * @param accountNumber the customer's account number (= erpCustomerId on CustomerContact); nullable
     * @param tinNumber     the customer's TIN; nullable — used only if accountNumber misses
     */
    public Sender resolve(String accountNumber, String tinNumber) {
        return resolve(null, accountNumber, tinNumber);
    }

    /**
     * Resolve the sender, preferring an explicitly-known owning organisation.
     *
     * @param organizationId the org the mail is sent on behalf of; nullable
     * @param accountNumber  the customer's account number (= erpCustomerId on CustomerContact); nullable
     * @param tinNumber      the customer's TIN; nullable — used only if accountNumber misses
     */
    public Sender resolve(UUID organizationId, String accountNumber, String tinNumber) {
        if (organizationId != null) {
            Optional<Organization> explicit = orgs.findById(organizationId);
            if (explicit.isPresent()) {
                return toSender(explicit.get());
            }
            log.warn("Organisation {} not found while resolving sender — falling back", organizationId);
        }

        Optional<Organization> fromAuth = currentOrg();
        if (fromAuth.isPresent()) {
            return toSender(fromAuth.get());
        }

        Optional<Organization> fromCustomer = lookupOrgViaCustomer(accountNumber, tinNumber);
        if (fromCustomer.isPresent()) {
            return toSender(fromCustomer.get());
        }

        log.debug("No org/customer context available — falling back to MailerProperties default sender");
        return new Sender(props.fromAddress(), props.fromName(), props.fromAddress());
    }

    /** Convenience: resolve with no customer hints (auth-only or fallback). */
    public Sender resolve() {
        return resolve(null, null);
    }

    private Optional<Organization> currentOrg() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        Object p = auth.getPrincipal();
        if (p instanceof OrgPrincipal op && op.org() != null) {
            return Optional.of(op.org());
        }
        return Optional.empty();
    }

    private Optional<Organization> lookupOrgViaCustomer(String accountNumber, String tinNumber) {
        // The customer registry is org-scoped — without an org we can't scope the query.
        // In that absence, fall back to ANY customer with the identifier (first match across orgs).
        // This path is used for unauthenticated/background sends where the caller has only the
        // buyer's account number or TIN. The chosen org's senderEmail must still be a verified
        // Brevo sender — otherwise the send will be rejected at the API.
        if (accountNumber != null && !accountNumber.isBlank()) {
            Optional<CustomerContact> hit =
                    customers.findFirstByErpCustomerIdOrderByCreatedAtDesc(accountNumber);
            if (hit.isPresent()) return orgs.findById(hit.get().getOrganizationId());
        }
        if (tinNumber != null && !tinNumber.isBlank()) {
            Optional<CustomerContact> hit =
                    customers.findFirstByTinNumberOrderByCreatedAtDesc(tinNumber);
            if (hit.isPresent()) return orgs.findById(hit.get().getOrganizationId());
        }
        return Optional.empty();
    }

    private Sender toSender(Organization o) {
        String email = (o.getSenderEmail() != null && !o.getSenderEmail().isBlank())
                ? o.getSenderEmail() : props.fromAddress();
        String name = (o.getSenderDisplayName() != null && !o.getSenderDisplayName().isBlank())
                ? o.getSenderDisplayName() : props.fromName();
        // Reply-To: explicit replyToEmail → accountsEmail (so customer replies reach the
        // org's accounts desk instead of the no-reply sender) → senderEmail.
        String replyTo = firstNonBlank(o.getReplyToEmail(), o.getAccountsEmail(), email);
        return new Sender(email, name, replyTo);
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }
}

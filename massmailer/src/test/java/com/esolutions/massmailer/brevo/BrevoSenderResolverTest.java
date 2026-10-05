package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reply-To resolution for org-scoped sends: replyToEmail → accountsEmail → senderEmail.
 * Exercised via the customer-lookup path, which is how background (PDF watcher) sends resolve.
 */
class BrevoSenderResolverTest {

    private static final String ACCOUNT = "ACC-001";

    private OrganizationRepository orgs;
    private CustomerContactRepository customers;
    private BrevoSenderResolver resolver;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        orgs = mock(OrganizationRepository.class);
        customers = mock(CustomerContactRepository.class);
        var props = new MailerProperties("no-reply@platform.test", "Platform",
                0, 0, 0, 0, false, null, 0, null);
        resolver = new BrevoSenderResolver(orgs, customers, props);
    }

    private void givenOrg(String replyTo, String accounts) {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder()
                .id(orgId)
                .senderEmail("noreply@acme.test")
                .senderDisplayName("Acme")
                .replyToEmail(replyTo)
                .accountsEmail(accounts)
                .build();
        CustomerContact customer = CustomerContact.builder().organizationId(orgId).build();
        when(customers.findFirstByErpCustomerIdOrderByCreatedAtDesc(ACCOUNT)).thenReturn(Optional.of(customer));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
    }

    @Test
    void explicitReplyToWins() {
        givenOrg("replies@acme.test", "accounts@acme.test");
        var sender = resolver.resolve(ACCOUNT, null);
        assertThat(sender.email()).isEqualTo("noreply@acme.test");
        assertThat(sender.replyTo()).isEqualTo("replies@acme.test");
    }

    @Test
    void fallsBackToAccountsEmail() {
        givenOrg(null, "accounts@acme.test");
        var sender = resolver.resolve(ACCOUNT, null);
        assertThat(sender.email()).isEqualTo("noreply@acme.test");
        assertThat(sender.replyTo()).isEqualTo("accounts@acme.test");
    }

    @Test
    void fallsBackToSenderEmailWhenAccountsBlank() {
        givenOrg(" ", "");
        var sender = resolver.resolve(ACCOUNT, null);
        assertThat(sender.replyTo()).isEqualTo("noreply@acme.test");
    }

    @Test
    void explicitOrgWinsOverCustomerLookup() {
        givenOrg(null, "accounts@acme.test");
        UUID otherId = UUID.randomUUID();
        Organization other = Organization.builder()
                .id(otherId)
                .senderEmail("billing@other.test")
                .senderDisplayName("Other Co")
                .accountsEmail("accounts@other.test")
                .build();
        when(orgs.findById(otherId)).thenReturn(Optional.of(other));

        var sender = resolver.resolve(otherId, ACCOUNT, null);
        assertThat(sender.email()).isEqualTo("billing@other.test");
        assertThat(sender.name()).isEqualTo("Other Co");
        assertThat(sender.replyTo()).isEqualTo("accounts@other.test");
    }

    @Test
    void unknownExplicitOrgFallsBackToCustomerLookup() {
        givenOrg(null, "accounts@acme.test");
        var sender = resolver.resolve(UUID.randomUUID(), ACCOUNT, null);
        assertThat(sender.email()).isEqualTo("noreply@acme.test");
    }

    @Test
    void noOrgUsesPlatformDefault() {
        var sender = resolver.resolve(null, null);
        assertThat(sender.email()).isEqualTo("no-reply@platform.test");
        assertThat(sender.replyTo()).isEqualTo("no-reply@platform.test");
    }
}

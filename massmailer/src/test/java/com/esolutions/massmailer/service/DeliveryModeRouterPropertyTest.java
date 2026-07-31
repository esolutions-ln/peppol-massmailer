package com.esolutions.massmailer.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import net.jqwik.api.*;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for DeliveryModeRouter.
 *
 * P10: Delivery Mode Routing — validates Requirements 11.1, 11.2, 11.3
 *
 * Note: jqwik does not process JUnit 5 extensions (@ExtendWith), so mocks are
 * created manually via Mockito.mock() for each property invocation.
 */
class DeliveryModeRouterPropertyTest {

    // ── Property P10: Delivery Mode Routing ──────────────────────────────────
    // Validates: Requirements 11.1, 11.2, 11.3

    /**
     * P10a — Customer-level override wins when non-null, regardless of org default.
     *
     * For all combinations of (customerOverride non-null, orgDefault), the resolved
     * mode must equal the customer override.
     *
     * **Validates: Requirements 11.1**
     */
    @Property
    void customerOverrideWinsWhenNonNull(
            @ForAll("nonNullDeliveryModes") DeliveryMode customerOverride,
            @ForAll("nullableDeliveryModes") DeliveryMode orgDefault
    ) {
        UUID orgId = UUID.randomUUID();
        String email = "customer@example.com";

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);

        Organization org = Organization.builder()
                .id(orgId)
                .name("Test Org")
                .slug("test-org")
                .apiKey("key")
                .senderEmail("noreply@test.com")
                .senderDisplayName("Test")
                .deliveryMode(orgDefault != null ? orgDefault : DeliveryMode.EMAIL)
                .build();

        CustomerContact contact = CustomerContact.builder()
                .organizationId(orgId)
                .email(email)
                .deliveryMode(customerOverride)
                .build();

        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(customerRepo.findByOrganizationIdAndEmail(orgId, email))
                .thenReturn(Optional.of(contact));

        DeliveryModeRouter router = new DeliveryModeRouter(orgRepo, customerRepo);
        DeliveryMode result = router.resolveDeliveryMode(orgId, email);

        assertThat(result)
                .as("Customer override %s must win over org default %s", customerOverride, orgDefault)
                .isEqualTo(customerOverride);
    }

    /**
     * P10b — When customer has no override, org default is used.
     *
     * For all non-null org defaults, the resolved mode must equal the org default.
     *
     * **Validates: Requirements 11.2**
     */
    @Property
    void orgDefaultUsedWhenNoCustomerOverride(
            @ForAll("nonNullDeliveryModes") DeliveryMode orgDefault
    ) {
        UUID orgId = UUID.randomUUID();
        String email = "customer@example.com";

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);

        Organization org = Organization.builder()
                .id(orgId)
                .name("Test Org")
                .slug("test-org")
                .apiKey("key")
                .senderEmail("noreply@test.com")
                .senderDisplayName("Test")
                .deliveryMode(orgDefault)
                .build();

        // Contact exists but has no delivery mode override
        CustomerContact contact = CustomerContact.builder()
                .organizationId(orgId)
                .email(email)
                .deliveryMode(null)
                .build();

        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(customerRepo.findByOrganizationIdAndEmail(orgId, email))
                .thenReturn(Optional.of(contact));

        DeliveryModeRouter router = new DeliveryModeRouter(orgRepo, customerRepo);
        DeliveryMode result = router.resolveDeliveryMode(orgId, email);

        assertThat(result)
                .as("Org default %s must be used when customer has no override", orgDefault)
                .isEqualTo(orgDefault);
    }

    /**
     * P10c — Defaults to EMAIL when both customer override and org default are absent.
     *
     * When neither the customer nor the org has a delivery mode configured,
     * the result must be EMAIL.
     *
     * **Validates: Requirements 11.3**
     */
    @Property
    void defaultsToEmailWhenBothAreNull() {
        UUID orgId = UUID.randomUUID();
        String email = "customer@example.com";

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);

        // Org not found in repository — triggers EMAIL fallback
        when(orgRepo.findById(orgId)).thenReturn(Optional.empty());
        when(customerRepo.findByOrganizationIdAndEmail(orgId, email))
                .thenReturn(Optional.empty());

        DeliveryModeRouter router = new DeliveryModeRouter(orgRepo, customerRepo);
        DeliveryMode result = router.resolveDeliveryMode(orgId, email);

        assertThat(result)
                .as("Must default to EMAIL when both customer and org have no delivery mode")
                .isEqualTo(DeliveryMode.EMAIL);
    }

    /**
     * P10d — No recipient with effective mode AS4 is routed to the email channel.
     *
     * When the resolved delivery mode is AS4, the result must not be EMAIL.
     *
     * **Validates: Requirements 11.3**
     */
    @Property
    void as4ModeIsNeverRoutedToEmail(
            @ForAll("as4Sources") As4Source source
    ) {
        UUID orgId = UUID.randomUUID();
        String email = "customer@example.com";

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);

        if (source == As4Source.CUSTOMER_OVERRIDE) {
            Organization org = Organization.builder()
                    .id(orgId)
                    .name("Test Org")
                    .slug("test-org")
                    .apiKey("key")
                    .senderEmail("noreply@test.com")
                    .senderDisplayName("Test")
                    .deliveryMode(DeliveryMode.EMAIL)
                    .build();

            CustomerContact contact = CustomerContact.builder()
                    .organizationId(orgId)
                    .email(email)
                    .deliveryMode(DeliveryMode.AS4)
                    .build();

            when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
            when(customerRepo.findByOrganizationIdAndEmail(orgId, email))
                    .thenReturn(Optional.of(contact));
        } else {
            // AS4 comes from org default, no customer override
            Organization org = Organization.builder()
                    .id(orgId)
                    .name("Test Org")
                    .slug("test-org")
                    .apiKey("key")
                    .senderEmail("noreply@test.com")
                    .senderDisplayName("Test")
                    .deliveryMode(DeliveryMode.AS4)
                    .build();

            when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
            when(customerRepo.findByOrganizationIdAndEmail(orgId, email))
                    .thenReturn(Optional.empty());
        }

        DeliveryModeRouter router = new DeliveryModeRouter(orgRepo, customerRepo);
        DeliveryMode result = router.resolveDeliveryMode(orgId, email);

        assertThat(result)
                .as("Effective mode AS4 must not resolve to EMAIL")
                .isNotEqualTo(DeliveryMode.EMAIL);
        assertThat(result)
                .as("Effective mode must be AS4 when configured as AS4")
                .isEqualTo(DeliveryMode.AS4);
    }

    // ── Arbitraries ──────────────────────────────────────────────────────────

    /** All three non-null DeliveryMode values. */
    @Provide
    Arbitrary<DeliveryMode> nonNullDeliveryModes() {
        return Arbitraries.of(DeliveryMode.values());
    }

    /** All three DeliveryMode values plus null (to represent "not configured"). */
    @Provide
    Arbitrary<DeliveryMode> nullableDeliveryModes() {
        return Arbitraries.of(DeliveryMode.EMAIL, DeliveryMode.AS4, DeliveryMode.BOTH, null);
    }

    /** Source of AS4 mode: either customer override or org default. */
    @Provide
    Arbitrary<As4Source> as4Sources() {
        return Arbitraries.of(As4Source.values());
    }

    enum As4Source {
        CUSTOMER_OVERRIDE,
        ORG_DEFAULT
    }
}

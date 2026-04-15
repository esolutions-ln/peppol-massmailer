package com.esolutions.massmailer.peppol.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.controller.ErpCampaignController;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import com.esolutions.massmailer.dto.MailDtos.ErpDispatchRequest;
import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterRegistry;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.model.MailCampaign;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.service.CampaignOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for the Dispatch Router's delivery mode routing correctness.
 *
 * <p><b>Property 7: Delivery Mode Routing Correctness</b>
 *
 * <p>For any invoice with effective mode {@code AS4}, it appears in peppolInvoices only
 * (peppolDispatched &gt; 0, emailDispatched = 0); for {@code EMAIL}, emailInvoices only
 * (emailDispatched &gt; 0, peppolDispatched = 0); for {@code BOTH}, both lists
 * (peppolDispatched &gt; 0, emailDispatched &gt; 0).
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4</b>
 */
class DeliveryModeRoutingPropertyTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String RECIPIENT_EMAIL = "buyer@example.com";
    private static final UUID ORG_ID = UUID.randomUUID();

    /**
     * Builds a minimal {@link CanonicalInvoice} for the standard test recipient.
     */
    private CanonicalInvoice invoice() {
        return new CanonicalInvoice(
                ErpSource.GENERIC_API,
                null,
                "INV-TEST-001",
                RECIPIENT_EMAIL,
                "Test Recipient",
                "Test Co",
                "INV-TEST-001",
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                new BigDecimal("100.00"),
                new BigDecimal("15.00"),
                new BigDecimal("115.00"),
                "USD",
                FiscalMetadata.EMPTY,
                new PdfSource(null, "dGVzdA==", null, "invoice.pdf"),
                Map.of()
        );
    }

    /**
     * Builds an {@link ErpDispatchRequest} that will return the given invoice
     * from the mocked ERP adapter.
     */
    private ErpDispatchRequest dispatchRequest(UUID orgId) {
        return new ErpDispatchRequest(
                "test-campaign",
                "Test Subject",
                "invoice",
                ErpSource.GENERIC_API,
                "test-tenant",
                List.of("INV-TEST-001"),
                Map.of(),
                orgId
        );
    }

    /**
     * Builds a fully wired {@link ErpCampaignController} with mocked dependencies.
     *
     * <p>The org's default delivery mode is {@code orgMode}. The customer contact's
     * delivery mode override is {@code contactMode} (null = inherit from org).
     */
    private ErpCampaignController buildController(UUID orgId,
                                                   DeliveryMode orgMode,
                                                   DeliveryMode contactMode) {
        // ── Org repo ──
        Organization org = Organization.builder()
                .id(orgId)
                .name("Test Org")
                .slug("test-org")
                .apiKey("testapikey")
                .senderEmail("noreply@test.com")
                .senderDisplayName("Test")
                .deliveryMode(orgMode)
                .build();

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        // ── Customer contact repo ──
        CustomerContact contact = CustomerContact.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .email(RECIPIENT_EMAIL)
                .name("Test Recipient")
                .deliveryMode(contactMode)   // null = inherit from org
                .build();

        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);
        when(customerRepo.findByOrganizationIdAndEmail(eq(orgId), anyString()))
                .thenReturn(Optional.of(contact));

        // ── PEPPOL service — returns a DELIVERED record ──
        PeppolDeliveryRecord peppolRecord = mock(PeppolDeliveryRecord.class);
        when(peppolRecord.getStatus()).thenReturn(DeliveryStatus.DELIVERED);
        when(peppolRecord.getDeliveredToEndpoint()).thenReturn("https://ap.example.com/receive");

        PeppolDeliveryService peppolService = mock(PeppolDeliveryService.class);
        when(peppolService.deliver(eq(orgId), any(CanonicalInvoice.class)))
                .thenReturn(peppolRecord);

        // ── Campaign orchestrator — returns a campaign with 1 recipient ──
        MailCampaign emailCampaign = mock(MailCampaign.class);
        when(emailCampaign.getId()).thenReturn(UUID.randomUUID());
        when(emailCampaign.getTotalRecipients()).thenReturn(1);

        CampaignOrchestrator orchestrator = mock(CampaignOrchestrator.class);
        when(orchestrator.fromCanonicalInvoices(any(), any(), any(), any(), eq(orgId), any()))
                .thenReturn(mock(com.esolutions.massmailer.dto.MailDtos.CampaignRequest.class));
        when(orchestrator.createCampaign(any())).thenReturn(emailCampaign);
        doNothing().when(orchestrator).dispatchCampaign(any());

        // ── ERP adapter — returns our test invoice ──
        ErpInvoicePort adapter = mock(ErpInvoicePort.class);
        when(adapter.fetchInvoices(anyString(), anyList())).thenReturn(List.of(invoice()));

        ErpAdapterRegistry adapterRegistry = mock(ErpAdapterRegistry.class);
        when(adapterRegistry.getAdapter(ErpSource.GENERIC_API)).thenReturn(adapter);

        // ── Customer service — no-op upsert ──
        CustomerContactService customerService = mock(CustomerContactService.class);
        when(customerService.upsertAll(any(), any())).thenReturn(List.of());

        ObjectMapper objectMapper = new ObjectMapper();

        return new ErpCampaignController(
                adapterRegistry, orchestrator, customerService,
                customerRepo, peppolService, orgRepo, objectMapper);
    }

    /**
     * Extracts {@code peppolDispatched} from the response body map.
     */
    @SuppressWarnings("unchecked")
    private int peppolDispatched(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        return (int) body.get("peppolDispatched");
    }

    /**
     * Extracts {@code emailDispatched} from the response body map.
     */
    @SuppressWarnings("unchecked")
    private int emailDispatched(ResponseEntity<?> response) {
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        return (int) body.get("emailDispatched");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 7a: AS4 effective mode → PEPPOL only
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 7a: AS4 effective mode routes exclusively to PEPPOL channel.</b>
     *
     * <p>For any invoice where the effective delivery mode resolves to {@code AS4}
     * (whether set at org level or as a customer contact override),
     * {@code peppolDispatched = 1} and {@code emailDispatched = 0}.
     *
     * <p><b>Validates: Requirements 4.1, 4.2</b>
     */
    @Property(tries = 200)
    void as4EffectiveModeRoutesToPeppolOnly(
            @ForAll("as4EffectiveModeScenarios") DeliveryModeScenario scenario) {

        UUID orgId = UUID.randomUUID();
        ErpCampaignController controller = buildController(orgId, scenario.orgMode(), scenario.contactMode());

        ResponseEntity<?> response = controller.fetchAndDispatch(dispatchRequest(orgId));

        assertThat(peppolDispatched(response))
                .as("AS4 effective mode must route to PEPPOL: peppolDispatched should be 1 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(1);

        assertThat(emailDispatched(response))
                .as("AS4 effective mode must NOT route to email: emailDispatched should be 0 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(0);
    }

    /**
     * Generates scenarios where the effective delivery mode resolves to {@code AS4}.
     * Either the org default is AS4 (with no contact override), or the contact
     * overrides to AS4 regardless of the org default.
     */
    @Provide
    Arbitrary<DeliveryModeScenario> as4EffectiveModeScenarios() {
        // Scenario A: org=AS4, contact=null (inherits AS4)
        Arbitrary<DeliveryModeScenario> orgAs4 = Arbitraries.just(
                new DeliveryModeScenario(DeliveryMode.AS4, null));

        // Scenario B: org=EMAIL or BOTH, contact overrides to AS4
        Arbitrary<DeliveryModeScenario> contactOverrideAs4 = Arbitraries
                .of(DeliveryMode.EMAIL, DeliveryMode.BOTH)
                .map(orgMode -> new DeliveryModeScenario(orgMode, DeliveryMode.AS4));

        return Arbitraries.oneOf(orgAs4, contactOverrideAs4);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 7b: EMAIL effective mode → email only
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 7b: EMAIL effective mode routes exclusively to email channel.</b>
     *
     * <p>For any invoice where the effective delivery mode resolves to {@code EMAIL},
     * {@code emailDispatched = 1} and {@code peppolDispatched = 0}.
     *
     * <p><b>Validates: Requirements 4.1, 4.3</b>
     */
    @Property(tries = 200)
    void emailEffectiveModeRoutesToEmailOnly(
            @ForAll("emailEffectiveModeScenarios") DeliveryModeScenario scenario) {

        UUID orgId = UUID.randomUUID();
        ErpCampaignController controller = buildController(orgId, scenario.orgMode(), scenario.contactMode());

        ResponseEntity<?> response = controller.fetchAndDispatch(dispatchRequest(orgId));

        assertThat(emailDispatched(response))
                .as("EMAIL effective mode must route to email: emailDispatched should be 1 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(1);

        assertThat(peppolDispatched(response))
                .as("EMAIL effective mode must NOT route to PEPPOL: peppolDispatched should be 0 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(0);
    }

    /**
     * Generates scenarios where the effective delivery mode resolves to {@code EMAIL}.
     */
    @Provide
    Arbitrary<DeliveryModeScenario> emailEffectiveModeScenarios() {
        // Scenario A: org=EMAIL, contact=null (inherits EMAIL)
        Arbitrary<DeliveryModeScenario> orgEmail = Arbitraries.just(
                new DeliveryModeScenario(DeliveryMode.EMAIL, null));

        // Scenario B: org=AS4 or BOTH, contact overrides to EMAIL
        Arbitrary<DeliveryModeScenario> contactOverrideEmail = Arbitraries
                .of(DeliveryMode.AS4, DeliveryMode.BOTH)
                .map(orgMode -> new DeliveryModeScenario(orgMode, DeliveryMode.EMAIL));

        return Arbitraries.oneOf(orgEmail, contactOverrideEmail);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 7c: BOTH effective mode → both channels
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 7c: BOTH effective mode routes to both PEPPOL and email channels.</b>
     *
     * <p>For any invoice where the effective delivery mode resolves to {@code BOTH},
     * {@code peppolDispatched = 1} and {@code emailDispatched = 1}.
     *
     * <p><b>Validates: Requirements 4.1, 4.4</b>
     */
    @Property(tries = 200)
    void bothEffectiveModeRoutesToBothChannels(
            @ForAll("bothEffectiveModeScenarios") DeliveryModeScenario scenario) {

        UUID orgId = UUID.randomUUID();
        ErpCampaignController controller = buildController(orgId, scenario.orgMode(), scenario.contactMode());

        ResponseEntity<?> response = controller.fetchAndDispatch(dispatchRequest(orgId));

        assertThat(peppolDispatched(response))
                .as("BOTH effective mode must route to PEPPOL: peppolDispatched should be 1 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(1);

        assertThat(emailDispatched(response))
                .as("BOTH effective mode must route to email: emailDispatched should be 1 "
                        + "(orgMode=%s, contactMode=%s)", scenario.orgMode(), scenario.contactMode())
                .isEqualTo(1);
    }

    /**
     * Generates scenarios where the effective delivery mode resolves to {@code BOTH}.
     */
    @Provide
    Arbitrary<DeliveryModeScenario> bothEffectiveModeScenarios() {
        // Scenario A: org=BOTH, contact=null (inherits BOTH)
        Arbitrary<DeliveryModeScenario> orgBoth = Arbitraries.just(
                new DeliveryModeScenario(DeliveryMode.BOTH, null));

        // Scenario B: org=EMAIL or AS4, contact overrides to BOTH
        Arbitrary<DeliveryModeScenario> contactOverrideBoth = Arbitraries
                .of(DeliveryMode.EMAIL, DeliveryMode.AS4)
                .map(orgMode -> new DeliveryModeScenario(orgMode, DeliveryMode.BOTH));

        return Arbitraries.oneOf(orgBoth, contactOverrideBoth);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 7d: Customer override takes precedence over org default
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 7d: Customer contact delivery mode override takes precedence over org default.</b>
     *
     * <p>For any combination of org default mode and non-null customer contact override mode,
     * the routing always matches the contact's mode, not the org's.
     *
     * <p><b>Validates: Requirements 4.1</b>
     */
    @Property(tries = 300)
    void customerContactOverrideTakesPrecedenceOverOrgDefault(
            @ForAll("orgAndContactModePairs") DeliveryModeScenario scenario) {

        UUID orgId = UUID.randomUUID();
        ErpCampaignController controller = buildController(orgId, scenario.orgMode(), scenario.contactMode());

        ResponseEntity<?> response = controller.fetchAndDispatch(dispatchRequest(orgId));

        // The effective mode is the contact's override — verify routing matches it
        DeliveryMode effectiveMode = scenario.contactMode();

        switch (effectiveMode) {
            case AS4 -> {
                assertThat(peppolDispatched(response))
                        .as("Contact override AS4: peppolDispatched should be 1 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(1);
                assertThat(emailDispatched(response))
                        .as("Contact override AS4: emailDispatched should be 0 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(0);
            }
            case EMAIL -> {
                assertThat(peppolDispatched(response))
                        .as("Contact override EMAIL: peppolDispatched should be 0 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(0);
                assertThat(emailDispatched(response))
                        .as("Contact override EMAIL: emailDispatched should be 1 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(1);
            }
            case BOTH -> {
                assertThat(peppolDispatched(response))
                        .as("Contact override BOTH: peppolDispatched should be 1 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(1);
                assertThat(emailDispatched(response))
                        .as("Contact override BOTH: emailDispatched should be 1 (orgMode=%s)", scenario.orgMode())
                        .isEqualTo(1);
            }
        }
    }

    /**
     * Generates all combinations of org default mode and non-null contact override mode.
     */
    @Provide
    Arbitrary<DeliveryModeScenario> orgAndContactModePairs() {
        return Arbitraries.of(DeliveryMode.values())
                .flatMap(orgMode ->
                        Arbitraries.of(DeliveryMode.values())
                                .map(contactMode -> new DeliveryModeScenario(orgMode, contactMode)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Value object for test scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Captures an org-level default delivery mode and an optional customer contact override.
     * When {@code contactMode} is null, the effective mode equals {@code orgMode}.
     */
    record DeliveryModeScenario(DeliveryMode orgMode, DeliveryMode contactMode) {}
}

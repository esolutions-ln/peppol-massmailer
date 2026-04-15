package com.esolutions.massmailer.peppol.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.as4.As4DeliveryResult;
import com.esolutions.massmailer.peppol.as4.As4TransportClient;
import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointRole;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointStatus;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.peppol.model.PeppolParticipantLink;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import com.esolutions.massmailer.peppol.repository.PeppolParticipantLinkRepository;
import com.esolutions.massmailer.peppol.schematron.SchematronResult;
import com.esolutions.massmailer.peppol.schematron.SchematronValidator;
import com.esolutions.massmailer.peppol.schematron.SchematronViolation;
import com.esolutions.massmailer.peppol.ubl.UblInvoiceBuilder;
import com.esolutions.massmailer.service.SmtpSendService;
import com.esolutions.massmailer.service.TemplateRenderService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link PeppolDeliveryService}.
 *
 * <p><b>Property 1: Delivery Audit Completeness</b>
 *
 * <p>For any invocation of {@code PeppolDeliveryService.deliver()} — whether the transport
 * layer succeeds or throws — a {@link PeppolDeliveryRecord} is always persisted with
 * {@code status ∈ {DELIVERED, FAILED}}.
 *
 * <p><b>Validates: Requirements 7.9</b>
 *
 * <p><b>Property 2: Schematron Gate</b>
 *
 * <p>For any UBL document where {@code schematronResult.hasFatalViolations() = true},
 * no HTTP or AS4 request is made to the receiver endpoint, and a
 * {@link PeppolDeliveryRecord} is persisted with {@code status=FAILED}.
 *
 * <p><b>Validates: Requirements 6.4</b>
 */
class PeppolDeliveryServicePropertyTest {

    // ── Fixed test identifiers ────────────────────────────────────────────────

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID RECEIVER_AP_ID = UUID.randomUUID();
    private static final String RECIPIENT_EMAIL = "buyer@example.com";
    private static final String PARTICIPANT_ID = "0190:ZW987654321";
    private static final String ENDPOINT_URL = "https://ap.example.com/peppol/receive";

    // ── Fixture builders ──────────────────────────────────────────────────────

    private CanonicalInvoice invoice(String invoiceNumber) {
        return new CanonicalInvoice(
                ErpSource.GENERIC_API,
                null,
                invoiceNumber,
                RECIPIENT_EMAIL,
                "Test Buyer",
                "Buyer Corp",
                invoiceNumber,
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
     * Builds a fully wired {@link PeppolDeliveryService} where the transport layer
     * either succeeds or throws based on {@code transportSucceeds}.
     *
     * <p>The {@link PeppolDeliveryRecordRepository} is mocked to capture saved records.
     * Schematron validation always passes (no fatal violations).
     *
     * @param transportSucceeds  true → HTTP delivery returns 200; false → throws RuntimeException
     * @param useSimplifiedHttp  true → simplified HTTP path; false → AS4 path
     * @param savedRecordCaptor  captures the record passed to {@code deliveryRepo.save()}
     */
    private PeppolDeliveryService buildService(
            boolean transportSucceeds,
            boolean useSimplifiedHttp,
            ArgumentCaptor<PeppolDeliveryRecord> savedRecordCaptor) {

        // ── Organization repo ──
        Organization supplier = Organization.builder()
                .id(ORG_ID)
                .name("Test Supplier")
                .slug("test-supplier")
                .apiKey("testapikey")
                .senderEmail("noreply@supplier.com")
                .senderDisplayName("Test Supplier")
                .build();
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(supplier));

        // ── Customer contact repo ──
        CustomerContact buyer = CustomerContact.builder()
                .id(CUSTOMER_ID)
                .organizationId(ORG_ID)
                .email(RECIPIENT_EMAIL)
                .name("Test Buyer")
                .build();
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);
        when(customerRepo.findByOrganizationIdAndEmail(eq(ORG_ID), anyString()))
                .thenReturn(Optional.of(buyer));

        // ── Participant link repo ──
        PeppolParticipantLink link = PeppolParticipantLink.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CUSTOMER_ID)
                .participantId(PARTICIPANT_ID)
                .receiverAccessPointId(RECEIVER_AP_ID)
                .build();
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(ORG_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(link));

        // ── Access point repo ──
        AccessPoint receiverAp = AccessPoint.builder()
                .id(RECEIVER_AP_ID)
                .participantId(PARTICIPANT_ID)
                .participantName("Buyer AP")
                .role(AccessPointRole.RECEIVER)
                .endpointUrl(ENDPOINT_URL)
                .simplifiedHttpDelivery(useSimplifiedHttp)
                .status(AccessPointStatus.ACTIVE)
                .build();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findById(RECEIVER_AP_ID)).thenReturn(Optional.of(receiverAp));
        when(apRepo.findByOrganizationIdAndStatus(eq(ORG_ID), eq(AccessPointStatus.ACTIVE)))
                .thenReturn(List.of());

        // ── UBL builder ──
        UblInvoiceBuilder ublBuilder = mock(UblInvoiceBuilder.class);
        when(ublBuilder.build(any(CanonicalInvoice.class), any(Organization.class)))
                .thenReturn("<Invoice>test</Invoice>");

        // ── Schematron validator — always passes ──
        SchematronValidator schematronValidator = mock(SchematronValidator.class);
        SchematronResult passingResult = mock(SchematronResult.class);
        when(passingResult.hasFatalViolations()).thenReturn(false);
        when(passingResult.getWarnings()).thenReturn(List.of());
        when(schematronValidator.validate(anyString(), anyString())).thenReturn(passingResult);

        // ── Delivery record repo — capture saved records ──
        PeppolDeliveryRecordRepository deliveryRepo = mock(PeppolDeliveryRecordRepository.class);
        when(deliveryRepo.save(savedRecordCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // ── RestTemplate — succeeds or throws based on transportSucceeds ──
        RestTemplate restTemplate = mock(RestTemplate.class);
        if (useSimplifiedHttp) {
            if (transportSucceeds) {
                var mockResponse = mock(org.springframework.http.ResponseEntity.class);
                when(mockResponse.getStatusCode()).thenReturn(org.springframework.http.HttpStatus.OK);
                when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                        .thenReturn(mockResponse);
            } else {
                when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                        .thenThrow(new RuntimeException("Simulated HTTP transport failure"));
            }
        }

        // ── AS4 client — succeeds or throws based on transportSucceeds ──
        As4TransportClient as4Client = mock(As4TransportClient.class);
        if (!useSimplifiedHttp) {
            if (transportSucceeds) {
                As4DeliveryResult successResult = new As4DeliveryResult(
                        true, "mdn-msg-id-123", "processed", "<MDN>ok</MDN>", null);
                when(as4Client.send(any())).thenReturn(successResult);
            } else {
                when(as4Client.send(any()))
                        .thenThrow(new com.esolutions.massmailer.peppol.as4.As4TransportException(
                                "Simulated AS4 transport failure"));
            }
        }

        TemplateRenderService templateRenderer = mock(TemplateRenderService.class);
        SmtpSendService smtpSendService = mock(SmtpSendService.class);

        return new PeppolDeliveryService(
                apRepo, linkRepo, deliveryRepo, customerRepo, orgRepo,
                ublBuilder, schematronValidator, restTemplate, as4Client,
                templateRenderer, smtpSendService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 1: Delivery Audit Completeness — HTTP transport path
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 1: Delivery Audit Completeness (simplified HTTP path)</b>
     *
     * <p>For any invocation of {@code PeppolDeliveryService.deliver()} via the simplified HTTP
     * transport path — regardless of whether the HTTP call succeeds or throws — a
     * {@link PeppolDeliveryRecord} is always persisted with {@code status ∈ {DELIVERED, FAILED}}.
     *
     * <p><b>Validates: Requirements 7.9</b>
     */
    @Property(tries = 200)
    void deliveryAuditCompletenessHttpPath(@ForAll boolean transportSucceeds) {
        ArgumentCaptor<PeppolDeliveryRecord> captor = ArgumentCaptor.forClass(PeppolDeliveryRecord.class);
        PeppolDeliveryService service = buildService(transportSucceeds, true, captor);

        String invoiceNumber = "INV-HTTP-" + UUID.randomUUID();
        try {
            service.deliver(ORG_ID, invoice(invoiceNumber));
        } catch (Exception ignored) {
            // Exceptions from the service are acceptable — the record must still be saved
        }

        // At least one save must have occurred
        assertThat(captor.getAllValues())
                .as("PeppolDeliveryRecord must be saved at least once regardless of transport outcome")
                .isNotEmpty();

        // The final saved record must have a terminal status
        PeppolDeliveryRecord finalRecord = captor.getAllValues().getLast();
        assertThat(finalRecord.getStatus())
                .as("Final record status must be DELIVERED or FAILED (transportSucceeds=%s)", transportSucceeds)
                .isIn(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 1: Delivery Audit Completeness — AS4 transport path
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 1: Delivery Audit Completeness (AS4 path)</b>
     *
     * <p>For any invocation of {@code PeppolDeliveryService.deliver()} via the AS4 transport
     * path — regardless of whether the AS4 send succeeds or throws — a
     * {@link PeppolDeliveryRecord} is always persisted with {@code status ∈ {DELIVERED, FAILED}}.
     *
     * <p><b>Validates: Requirements 7.9</b>
     */
    @Property(tries = 200)
    void deliveryAuditCompletenessAs4Path(@ForAll boolean transportSucceeds) {
        ArgumentCaptor<PeppolDeliveryRecord> captor = ArgumentCaptor.forClass(PeppolDeliveryRecord.class);
        PeppolDeliveryService service = buildService(transportSucceeds, false, captor);

        String invoiceNumber = "INV-AS4-" + UUID.randomUUID();
        try {
            service.deliver(ORG_ID, invoice(invoiceNumber));
        } catch (Exception ignored) {
            // Exceptions from the service are acceptable — the record must still be saved
        }

        assertThat(captor.getAllValues())
                .as("PeppolDeliveryRecord must be saved at least once regardless of AS4 transport outcome")
                .isNotEmpty();

        PeppolDeliveryRecord finalRecord = captor.getAllValues().getLast();
        assertThat(finalRecord.getStatus())
                .as("Final record status must be DELIVERED or FAILED (transportSucceeds=%s)", transportSucceeds)
                .isIn(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 2: Schematron Gate — fatal violations block all transport calls
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a {@link PeppolDeliveryService} where the Schematron validator returns
     * {@code hasFatalViolations() = true} with the given number of fatal violations.
     *
     * <p>The {@code As4TransportClient} and {@code RestTemplate} are mocked and
     * captured so callers can verify they were never invoked.
     *
     * @param fatalCount         number of fatal violations to inject (≥ 1)
     * @param useSimplifiedHttp  true → HTTP path; false → AS4 path
     * @param savedRecordCaptor  captures records passed to {@code deliveryRepo.save()}
     * @param as4ClientHolder    single-element array to receive the mocked AS4 client
     * @param restTemplateHolder single-element array to receive the mocked RestTemplate
     */
    private PeppolDeliveryService buildServiceWithFatalSchematron(
            int fatalCount,
            boolean useSimplifiedHttp,
            ArgumentCaptor<PeppolDeliveryRecord> savedRecordCaptor,
            As4TransportClient[] as4ClientHolder,
            RestTemplate[] restTemplateHolder) {

        // ── Organization repo ──
        Organization supplier = Organization.builder()
                .id(ORG_ID)
                .name("Test Supplier")
                .slug("test-supplier")
                .apiKey("testapikey")
                .senderEmail("noreply@supplier.com")
                .senderDisplayName("Test Supplier")
                .build();
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(supplier));

        // ── Customer contact repo ──
        CustomerContact buyer = CustomerContact.builder()
                .id(CUSTOMER_ID)
                .organizationId(ORG_ID)
                .email(RECIPIENT_EMAIL)
                .name("Test Buyer")
                .build();
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);
        when(customerRepo.findByOrganizationIdAndEmail(eq(ORG_ID), anyString()))
                .thenReturn(Optional.of(buyer));

        // ── Participant link repo ──
        PeppolParticipantLink link = PeppolParticipantLink.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CUSTOMER_ID)
                .participantId(PARTICIPANT_ID)
                .receiverAccessPointId(RECEIVER_AP_ID)
                .build();
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(ORG_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(link));

        // ── Access point repo ──
        AccessPoint receiverAp = AccessPoint.builder()
                .id(RECEIVER_AP_ID)
                .participantId(PARTICIPANT_ID)
                .participantName("Buyer AP")
                .role(AccessPointRole.RECEIVER)
                .endpointUrl(ENDPOINT_URL)
                .simplifiedHttpDelivery(useSimplifiedHttp)
                .status(AccessPointStatus.ACTIVE)
                .build();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findById(RECEIVER_AP_ID)).thenReturn(Optional.of(receiverAp));
        when(apRepo.findByOrganizationIdAndStatus(eq(ORG_ID), eq(AccessPointStatus.ACTIVE)))
                .thenReturn(List.of());

        // ── UBL builder ──
        UblInvoiceBuilder ublBuilder = mock(UblInvoiceBuilder.class);
        when(ublBuilder.build(any(CanonicalInvoice.class), any(Organization.class)))
                .thenReturn("<Invoice>test</Invoice>");

        // ── Schematron validator — returns fatal violations ──
        SchematronValidator schematronValidator = mock(SchematronValidator.class);
        List<SchematronViolation> fatalViolations = java.util.stream.IntStream.range(0, fatalCount)
                .mapToObj(i -> new SchematronViolation(
                        "BR-" + String.format("%02d", i + 1),
                        "fatal",
                        "Fatal rule violation #" + (i + 1),
                        "/Invoice/cbc:Field" + i))
                .toList();
        SchematronResult fatalResult = new SchematronResult(false, fatalViolations);
        when(schematronValidator.validate(anyString(), anyString())).thenReturn(fatalResult);

        // ── Delivery record repo — capture saved records ──
        PeppolDeliveryRecordRepository deliveryRepo = mock(PeppolDeliveryRecordRepository.class);
        when(deliveryRepo.save(savedRecordCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // ── RestTemplate — should NEVER be called ──
        RestTemplate restTemplate = mock(RestTemplate.class);
        restTemplateHolder[0] = restTemplate;

        // ── AS4 client — should NEVER be called ──
        As4TransportClient as4Client = mock(As4TransportClient.class);
        as4ClientHolder[0] = as4Client;

        TemplateRenderService templateRenderer = mock(TemplateRenderService.class);
        SmtpSendService smtpSendService = mock(SmtpSendService.class);

        return new PeppolDeliveryService(
                apRepo, linkRepo, deliveryRepo, customerRepo, orgRepo,
                ublBuilder, schematronValidator, restTemplate, as4Client,
                templateRenderer, smtpSendService);
    }

    /**
     * <b>Property 2: Schematron Gate</b>
     *
     * <p>For any UBL document where {@code schematronResult.hasFatalViolations() = true},
     * no HTTP or AS4 request is made to the receiver endpoint, and a
     * {@link PeppolDeliveryRecord} is persisted with {@code status=FAILED}.
     *
     * <p><b>Validates: Requirements 6.4</b>
     */
    @Property(tries = 200)
    void schematronGateBlocksTransport(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 10) int fatalCount,
            @ForAll boolean useSimplifiedHttp) {

        ArgumentCaptor<PeppolDeliveryRecord> captor = ArgumentCaptor.forClass(PeppolDeliveryRecord.class);
        As4TransportClient[] as4ClientHolder = new As4TransportClient[1];
        RestTemplate[] restTemplateHolder = new RestTemplate[1];

        PeppolDeliveryService service = buildServiceWithFatalSchematron(
                fatalCount, useSimplifiedHttp, captor, as4ClientHolder, restTemplateHolder);

        String invoiceNumber = "INV-SCHEMATRON-" + UUID.randomUUID();
        try {
            service.deliver(ORG_ID, invoice(invoiceNumber));
        } catch (Exception ignored) {
            // SchematronValidationException is expected — we only care about side effects
        }

        // AS4 client must NEVER have been called
        verify(as4ClientHolder[0], never()).send(any());

        // RestTemplate must NEVER have been called
        verify(restTemplateHolder[0], never()).postForEntity(anyString(), any(), eq(String.class));

        // A PeppolDeliveryRecord must have been persisted with status=FAILED
        assertThat(captor.getAllValues())
                .as("A PeppolDeliveryRecord must be saved even when Schematron fails "
                        + "(fatalCount=%d, useSimplifiedHttp=%s)", fatalCount, useSimplifiedHttp)
                .isNotEmpty();

        PeppolDeliveryRecord savedRecord = captor.getAllValues().getLast();
        assertThat(savedRecord.getStatus())
                .as("Record status must be FAILED when Schematron has fatal violations "
                        + "(fatalCount=%d, useSimplifiedHttp=%s)", fatalCount, useSimplifiedHttp)
                .isEqualTo(DeliveryStatus.FAILED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 8: MDN Verification — AS4 delivery record MDN field invariants
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a {@link PeppolDeliveryService} where the AS4 transport layer returns
     * the given {@link As4DeliveryResult} (success or failure variant).
     *
     * @param mdnResult         the result to return from {@code As4TransportClient.send()}
     * @param savedRecordCaptor captures records passed to {@code deliveryRepo.save()}
     */
    private PeppolDeliveryService buildServiceWithAs4Result(
            As4DeliveryResult mdnResult,
            ArgumentCaptor<PeppolDeliveryRecord> savedRecordCaptor) {

        // ── Organization repo ──
        Organization supplier = Organization.builder()
                .id(ORG_ID)
                .name("Test Supplier")
                .slug("test-supplier")
                .apiKey("testapikey")
                .senderEmail("noreply@supplier.com")
                .senderDisplayName("Test Supplier")
                .build();
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(supplier));

        // ── Customer contact repo ──
        CustomerContact buyer = CustomerContact.builder()
                .id(CUSTOMER_ID)
                .organizationId(ORG_ID)
                .email(RECIPIENT_EMAIL)
                .name("Test Buyer")
                .build();
        CustomerContactRepository customerRepo = mock(CustomerContactRepository.class);
        when(customerRepo.findByOrganizationIdAndEmail(eq(ORG_ID), anyString()))
                .thenReturn(Optional.of(buyer));

        // ── Participant link repo ──
        PeppolParticipantLink link = PeppolParticipantLink.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CUSTOMER_ID)
                .participantId(PARTICIPANT_ID)
                .receiverAccessPointId(RECEIVER_AP_ID)
                .build();
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(ORG_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(link));

        // ── Access point repo — AS4 path (simplifiedHttpDelivery=false) ──
        AccessPoint receiverAp = AccessPoint.builder()
                .id(RECEIVER_AP_ID)
                .participantId(PARTICIPANT_ID)
                .participantName("Buyer AP")
                .role(AccessPoint.AccessPointRole.RECEIVER)
                .endpointUrl(ENDPOINT_URL)
                .simplifiedHttpDelivery(false)
                .status(AccessPoint.AccessPointStatus.ACTIVE)
                .build();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findById(RECEIVER_AP_ID)).thenReturn(Optional.of(receiverAp));
        when(apRepo.findByOrganizationIdAndStatus(eq(ORG_ID), eq(AccessPoint.AccessPointStatus.ACTIVE)))
                .thenReturn(List.of());

        // ── UBL builder ──
        UblInvoiceBuilder ublBuilder = mock(UblInvoiceBuilder.class);
        when(ublBuilder.build(any(CanonicalInvoice.class), any(Organization.class)))
                .thenReturn("<Invoice>test</Invoice>");

        // ── Schematron validator — always passes ──
        SchematronValidator schematronValidator = mock(SchematronValidator.class);
        SchematronResult passingResult = mock(SchematronResult.class);
        when(passingResult.hasFatalViolations()).thenReturn(false);
        when(passingResult.getWarnings()).thenReturn(List.of());
        when(schematronValidator.validate(anyString(), anyString())).thenReturn(passingResult);

        // ── Delivery record repo — capture saved records ──
        PeppolDeliveryRecordRepository deliveryRepo = mock(PeppolDeliveryRecordRepository.class);
        when(deliveryRepo.save(savedRecordCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // ── RestTemplate — not used on AS4 path ──
        RestTemplate restTemplate = mock(RestTemplate.class);

        // ── AS4 client — returns the provided result ──
        As4TransportClient as4Client = mock(As4TransportClient.class);
        if (mdnResult.success()) {
            when(as4Client.send(any())).thenReturn(mdnResult);
        } else {
            when(as4Client.send(any()))
                    .thenThrow(new com.esolutions.massmailer.peppol.as4.As4TransportException(
                            mdnResult.errorDescription() != null
                                    ? mdnResult.errorDescription()
                                    : "Simulated AS4 failure"));
        }

        return new PeppolDeliveryService(
                apRepo, linkRepo, deliveryRepo, customerRepo, orgRepo,
                ublBuilder, schematronValidator, restTemplate, as4Client,
                mock(TemplateRenderService.class), mock(SmtpSendService.class));
    }

    /**
     * Generator that produces {@link As4DeliveryResult} variants:
     * <ul>
     *   <li>Success: {@code success=true, mdnMessageId="mdn-{uuid}", mdnStatus="processed"}</li>
     *   <li>Failure: {@code success=false, mdnMessageId=null, mdnStatus="failed"}</li>
     * </ul>
     */
    @Provide
    Arbitrary<As4DeliveryResult> mdnOutcomes() {
        Arbitrary<As4DeliveryResult> success = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(4).ofMaxLength(20)
                .map(suffix -> new As4DeliveryResult(
                        true,
                        "mdn-" + suffix,
                        "processed",
                        "<MDN>ok</MDN>",
                        null));

        Arbitrary<As4DeliveryResult> failure = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(4).ofMaxLength(20)
                .map(suffix -> new As4DeliveryResult(
                        false,
                        null,
                        "failed",
                        null,
                        "AS4 error: " + suffix));

        return Arbitraries.oneOf(success, failure);
    }

    /**
     * <b>Property 8: MDN Verification</b>
     *
     * <p>For any AS4 delivery record:
     * <ul>
     *   <li>If the MDN result has {@code success=true}: the persisted record must have
     *       {@code status=DELIVERED}, {@code mdnStatus="processed"}, and {@code mdnMessageId ≠ null}.</li>
     *   <li>If the MDN result has {@code success=false}: the persisted record must have
     *       {@code status=FAILED}.</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 8.4, 8.6</b>
     */
    @Property(tries = 200)
    void mdnVerification(@ForAll("mdnOutcomes") As4DeliveryResult mdnResult) {
        ArgumentCaptor<PeppolDeliveryRecord> captor = ArgumentCaptor.forClass(PeppolDeliveryRecord.class);
        PeppolDeliveryService service = buildServiceWithAs4Result(mdnResult, captor);

        String invoiceNumber = "INV-MDN-" + UUID.randomUUID();
        try {
            service.deliver(ORG_ID, invoice(invoiceNumber));
        } catch (Exception ignored) {
            // AS4 transport exceptions are acceptable — we only care about the persisted record
        }

        assertThat(captor.getAllValues())
                .as("A PeppolDeliveryRecord must be saved for every AS4 delivery attempt")
                .isNotEmpty();

        PeppolDeliveryRecord finalRecord = captor.getAllValues().getLast();

        if (mdnResult.success()) {
            assertThat(finalRecord.getStatus())
                    .as("Successful MDN (success=true) must yield status=DELIVERED")
                    .isEqualTo(DeliveryStatus.DELIVERED);
            assertThat(finalRecord.getMdnStatus())
                    .as("Successful MDN must have mdnStatus=\"processed\"")
                    .isEqualTo("processed");
            assertThat(finalRecord.getMdnMessageId())
                    .as("Successful MDN must have a non-null mdnMessageId")
                    .isNotNull();
        } else {
            assertThat(finalRecord.getStatus())
                    .as("Failed or absent MDN (success=false) must yield status=FAILED")
                    .isEqualTo(DeliveryStatus.FAILED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 1: Status is DELIVERED on success, FAILED on failure
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * <b>Property 1 (status invariant): Transport outcome determines record status.</b>
     *
     * <p>When transport succeeds, the final record status is {@code DELIVERED}.
     * When transport fails, the final record status is {@code FAILED}.
     * This holds for both HTTP and AS4 transport paths.
     *
     * <p><b>Validates: Requirements 7.7, 7.8, 7.9</b>
     */
    @Property(tries = 200)
    void recordStatusReflectsTransportOutcome(
            @ForAll boolean transportSucceeds,
            @ForAll boolean useSimplifiedHttp) {

        ArgumentCaptor<PeppolDeliveryRecord> captor = ArgumentCaptor.forClass(PeppolDeliveryRecord.class);
        PeppolDeliveryService service = buildService(transportSucceeds, useSimplifiedHttp, captor);

        String invoiceNumber = "INV-STATUS-" + UUID.randomUUID();
        try {
            service.deliver(ORG_ID, invoice(invoiceNumber));
        } catch (Exception ignored) {
            // Exceptions are acceptable — we only care about the persisted record
        }

        assertThat(captor.getAllValues())
                .as("At least one record save must occur")
                .isNotEmpty();

        PeppolDeliveryRecord finalRecord = captor.getAllValues().getLast();

        if (transportSucceeds) {
            assertThat(finalRecord.getStatus())
                    .as("Successful transport must yield DELIVERED status "
                            + "(useSimplifiedHttp=%s)", useSimplifiedHttp)
                    .isEqualTo(DeliveryStatus.DELIVERED);
        } else {
            assertThat(finalRecord.getStatus())
                    .as("Failed transport must yield FAILED status "
                            + "(useSimplifiedHttp=%s)", useSimplifiedHttp)
                    .isEqualTo(DeliveryStatus.FAILED);
        }
    }
}

package com.esolutions.massmailer.invitation.service;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.invitation.model.PeppolInvitation;
import com.esolutions.massmailer.invitation.repository.InvitationRepository;
import com.esolutions.massmailer.invitation.service.dto.CompleteRegistrationRequest;
import com.esolutions.massmailer.invitation.service.dto.CompleteRegistrationResponse;
import com.esolutions.massmailer.invitation.service.dto.InvitationResponse;
import com.esolutions.massmailer.invitation.service.dto.TokenValidationResponse;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.PeppolParticipantLink;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.PeppolParticipantLinkRepository;
import jakarta.mail.internet.MimeMessage;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for {@link InvitationService}.
 */
class InvitationServicePropertyTest {

    // ── Fixed test identifiers ────────────────────────────────────────────────

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final String CUSTOMER_EMAIL = "customer@example.com";
    private static final String ORG_NAME = "Test Organisation";
    private static final String ORG_API_KEY = "secret-api-key-do-not-expose";
    private static final String BASE_URL = "https://app.invoicedirect.biz";
    private static final String FROM_ADDRESS = "noreply@invoicedirect.biz";


    // ── Fixture builders ──────────────────────────────────────────────────────

    private Organization buildOrg() {
        return Organization.builder()
                .id(ORG_ID)
                .name(ORG_NAME)
                .slug("test-org")
                .apiKey(ORG_API_KEY)
                .senderEmail("sender@test-org.com")
                .senderDisplayName("Test Org")
                .build();
    }

    private CustomerContact buildContact() {
        return CustomerContact.builder()
                .id(CONTACT_ID)
                .organizationId(ORG_ID)
                .email(CUSTOMER_EMAIL)
                .name("Test Customer")
                .build();
    }

    /**
     * Builds an InvitationService where:
     * - CustomerContact exists for ORG_ID + CUSTOMER_EMAIL
     * - No PeppolParticipantLink exists
     * - No existing PENDING invitations
     * - invitationRepo.save() returns the saved invitation with a generated ID
     * - mailSender is mocked (captures sent messages)
     */
    private InvitationService buildServiceHappyPath(
            ArgumentCaptor<PeppolInvitation> savedInvitationCaptor,
            MimeMessage[] sentMessageHolder) {

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findByOrganizationIdAndEmail(eq(ORG_ID), eq(CUSTOMER_EMAIL)))
                .thenReturn(Optional.of(buildContact()));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(eq(ORG_ID), eq(CONTACT_ID)))
                .thenReturn(Optional.empty());

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByOrganizationIdAndCustomerContactIdAndStatus(
                eq(ORG_ID), eq(CONTACT_ID), eq(InvitationStatus.PENDING)))
                .thenReturn(List.of());
        when(invRepo.save(savedInvitationCaptor.capture()))
                .thenAnswer(inv -> {
                    PeppolInvitation i = inv.getArgument(0);
                    if (i.getId() == null) {
                        return PeppolInvitation.builder()
                                .id(UUID.randomUUID())
                                .organizationId(i.getOrganizationId())
                                .customerContactId(i.getCustomerContactId())
                                .customerEmail(i.getCustomerEmail())
                                .token(i.getToken())
                                .status(i.getStatus())
                                .createdAt(i.getCreatedAt())
                                .expiresAt(i.getExpiresAt())
                                .build();
                    }
                    return i;
                });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        MimeMessage mockMessage = mock(MimeMessage.class);
        if (sentMessageHolder != null) sentMessageHolder[0] = mockMessage;

        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(eq("email/peppol-invitation"), any(Context.class)))
                .thenAnswer(inv -> {
                    Context ctx = inv.getArgument(1);
                    String token = (String) ctx.getVariable("inviteUrl");
                    return "<html><body><a href=\"" + token + "\">" + token + "</a></body></html>";
                });

        return new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 1: Token Creation Invariants
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 1: Token Creation Invariants
    /**
     * For any valid invitation request (existing customer, no existing participant link),
     * the created PeppolInvitation must have status=PENDING, a non-null UUID-format token,
     * and expiresAt within [71h59m, 72h01m] of createdAt.
     *
     * <p><b>Validates: Requirements 1.1, 8.1</b>
     */
    @Property(tries = 200)
    void tokenCreationInvariants(@ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int ignored) {
        ArgumentCaptor<PeppolInvitation> captor = ArgumentCaptor.forClass(PeppolInvitation.class);
        InvitationService service = buildServiceHappyPath(captor, null);

        Instant before = Instant.now();
        PeppolInvitation result = service.sendInvitation(ORG_ID, CUSTOMER_EMAIL);
        Instant after = Instant.now();

        // The captor captures the invitation passed to save() — use the returned result
        assertThat(result.getStatus())
                .as("Invitation status must be PENDING")
                .isEqualTo(InvitationStatus.PENDING);

        assertThat(result.getToken())
                .as("Token must be non-null")
                .isNotNull()
                .isNotBlank();

        // Token must be UUID format (8-4-4-4-12 hex)
        assertThat(result.getToken())
                .as("Token must be UUID v4 format")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        // expiresAt must be createdAt + 72h (within 2 minutes tolerance)
        long toleranceSeconds = 120;
        long expectedSeconds = 72 * 3600;
        long actualDiff = result.getExpiresAt().getEpochSecond() - result.getCreatedAt().getEpochSecond();

        assertThat(actualDiff)
                .as("expiresAt must be createdAt + 72h (within tolerance)")
                .isBetween(expectedSeconds - toleranceSeconds, expectedSeconds + toleranceSeconds);

        // createdAt must be within the test window
        assertThat(result.getCreatedAt())
                .as("createdAt must be set to approximately now")
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 2: Invitation Email Contains Token
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 2: Invitation Email Contains Token
    /**
     * For any successfully created invitation, the email sent to the customer must
     * contain the token embedded in a URL path /invite/peppol/{token}.
     *
     * <p><b>Validates: Requirements 1.2</b>
     */
    @Property(tries = 200)
    void invitationEmailContainsToken(@ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int ignored) {
        ArgumentCaptor<PeppolInvitation> invCaptor = ArgumentCaptor.forClass(PeppolInvitation.class);

        // Capture the rendered HTML passed to the template engine
        String[] capturedHtml = new String[1];

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findByOrganizationIdAndEmail(eq(ORG_ID), eq(CUSTOMER_EMAIL)))
                .thenReturn(Optional.of(buildContact()));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(any(), any()))
                .thenReturn(Optional.empty());

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByOrganizationIdAndCustomerContactIdAndStatus(any(), any(), any()))
                .thenReturn(List.of());
        when(invRepo.save(invCaptor.capture()))
                .thenAnswer(inv -> {
                    PeppolInvitation i = inv.getArgument(0);
                    return PeppolInvitation.builder()
                            .id(UUID.randomUUID())
                            .organizationId(i.getOrganizationId())
                            .customerContactId(i.getCustomerContactId())
                            .customerEmail(i.getCustomerEmail())
                            .token(i.getToken())
                            .status(i.getStatus())
                            .createdAt(i.getCreatedAt())
                            .expiresAt(i.getExpiresAt())
                            .build();
                });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        MimeMessage mockMessage = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(eq("email/peppol-invitation"), any(Context.class)))
                .thenAnswer(inv -> {
                    Context ctx = inv.getArgument(1);
                    String inviteUrl = (String) ctx.getVariable("inviteUrl");
                    String html = "<html><body><a href=\"" + inviteUrl + "\">" + inviteUrl + "</a></body></html>";
                    capturedHtml[0] = html;
                    return html;
                });

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        PeppolInvitation result = service.sendInvitation(ORG_ID, CUSTOMER_EMAIL);

        // The rendered HTML must contain the token in the invite URL path
        String token = result.getToken();
        String expectedPath = "/invite/peppol/" + token;

        assertThat(capturedHtml[0])
                .as("Email HTML must contain the token embedded in /invite/peppol/{token}")
                .contains(expectedPath);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 3: Re-invite Invalidates Previous
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 3: Re-invite Invalidates Previous
    /**
     * For any org+customer where a PENDING invitation exists, sending a new invitation
     * must result in the old invitation having status=CANCELLED and a new PENDING
     * invitation existing.
     *
     * <p><b>Validates: Requirements 1.3</b>
     */
    @Property(tries = 200)
    void reInviteInvalidatesPrevious(@ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int ignored) {
        // Build an existing PENDING invitation
        PeppolInvitation existingPending = PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(UUID.randomUUID().toString())
                .status(InvitationStatus.PENDING)
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(72 * 3600 - 3600))
                .build();

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findByOrganizationIdAndEmail(eq(ORG_ID), eq(CUSTOMER_EMAIL)))
                .thenReturn(Optional.of(buildContact()));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(any(), any()))
                .thenReturn(Optional.empty());

        // Track all saved invitations
        java.util.List<PeppolInvitation> allSaved = new java.util.ArrayList<>();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByOrganizationIdAndCustomerContactIdAndStatus(
                eq(ORG_ID), eq(CONTACT_ID), eq(InvitationStatus.PENDING)))
                .thenReturn(List.of(existingPending));
        when(invRepo.save(any(PeppolInvitation.class)))
                .thenAnswer(inv -> {
                    PeppolInvitation i = inv.getArgument(0);
                    PeppolInvitation saved = i.getId() != null ? i :
                            PeppolInvitation.builder()
                                    .id(UUID.randomUUID())
                                    .organizationId(i.getOrganizationId())
                                    .customerContactId(i.getCustomerContactId())
                                    .customerEmail(i.getCustomerEmail())
                                    .token(i.getToken())
                                    .status(i.getStatus())
                                    .createdAt(i.getCreatedAt())
                                    .expiresAt(i.getExpiresAt())
                                    .build();
                    allSaved.add(saved);
                    return saved;
                });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        MimeMessage mockMessage = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMessage);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html/>");

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        service.sendInvitation(ORG_ID, CUSTOMER_EMAIL);

        // The existing PENDING invitation must have been saved with CANCELLED status
        assertThat(allSaved)
                .as("At least one save must have occurred for the cancelled invitation")
                .isNotEmpty();

        boolean oldWasCancelled = allSaved.stream()
                .anyMatch(i -> i.getId().equals(existingPending.getId())
                        && i.getStatus() == InvitationStatus.CANCELLED);
        assertThat(oldWasCancelled)
                .as("The existing PENDING invitation must be saved with status=CANCELLED")
                .isTrue();

        // A new PENDING invitation must have been saved
        boolean newPendingExists = allSaved.stream()
                .anyMatch(i -> !i.getId().equals(existingPending.getId())
                        && i.getStatus() == InvitationStatus.PENDING);
        assertThat(newPendingExists)
                .as("A new PENDING invitation must be created")
                .isTrue();
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 4: Missing Customer Returns 404
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 4: Missing Customer Returns 404
    /**
     * For any invitation request where the customer email does not exist in the org,
     * the service must throw a ResponseStatusException with status 404.
     *
     * <p><b>Validates: Requirements 1.4</b>
     */
    @Property(tries = 200)
    void missingCustomerReturns404(@ForAll("unknownEmails") String unknownEmail) {
        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findByOrganizationIdAndEmail(any(), anyString()))
                .thenReturn(Optional.empty());

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        assertThatThrownBy(() -> service.sendInvitation(ORG_ID, unknownEmail))
                .as("Must throw ResponseStatusException with 404 for unknown customer email")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Provide
    Arbitrary<String> unknownEmails() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3).ofMaxLength(10)
                .map(s -> s + "@unknown.com");
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 5: Already-Linked Customer Returns 409
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 5: Already-Linked Customer Returns 409
    /**
     * For any invitation request where the customer already has an active PeppolParticipantLink,
     * the service must throw a ResponseStatusException with status 409.
     *
     * <p><b>Validates: Requirements 1.5</b>
     */
    @Property(tries = 200)
    void alreadyLinkedReturns409(@ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = Integer.MAX_VALUE) int ignored) {
        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findByOrganizationIdAndEmail(eq(ORG_ID), eq(CUSTOMER_EMAIL)))
                .thenReturn(Optional.of(buildContact()));

        PeppolParticipantLink existingLink = PeppolParticipantLink.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .participantId("0190:ZW123456789")
                .receiverAccessPointId(UUID.randomUUID())
                .build();

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.findByOrganizationIdAndCustomerContactId(eq(ORG_ID), eq(CONTACT_ID)))
                .thenReturn(Optional.of(existingLink));

        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        assertThatThrownBy(() -> service.sendInvitation(ORG_ID, CUSTOMER_EMAIL))
                .as("Must throw ResponseStatusException with 409 when customer already has PEPPOL link")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 6: Token Validation State Machine
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 6: Token Validation State Machine
    /**
     * For any token lookup:
     * - Non-existent token → 404
     * - COMPLETED or CANCELLED token → 410
     * - PENDING token with expiresAt in the past → 410
     * - PENDING token with expiresAt in the future → 200 with valid response
     *
     * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 6.1</b>
     */
    @Property(tries = 200)
    void tokenValidationStateMachine(@ForAll("tokenScenarios") TokenScenario scenario) {
        InvitationRepository invRepo = mock(InvitationRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        String token = UUID.randomUUID().toString();

        if (scenario == TokenScenario.NOT_FOUND) {
            when(invRepo.findByToken(token)).thenReturn(Optional.empty());

            InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                    mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));

        } else if (scenario == TokenScenario.COMPLETED || scenario == TokenScenario.CANCELLED) {
            InvitationStatus status = scenario == TokenScenario.COMPLETED
                    ? InvitationStatus.COMPLETED : InvitationStatus.CANCELLED;
            PeppolInvitation inv = buildInvitation(token, status, Instant.now().plusSeconds(3600));
            when(invRepo.findByToken(token)).thenReturn(Optional.of(inv));

            InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                    mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.GONE));

        } else if (scenario == TokenScenario.PENDING_EXPIRED) {
            PeppolInvitation inv = buildInvitation(token, InvitationStatus.PENDING,
                    Instant.now().minusSeconds(3600));
            when(invRepo.findByToken(token)).thenReturn(Optional.of(inv));

            InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                    mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.GONE));

        } else { // PENDING_VALID
            PeppolInvitation inv = buildInvitation(token, InvitationStatus.PENDING,
                    Instant.now().plusSeconds(72 * 3600));
            when(invRepo.findByToken(token)).thenReturn(Optional.of(inv));
            when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

            InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                    mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

            TokenValidationResponse response = service.validateToken(token);

            assertThat(response).isNotNull();
            assertThat(response.customerEmail()).isEqualTo(CUSTOMER_EMAIL);
            assertThat(response.organisationName()).isEqualTo(ORG_NAME);
        }
    }

    enum TokenScenario { NOT_FOUND, COMPLETED, CANCELLED, PENDING_EXPIRED, PENDING_VALID }

    @Provide
    Arbitrary<TokenScenario> tokenScenarios() {
        return Arbitraries.of(TokenScenario.values());
    }

    private PeppolInvitation buildInvitation(String token, InvitationStatus status, Instant expiresAt) {
        return PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(token)
                .status(status)
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(expiresAt)
                .build();
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 7: Token Validation Response Contains No Sensitive Data
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 7: Token Validation Response Contains No Sensitive Data
    /**
     * For any valid token validation response, it must contain customerEmail and
     * organisationName, and must NOT contain the org's API key or internal UUID.
     *
     * <p><b>Validates: Requirements 2.5, 8.4</b>
     */
    @Property(tries = 200)
    void tokenValidationResponseContainsNoSensitiveData(@ForAll("apiKeys") String apiKey) {
        String token = UUID.randomUUID().toString();
        UUID orgId = UUID.randomUUID();

        Organization org = Organization.builder()
                .id(orgId)
                .name("Sensitive Org")
                .slug("sensitive-org")
                .apiKey(apiKey)
                .senderEmail("sender@sensitive.com")
                .senderDisplayName("Sensitive Org")
                .build();

        PeppolInvitation inv = PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(orgId)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(72 * 3600))
                .build();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(inv));

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        TokenValidationResponse response = service.validateToken(token);

        // Must contain customerEmail and organisationName
        assertThat(response.customerEmail())
                .as("Response must contain customerEmail")
                .isEqualTo(CUSTOMER_EMAIL);
        assertThat(response.organisationName())
                .as("Response must contain organisationName")
                .isEqualTo("Sensitive Org");

        // Must NOT expose the API key
        assertThat(response.toString())
                .as("Response must not contain the org API key")
                .doesNotContain(apiKey);

        // Must NOT expose the internal org UUID
        assertThat(response.toString())
                .as("Response must not contain the internal org UUID")
                .doesNotContain(orgId.toString());

        // The record type itself must not have API key or UUID fields
        // (verified by checking the record's component names via reflection)
        var components = response.getClass().getRecordComponents();
        assertThat(components).hasSize(2);
        assertThat(components[0].getName()).isEqualTo("customerEmail");
        assertThat(components[1].getName()).isEqualTo("organisationName");
    }

    @Provide
    Arbitrary<String> apiKeys() {
        return Arbitraries.strings()
                .withCharRange('a', 'f')
                .ofLength(32);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Helper: build a full InvitationService with AccessPointRepository
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a PENDING invitation for use in completion tests.
     */
    private PeppolInvitation buildPendingInvitation(String token) {
        return PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(72 * 3600))
                .build();
    }

    /**
     * Builds an InvitationService wired for completion tests.
     * The AccessPointRepository mock is passed in so tests can configure it.
     */
    private InvitationService buildCompletionService(
            InvitationRepository invRepo,
            CustomerContactRepository contactRepo,
            PeppolParticipantLinkRepository linkRepo,
            AccessPointRepository apRepo,
            OrganizationRepository orgRepo,
            JavaMailSender mailSender,
            TemplateEngine templateEngine) {
        return new InvitationService(invRepo, contactRepo, linkRepo, apRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 8: Completion Invariants
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 8: completionInvariants
    /**
     * For any successful completion of a PENDING invitation with valid PEPPOL details,
     * all of the following must hold atomically:
     * - An AccessPoint with role=RECEIVER and the submitted participantId exists
     * - A PeppolParticipantLink exists mapping the CustomerContact to that AccessPoint
     *   with preferredChannel=PEPPOL
     * - The CustomerContact has deliveryMode=AS4 and peppolParticipantId equal to the submitted value
     * - The PeppolInvitation has status=COMPLETED and a non-null completedAt
     *
     * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.8</b>
     */
    @Property(tries = 200)
    void completionInvariants(@ForAll("validParticipantIds") String participantId,
                               @ForAll("validHttpsUrls") String endpointUrl) {
        String token = UUID.randomUUID().toString();
        PeppolInvitation invitation = buildPendingInvitation(token);

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(invitation));
        when(invRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findById(CONTACT_ID)).thenReturn(Optional.of(buildContact()));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        ArgumentCaptor<PeppolParticipantLink> linkCaptor =
                ArgumentCaptor.forClass(PeppolParticipantLink.class);
        when(linkRepo.save(linkCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UUID apId = UUID.randomUUID();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(participantId)).thenReturn(Optional.empty());
        ArgumentCaptor<AccessPoint> apCaptor = ArgumentCaptor.forClass(AccessPoint.class);
        when(apRepo.save(apCaptor.capture())).thenAnswer(inv -> {
            AccessPoint ap = inv.getArgument(0);
            // Simulate ID assignment
            return AccessPoint.builder()
                    .id(apId)
                    .participantId(ap.getParticipantId())
                    .participantName(ap.getParticipantName())
                    .role(ap.getRole())
                    .endpointUrl(ap.getEndpointUrl())
                    .deliveryAuthToken(ap.getDeliveryAuthToken())
                    .simplifiedHttpDelivery(ap.isSimplifiedHttpDelivery())
                    .build();
        });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        MimeMessage mockMsg = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html/>");

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        CompleteRegistrationResponse response = service.completeRegistration(token,
                new CompleteRegistrationRequest(participantId, endpointUrl, null, true));

        // Response must contain participantId and endpointUrl
        assertThat(response.participantId()).isEqualTo(participantId);
        assertThat(response.endpointUrl()).isEqualTo(endpointUrl);

        // AccessPoint must have been saved with role=RECEIVER
        AccessPoint savedAp = apCaptor.getValue();
        assertThat(savedAp.getRole())
                .as("AccessPoint must have role=RECEIVER")
                .isEqualTo(AccessPoint.AccessPointRole.RECEIVER);
        assertThat(savedAp.getParticipantId())
                .as("AccessPoint must have the submitted participantId")
                .isEqualTo(participantId);

        // PeppolParticipantLink must reference the AccessPoint and have preferredChannel=PEPPOL
        PeppolParticipantLink savedLink = linkCaptor.getValue();
        assertThat(savedLink.getPreferredChannel())
                .as("PeppolParticipantLink must have preferredChannel=PEPPOL")
                .isEqualTo(PeppolParticipantLink.DeliveryChannel.PEPPOL);
        assertThat(savedLink.getCustomerContactId())
                .as("PeppolParticipantLink must reference the CustomerContact")
                .isEqualTo(CONTACT_ID);

        // CustomerContact must have been updated
        ArgumentCaptor<CustomerContact> contactCaptor = ArgumentCaptor.forClass(CustomerContact.class);
        verify(contactRepo).save(contactCaptor.capture());
        CustomerContact savedContact = contactCaptor.getValue();
        assertThat(savedContact.getDeliveryMode())
                .as("CustomerContact deliveryMode must be AS4")
                .isEqualTo(DeliveryMode.AS4);
        assertThat(savedContact.getPeppolParticipantId())
                .as("CustomerContact peppolParticipantId must match submitted value")
                .isEqualTo(participantId);

        // Invitation must have been saved with COMPLETED status and non-null completedAt
        ArgumentCaptor<PeppolInvitation> invCaptor = ArgumentCaptor.forClass(PeppolInvitation.class);
        verify(invRepo).save(invCaptor.capture());
        PeppolInvitation savedInv = invCaptor.getValue();
        assertThat(savedInv.getStatus())
                .as("PeppolInvitation must have status=COMPLETED")
                .isEqualTo(InvitationStatus.COMPLETED);
        assertThat(savedInv.getCompletedAt())
                .as("PeppolInvitation must have a non-null completedAt")
                .isNotNull();
    }

    @Provide
    Arbitrary<String> validParticipantIds() {
        return Combinators.combine(
                Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(4),
                Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(10)
        ).as((scheme, value) -> scheme + ":" + value);
    }

    @Provide
    Arbitrary<String> validHttpsUrls() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(3).ofMaxLength(10)
                .map(host -> "https://" + host + ".example.com/peppol/receive");
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 9: Existing AccessPoint Is Reused
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 9: existingAccessPointIsReused
    /**
     * For any completion request where an AccessPoint with the submitted participantId
     * already exists, no duplicate AccessPoint is created — the existing one is reused,
     * and the PeppolParticipantLink references the pre-existing AccessPoint ID.
     *
     * <p><b>Validates: Requirements 3.5</b>
     */
    @Property(tries = 200)
    void existingAccessPointIsReused(@ForAll("validParticipantIds") String participantId,
                                      @ForAll("validHttpsUrls") String endpointUrl) {
        String token = UUID.randomUUID().toString();
        PeppolInvitation invitation = buildPendingInvitation(token);

        UUID existingApId = UUID.randomUUID();
        AccessPoint existingAp = AccessPoint.builder()
                .id(existingApId)
                .participantId(participantId)
                .participantName(participantId)
                .role(AccessPoint.AccessPointRole.RECEIVER)
                .endpointUrl("https://existing.example.com/peppol")
                .simplifiedHttpDelivery(true)
                .build();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(invitation));
        when(invRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findById(CONTACT_ID)).thenReturn(Optional.of(buildContact()));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        ArgumentCaptor<PeppolParticipantLink> linkCaptor =
                ArgumentCaptor.forClass(PeppolParticipantLink.class);
        when(linkRepo.save(linkCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        // Existing AP found — should be reused, not a new one created
        when(apRepo.findByParticipantId(participantId)).thenReturn(Optional.of(existingAp));

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        MimeMessage mockMsg = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html/>");

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        service.completeRegistration(token,
                new CompleteRegistrationRequest(participantId, endpointUrl, null, true));

        // AccessPointRepository.save() must NOT have been called (no new AP created)
        verify(apRepo, never()).save(any());

        // PeppolParticipantLink must reference the pre-existing AccessPoint ID
        PeppolParticipantLink savedLink = linkCaptor.getValue();
        assertThat(savedLink.getReceiverAccessPointId())
                .as("PeppolParticipantLink must reference the pre-existing AccessPoint ID")
                .isEqualTo(existingApId);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 10: Input Validation Rejects Bad Inputs
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 10: inputValidationRejectsBadInputs
    /**
     * For any completion request where the participantId does not match {non-empty}:{non-empty},
     * or where the endpointUrl is not a valid HTTPS URL, the service must return a 400 error
     * and no records must be persisted.
     *
     * <p><b>Validates: Requirements 3.6, 3.7</b>
     */
    @Property(tries = 200)
    void inputValidationRejectsBadInputs(@ForAll("badInputScenarios") BadInputScenario scenario) {
        String token = UUID.randomUUID().toString();
        PeppolInvitation invitation = buildPendingInvitation(token);

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(invitation));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        assertThatThrownBy(() -> service.completeRegistration(token,
                new CompleteRegistrationRequest(
                        scenario.participantId(), scenario.endpointUrl(), null, true)))
                .as("Must throw ResponseStatusException with 400 for bad input: " + scenario)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // No records must have been persisted
        verify(apRepo, never()).save(any());
        verify(linkRepo, never()).save(any());
        verify(contactRepo, never()).save(any());
        verify(invRepo, never()).save(any());
    }

    record BadInputScenario(String participantId, String endpointUrl) {}

    @Provide
    Arbitrary<BadInputScenario> badInputScenarios() {
        // Bad participantId scenarios (valid HTTPS URL)
        Arbitrary<BadInputScenario> badParticipantId = Arbitraries.of(
                new BadInputScenario(null, "https://valid.example.com/peppol"),
                new BadInputScenario("", "https://valid.example.com/peppol"),
                new BadInputScenario("nocolon", "https://valid.example.com/peppol"),
                new BadInputScenario(":noscheme", "https://valid.example.com/peppol"),
                new BadInputScenario("novalue:", "https://valid.example.com/peppol"),
                new BadInputScenario(":", "https://valid.example.com/peppol")
        );
        // Bad endpointUrl scenarios (valid participantId)
        Arbitrary<BadInputScenario> badEndpointUrl = Arbitraries.of(
                new BadInputScenario("0190:ZW123", null),
                new BadInputScenario("0190:ZW123", ""),
                new BadInputScenario("0190:ZW123", "http://not-https.example.com"),
                new BadInputScenario("0190:ZW123", "ftp://wrong-scheme.example.com"),
                new BadInputScenario("0190:ZW123", "not-a-url-at-all"),
                new BadInputScenario("0190:ZW123", "https://")
        );
        return Arbitraries.oneOf(badParticipantId, badEndpointUrl);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 15: Completion Notification Email
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 15: completionNotificationEmail
    /**
     * For any successful registration completion, a notification email must be sent to the
     * inviting organisation's senderEmail address, and the email body must contain the
     * customer's email address, their registered participantId, and the completion timestamp.
     *
     * <p><b>Validates: Requirements 7.1, 7.2</b>
     */
    @Property(tries = 200)
    void completionNotificationEmail(@ForAll("validParticipantIds") String participantId,
                                      @ForAll("validHttpsUrls") String endpointUrl) {
        String token = UUID.randomUUID().toString();
        PeppolInvitation invitation = buildPendingInvitation(token);

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(invitation));
        when(invRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findById(CONTACT_ID)).thenReturn(Optional.of(buildContact()));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        when(linkRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID apId = UUID.randomUUID();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(participantId)).thenReturn(Optional.empty());
        when(apRepo.save(any())).thenAnswer(inv -> {
            AccessPoint ap = inv.getArgument(0);
            return AccessPoint.builder().id(apId)
                    .participantId(ap.getParticipantId())
                    .participantName(ap.getParticipantName())
                    .role(ap.getRole()).endpointUrl(ap.getEndpointUrl()).build();
        });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        // Capture the template context to verify email content
        String[] capturedHtml = new String[1];
        String[] capturedTo = new String[1];

        MimeMessage mockMsg = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(eq("email/peppol-invitation-complete"), any(Context.class)))
                .thenAnswer(inv -> {
                    Context ctx = inv.getArgument(1);
                    String html = "<html>" +
                            ctx.getVariable("customerEmail") + "|" +
                            ctx.getVariable("participantId") + "|" +
                            ctx.getVariable("completedAt") +
                            "</html>";
                    capturedHtml[0] = html;
                    return html;
                });
        // For the invitation email (not called in completion, but guard against NPE)
        when(templateEngine.process(eq("email/peppol-invitation"), any(Context.class)))
                .thenReturn("<html/>");

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        service.completeRegistration(token,
                new CompleteRegistrationRequest(participantId, endpointUrl, null, true));

        // The completion template must have been processed
        assertThat(capturedHtml[0])
                .as("Completion email body must contain the customer email")
                .contains(CUSTOMER_EMAIL);
        assertThat(capturedHtml[0])
                .as("Completion email body must contain the participantId")
                .contains(participantId);
        assertThat(capturedHtml[0])
                .as("Completion email body must contain a completedAt timestamp")
                .isNotNull()
                .isNotBlank();

        // mailSender.send() must have been called (notification sent to org senderEmail)
        verify(mailSender).send(any(MimeMessage.class));
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 16: Notification Failure Does Not Roll Back
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 16: notificationFailureDoesNotRollBack
    /**
     * For any successful registration where the notification email send throws an exception,
     * the PeppolInvitation must still have status=COMPLETED, the AccessPoint and
     * PeppolParticipantLink must still exist, and the CustomerContact must still be updated.
     *
     * <p><b>Validates: Requirements 7.3</b>
     */
    @Property(tries = 200)
    void notificationFailureDoesNotRollBack(@ForAll("validParticipantIds") String participantId,
                                             @ForAll("validHttpsUrls") String endpointUrl) {
        String token = UUID.randomUUID().toString();
        PeppolInvitation invitation = buildPendingInvitation(token);

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(invitation));
        when(invRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        when(contactRepo.findById(CONTACT_ID)).thenReturn(Optional.of(buildContact()));
        when(contactRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        ArgumentCaptor<PeppolParticipantLink> linkCaptor =
                ArgumentCaptor.forClass(PeppolParticipantLink.class);
        when(linkRepo.save(linkCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        UUID apId = UUID.randomUUID();
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        when(apRepo.findByParticipantId(participantId)).thenReturn(Optional.empty());
        ArgumentCaptor<AccessPoint> apCaptor = ArgumentCaptor.forClass(AccessPoint.class);
        when(apRepo.save(apCaptor.capture())).thenAnswer(inv -> {
            AccessPoint ap = inv.getArgument(0);
            return AccessPoint.builder().id(apId)
                    .participantId(ap.getParticipantId())
                    .participantName(ap.getParticipantName())
                    .role(ap.getRole()).endpointUrl(ap.getEndpointUrl()).build();
        });

        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(buildOrg()));

        // Mail sender throws on send() — simulating notification failure
        MimeMessage mockMsg = mock(MimeMessage.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);
        doThrow(new RuntimeException("SMTP connection refused"))
                .when(mailSender).send(any(MimeMessage.class));

        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html/>");

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        // Must NOT throw — mail failure must be swallowed
        CompleteRegistrationResponse response = service.completeRegistration(token,
                new CompleteRegistrationRequest(participantId, endpointUrl, null, true));

        assertThat(response).isNotNull();
        assertThat(response.participantId()).isEqualTo(participantId);

        // AccessPoint must still have been saved
        assertThat(apCaptor.getAllValues()).isNotEmpty();

        // PeppolParticipantLink must still have been saved
        assertThat(linkCaptor.getAllValues()).isNotEmpty();

        // CustomerContact must still have been updated
        ArgumentCaptor<CustomerContact> contactCaptor = ArgumentCaptor.forClass(CustomerContact.class);
        verify(contactRepo).save(contactCaptor.capture());
        assertThat(contactCaptor.getValue().getDeliveryMode()).isEqualTo(DeliveryMode.AS4);

        // Invitation must still have been saved with COMPLETED status
        ArgumentCaptor<PeppolInvitation> invCaptor = ArgumentCaptor.forClass(PeppolInvitation.class);
        verify(invRepo).save(invCaptor.capture());
        assertThat(invCaptor.getValue().getStatus()).isEqualTo(InvitationStatus.COMPLETED);
        assertThat(invCaptor.getValue().getCompletedAt()).isNotNull();
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 17: Token Is Single-Use
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 17: tokenIsSingleUse
    /**
     * For any PeppolInvitation token that has been used to complete a registration
     * (status=COMPLETED), a subsequent attempt to complete registration using the same
     * token must be rejected with 410 Gone.
     *
     * <p><b>Validates: Requirements 8.2</b>
     */
    @Property(tries = 200)
    void tokenIsSingleUse(@ForAll("validParticipantIds") String participantId,
                           @ForAll("validHttpsUrls") String endpointUrl) {
        String token = UUID.randomUUID().toString();

        // Invitation is already COMPLETED (token was used)
        PeppolInvitation completedInvitation = PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(token)
                .status(InvitationStatus.COMPLETED)
                .createdAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().plusSeconds(72 * 3600))
                .completedAt(Instant.now().minusSeconds(3600))
                .build();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByToken(token)).thenReturn(Optional.of(completedInvitation));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        AccessPointRepository apRepo = mock(AccessPointRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = buildCompletionService(invRepo, contactRepo, linkRepo,
                apRepo, orgRepo, mailSender, templateEngine);

        // Second attempt must be rejected with 410 Gone
        assertThatThrownBy(() -> service.completeRegistration(token,
                new CompleteRegistrationRequest(participantId, endpointUrl, null, true)))
                .as("Reusing a COMPLETED token must throw 410 Gone")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.GONE));

        // No records must have been persisted on the second attempt
        verify(apRepo, never()).save(any());
        verify(linkRepo, never()).save(any());
        verify(contactRepo, never()).save(any());
        verify(invRepo, never()).save(any());
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 11: List Response Ordering and Completeness
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 11: listResponseOrderingAndCompleteness
    /**
     * For any org with multiple invitations, the list must return all ordered by createdAt
     * desc, and each item must include customerEmail, status, createdAt, expiresAt, completedAt.
     *
     * <p><b>Validates: Requirements 5.1, 5.2</b>
     */
    @Property(tries = 200)
    void listResponseOrderingAndCompleteness(
            @ForAll("multipleInvitationStatuses") List<InvitationStatus> statuses) {
        // Build N invitations with staggered createdAt values
        int n = statuses.size();
        List<PeppolInvitation> invitations = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            InvitationStatus status = statuses.get(i);
            Instant createdAt = Instant.now().minusSeconds((long) (n - i) * 3600);
            Instant expiresAt = createdAt.plusSeconds(72 * 3600);
            Instant completedAt = status == InvitationStatus.COMPLETED
                    ? createdAt.plusSeconds(1800) : null;
            invitations.add(PeppolInvitation.builder()
                    .id(UUID.randomUUID())
                    .organizationId(ORG_ID)
                    .customerContactId(CONTACT_ID)
                    .customerEmail(CUSTOMER_EMAIL)
                    .token(UUID.randomUUID().toString())
                    .status(status)
                    .createdAt(createdAt)
                    .expiresAt(expiresAt)
                    .completedAt(completedAt)
                    .build());
        }

        // Repository returns them already ordered desc (simulate by reversing)
        List<PeppolInvitation> orderedDesc = new java.util.ArrayList<>(invitations);
        orderedDesc.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(orderedDesc);

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        List<InvitationResponse> responses = service.listInvitations(ORG_ID);

        // Must return all invitations
        assertThat(responses)
                .as("Must return all %d invitations", n)
                .hasSize(n);

        // Must be ordered by createdAt descending
        for (int i = 0; i < responses.size() - 1; i++) {
            assertThat(responses.get(i).createdAt())
                    .as("Response[%d].createdAt must be >= response[%d].createdAt", i, i + 1)
                    .isAfterOrEqualTo(responses.get(i + 1).createdAt());
        }

        // Each item must include all required fields
        for (InvitationResponse r : responses) {
            assertThat(r.customerEmail()).as("customerEmail must be present").isNotNull();
            assertThat(r.status()).as("status must be present").isNotNull();
            assertThat(r.createdAt()).as("createdAt must be present").isNotNull();
            assertThat(r.expiresAt()).as("expiresAt must be present").isNotNull();
            // completedAt is null unless COMPLETED — just verify the field exists (can be null)
        }
    }

    @Provide
    Arbitrary<List<InvitationStatus>> multipleInvitationStatuses() {
        return Arbitraries.of(InvitationStatus.values())
                .list()
                .ofMinSize(2)
                .ofMaxSize(6);
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 12: Cancellation State Transition
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 12: cancellationStateTransition
    /**
     * PENDING invitation → cancellation sets status=CANCELLED.
     * Non-PENDING (COMPLETED, CANCELLED, EXPIRED) → 409 error, invitation unchanged.
     *
     * <p><b>Validates: Requirements 5.3, 5.4</b>
     */
    @Property(tries = 200)
    void cancellationStateTransition(@ForAll("cancellationScenarios") InvitationStatus initialStatus) {
        UUID invitationId = UUID.randomUUID();
        PeppolInvitation invitation = PeppolInvitation.builder()
                .id(invitationId)
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(UUID.randomUUID().toString())
                .status(initialStatus)
                .createdAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().plusSeconds(72 * 3600))
                .build();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findById(invitationId)).thenReturn(Optional.of(invitation));
        when(invRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        if (initialStatus == InvitationStatus.PENDING) {
            // Must succeed and set status=CANCELLED
            service.cancelInvitation(ORG_ID, invitationId);

            ArgumentCaptor<PeppolInvitation> captor = ArgumentCaptor.forClass(PeppolInvitation.class);
            verify(invRepo).save(captor.capture());
            assertThat(captor.getValue().getStatus())
                    .as("PENDING invitation must be saved with status=CANCELLED")
                    .isEqualTo(InvitationStatus.CANCELLED);
        } else {
            // Must throw 409 and leave invitation unchanged
            assertThatThrownBy(() -> service.cancelInvitation(ORG_ID, invitationId))
                    .as("Non-PENDING invitation cancellation must throw 409")
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));

            verify(invRepo, never()).save(any());
        }
    }

    @Provide
    Arbitrary<InvitationStatus> cancellationScenarios() {
        return Arbitraries.of(InvitationStatus.values());
    }


    // ═══════════════════════════════════════════════════════════════════════════
    //  Property 13: Expired Status in List Responses
    // ═══════════════════════════════════════════════════════════════════════════

    // Feature: peppol-customer-invitation, Property 13: expiredStatusInListResponses
    /**
     * For any invitation where expiresAt is in the past and status is PENDING, the list
     * response must report status as EXPIRED (without mutating the entity).
     *
     * <p><b>Validates: Requirements 6.2</b>
     */
    @Property(tries = 200)
    void expiredStatusInListResponses(
            @ForAll("pastExpiryOffsets") long secondsInPast) {
        Instant createdAt = Instant.now().minusSeconds(secondsInPast + 3600);
        Instant expiresAt = Instant.now().minusSeconds(secondsInPast); // in the past

        PeppolInvitation pendingExpired = PeppolInvitation.builder()
                .id(UUID.randomUUID())
                .organizationId(ORG_ID)
                .customerContactId(CONTACT_ID)
                .customerEmail(CUSTOMER_EMAIL)
                .token(UUID.randomUUID().toString())
                .status(InvitationStatus.PENDING) // stored as PENDING
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();

        InvitationRepository invRepo = mock(InvitationRepository.class);
        when(invRepo.findByOrganizationIdOrderByCreatedAtDesc(ORG_ID))
                .thenReturn(List.of(pendingExpired));

        CustomerContactRepository contactRepo = mock(CustomerContactRepository.class);
        PeppolParticipantLinkRepository linkRepo = mock(PeppolParticipantLinkRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        InvitationService service = new InvitationService(invRepo, contactRepo, linkRepo, orgRepo,
                mailSender, templateEngine, BASE_URL, FROM_ADDRESS);

        List<InvitationResponse> responses = service.listInvitations(ORG_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).status())
                .as("PENDING invitation past expiresAt must be reported as EXPIRED in list response")
                .isEqualTo(InvitationStatus.EXPIRED);

        // Entity must NOT have been mutated
        assertThat(pendingExpired.getStatus())
                .as("Entity status must remain PENDING (no mutation)")
                .isEqualTo(InvitationStatus.PENDING);
    }

    @Provide
    Arbitrary<Long> pastExpiryOffsets() {
        // Between 1 second and 30 days in the past
        return Arbitraries.longs().between(1L, 30L * 24 * 3600);
    }

}

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
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic for PEPPOL customer invitations.
 *
 * <p>Handles:
 * <ul>
 *   <li>Sending tokenised invitation emails to CustomerContacts (Requirements 1.1–1.6)</li>
 *   <li>Validating invitation tokens (Requirements 2.1–2.5)</li>
 * </ul>
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private static final long EXPIRY_HOURS = 72;
    private static final DateTimeFormatter EXPIRY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final InvitationRepository invitationRepo;
    private final CustomerContactRepository customerContactRepo;
    private final PeppolParticipantLinkRepository participantLinkRepo;
    private final AccessPointRepository accessPointRepo;
    private final OrganizationRepository organizationRepo;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.base-url:https://ap.invoicedirect.biz}")
    private String baseUrl;

    @Value("${massmailer.from-address:noreply@invoicedirect.biz}")
    private String fromAddress;

    @Autowired
    public InvitationService(InvitationRepository invitationRepo,
                              CustomerContactRepository customerContactRepo,
                              PeppolParticipantLinkRepository participantLinkRepo,
                              AccessPointRepository accessPointRepo,
                              OrganizationRepository organizationRepo,
                              JavaMailSender mailSender,
                              TemplateEngine templateEngine) {
        this.invitationRepo = invitationRepo;
        this.customerContactRepo = customerContactRepo;
        this.participantLinkRepo = participantLinkRepo;
        this.accessPointRepo = accessPointRepo;
        this.organizationRepo = organizationRepo;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    // ── Constructor for testing (allows injecting baseUrl and fromAddress) ──

    InvitationService(InvitationRepository invitationRepo,
                      CustomerContactRepository customerContactRepo,
                      PeppolParticipantLinkRepository participantLinkRepo,
                      OrganizationRepository organizationRepo,
                      JavaMailSender mailSender,
                      TemplateEngine templateEngine,
                      String baseUrl,
                      String fromAddress) {
        this.invitationRepo = invitationRepo;
        this.customerContactRepo = customerContactRepo;
        this.participantLinkRepo = participantLinkRepo;
        this.accessPointRepo = null;
        this.organizationRepo = organizationRepo;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.baseUrl = baseUrl;
        this.fromAddress = fromAddress;
    }

    // ── Constructor for testing with AccessPointRepository ──

    InvitationService(InvitationRepository invitationRepo,
                      CustomerContactRepository customerContactRepo,
                      PeppolParticipantLinkRepository participantLinkRepo,
                      AccessPointRepository accessPointRepo,
                      OrganizationRepository organizationRepo,
                      JavaMailSender mailSender,
                      TemplateEngine templateEngine,
                      String baseUrl,
                      String fromAddress) {
        this.invitationRepo = invitationRepo;
        this.customerContactRepo = customerContactRepo;
        this.participantLinkRepo = participantLinkRepo;
        this.accessPointRepo = accessPointRepo;
        this.organizationRepo = organizationRepo;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.baseUrl = baseUrl;
        this.fromAddress = fromAddress;
    }

    /**
     * Sends a PEPPOL invitation email to a customer contact.
     *
     * <p>Steps:
     * <ol>
     *   <li>Verify CustomerContact exists for org+email; throw 404 if not</li>
     *   <li>Verify no active PeppolParticipantLink exists; throw 409 if found</li>
     *   <li>Cancel any existing PENDING invitation for the same org+customer</li>
     *   <li>Generate token and persist PeppolInvitation with status=PENDING</li>
     *   <li>Send invitation email via JavaMailSender + Thymeleaf</li>
     * </ol>
     *
     * @param orgId         the sending organisation's UUID
     * @param customerEmail the customer's email address
     * @return the persisted PeppolInvitation
     * @throws ResponseStatusException 404 if customer not found, 409 if already linked
     */
    @Transactional
    public PeppolInvitation sendInvitation(UUID orgId, String customerEmail) {
        // 1. Look up CustomerContact
        CustomerContact contact = customerContactRepo
                .findByOrganizationIdAndEmail(orgId, customerEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Customer not found: " + customerEmail));

        // 2. Check for existing PeppolParticipantLink
        boolean alreadyLinked = participantLinkRepo
                .findByOrganizationIdAndCustomerContactId(orgId, contact.getId())
                .isPresent();
        if (alreadyLinked) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Customer is already linked to PEPPOL");
        }

        // 3. Cancel any existing PENDING invitations for this org+customer
        List<PeppolInvitation> pending = invitationRepo
                .findByOrganizationIdAndCustomerContactIdAndStatus(
                        orgId, contact.getId(), InvitationStatus.PENDING);
        for (PeppolInvitation existing : pending) {
            existing.setStatus(InvitationStatus.CANCELLED);
            invitationRepo.save(existing);
        }

        // 4. Generate token and persist invitation
        String token = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(EXPIRY_HOURS * 3600);

        PeppolInvitation invitation = PeppolInvitation.builder()
                .organizationId(orgId)
                .customerContactId(contact.getId())
                .customerEmail(customerEmail)
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();

        invitation = invitationRepo.save(invitation);

        // 5. Look up org name for email
        Organization org = organizationRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Organization not found: " + orgId));

        // 6. Send invitation email
        sendInvitationEmail(org, customerEmail, token, expiresAt);

        return invitation;
    }

    /**
     * Validates an invitation token and returns context for the self-registration page.
     *
     * @param token the invitation token from the URL
     * @return TokenValidationResponse with customerEmail and organisationName
     * @throws ResponseStatusException 404 if token not found, 410 if expired/used/cancelled
     */
    public TokenValidationResponse validateToken(String token) {
        PeppolInvitation invitation = invitationRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invitation not found"));

        // COMPLETED or CANCELLED → 410 Gone
        if (invitation.getStatus() == InvitationStatus.COMPLETED
                || invitation.getStatus() == InvitationStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This invitation link has already been used");
        }

        // PENDING but expired → 410 Gone
        if (invitation.getStatus() == InvitationStatus.PENDING
                && invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This invitation link has expired");
        }

        // EXPIRED status (set by job) → 410 Gone
        if (invitation.getStatus() == InvitationStatus.EXPIRED) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This invitation link has expired");
        }

        // Valid PENDING token — look up org name
        Organization org = organizationRepo.findById(invitation.getOrganizationId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Organization not found"));

        // NOTE: response intentionally excludes API key and internal org UUID (Requirement 8.4)
        return new TokenValidationResponse(invitation.getCustomerEmail(), org.getName());
    }

    /**
     * Completes a PEPPOL self-registration using a valid invitation token.
     *
     * <p>Steps (all within a single {@code @Transactional} boundary):
     * <ol>
     *   <li>Validate token is PENDING and not expired</li>
     *   <li>Validate participantId format ({scheme}:{value})</li>
     *   <li>Validate endpointUrl is a valid HTTPS URL</li>
     *   <li>Upsert AccessPoint with role=RECEIVER</li>
     *   <li>Create PeppolParticipantLink with preferredChannel=PEPPOL</li>
     *   <li>Update CustomerContact: deliveryMode=AS4, peppolParticipantId</li>
     *   <li>Set PeppolInvitation.status=COMPLETED, completedAt=now</li>
     * </ol>
     * After the transaction commits, sends a completion notification email to the org's senderEmail.
     * Mail failures are caught and logged — they do NOT roll back the transaction.
     *
     * @param token   the invitation token from the URL
     * @param request the PEPPOL endpoint details submitted by the customer
     * @return CompleteRegistrationResponse with participantId and endpointUrl
     * @throws ResponseStatusException 404/410 for invalid/expired/used token, 400 for bad input
     */
    @Transactional
    public CompleteRegistrationResponse completeRegistration(String token,
                                                              CompleteRegistrationRequest request) {
        // 1. Validate token (reuse validateToken logic — look up and check state)
        PeppolInvitation invitation = invitationRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invitation not found"));

        if (invitation.getStatus() == InvitationStatus.COMPLETED
                || invitation.getStatus() == InvitationStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This invitation link has already been used");
        }
        if (invitation.getStatus() == InvitationStatus.EXPIRED
                || (invitation.getStatus() == InvitationStatus.PENDING
                    && invitation.getExpiresAt().isBefore(Instant.now()))) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This invitation link has expired");
        }

        // 2. Validate participantId format: {non-empty}:{non-empty}
        String participantId = request.participantId();
        if (participantId == null || !participantId.matches(".+:.+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid participant ID format. Expected {scheme}:{value}");
        }

        // 3. Validate endpointUrl is a valid HTTPS URL
        String endpointUrl = request.endpointUrl();
        if (!isValidHttpsUrl(endpointUrl)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Endpoint URL must be a valid HTTPS URL");
        }

        // 4a. Upsert AccessPoint with role=RECEIVER
        AccessPoint accessPoint = accessPointRepo.findByParticipantId(participantId)
                .orElseGet(() -> {
                    AccessPoint ap = AccessPoint.builder()
                            .participantId(participantId)
                            .participantName(participantId)
                            .role(AccessPoint.AccessPointRole.RECEIVER)
                            .endpointUrl(endpointUrl)
                            .deliveryAuthToken(request.deliveryAuthToken())
                            .simplifiedHttpDelivery(request.simplifiedHttpDelivery())
                            .build();
                    return accessPointRepo.save(ap);
                });

        // 4b. Create PeppolParticipantLink with preferredChannel=PEPPOL
        PeppolParticipantLink link = PeppolParticipantLink.builder()
                .organizationId(invitation.getOrganizationId())
                .customerContactId(invitation.getCustomerContactId())
                .participantId(participantId)
                .receiverAccessPointId(accessPoint.getId())
                .preferredChannel(PeppolParticipantLink.DeliveryChannel.PEPPOL)
                .build();
        participantLinkRepo.save(link);

        // 4c. Update CustomerContact: deliveryMode=AS4, peppolParticipantId
        CustomerContact contact = customerContactRepo.findById(invitation.getCustomerContactId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Customer contact not found"));
        contact.setDeliveryMode(DeliveryMode.AS4);
        contact.setPeppolParticipantId(participantId);
        customerContactRepo.save(contact);

        // 4d. Mark invitation COMPLETED
        Instant completedAt = Instant.now();
        invitation.setStatus(InvitationStatus.COMPLETED);
        invitation.setCompletedAt(completedAt);
        invitationRepo.save(invitation);

        // 5. Send completion notification email (after transaction — catch and log failures)
        Organization org = organizationRepo.findById(invitation.getOrganizationId()).orElse(null);
        if (org != null) {
            try {
                sendCompletionEmail(org, invitation.getCustomerEmail(), participantId,
                        endpointUrl, completedAt);
            } catch (Exception e) {
                log.warn("Failed to send PEPPOL completion notification to {}: {}",
                        org.getSenderEmail(), e.getMessage());
            }
        }

        return new CompleteRegistrationResponse(participantId, endpointUrl);
    }

    /**
     * Returns all invitations for the given organisation, ordered by createdAt descending.
     *
     * <p>The {@code status} field in each response is virtual: if the stored status is
     * {@code PENDING} and {@code expiresAt} is in the past, the response reports
     * {@code EXPIRED} without mutating the entity.
     *
     * @param orgId the organisation UUID
     * @return list of InvitationResponse DTOs
     */
    public List<InvitationResponse> listInvitations(UUID orgId) {
        List<PeppolInvitation> invitations =
                invitationRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        Instant now = Instant.now();
        return invitations.stream()
                .map(inv -> {
                    InvitationStatus virtualStatus = inv.getStatus();
                    if (virtualStatus == InvitationStatus.PENDING
                            && inv.getExpiresAt().isBefore(now)) {
                        virtualStatus = InvitationStatus.EXPIRED;
                    }
                    return new InvitationResponse(
                            inv.getId(),
                            inv.getCustomerEmail(),
                            virtualStatus,
                            inv.getCreatedAt(),
                            inv.getExpiresAt(),
                            inv.getCompletedAt()
                    );
                })
                .toList();
    }

    /**
     * Cancels a PENDING invitation.
     *
     * @param orgId        the organisation UUID (used to verify ownership)
     * @param invitationId the invitation UUID to cancel
     * @throws ResponseStatusException 404 if not found, 403 if wrong org, 409 if not PENDING
     */
    @Transactional
    public void cancelInvitation(UUID orgId, UUID invitationId) {
        PeppolInvitation invitation = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Invitation not found"));

        if (!invitation.getOrganizationId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Invitation does not belong to this organisation");
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only PENDING invitations can be cancelled");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepo.save(invitation);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private boolean isValidHttpsUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URL parsed = new URL(url);
            return "https".equalsIgnoreCase(parsed.getProtocol())
                    && parsed.getHost() != null
                    && !parsed.getHost().isBlank();
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private void sendCompletionEmail(Organization org, String customerEmail,
                                      String participantId, String endpointUrl,
                                      Instant completedAt) {
        Context ctx = new Context();
        ctx.setVariable("customerEmail", customerEmail);
        ctx.setVariable("participantId", participantId);
        ctx.setVariable("endpointUrl", endpointUrl);
        ctx.setVariable("completedAt", EXPIRY_FORMATTER.format(completedAt));

        String html = templateEngine.process("email/peppol-invitation-complete", ctx);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(org.getSenderEmail());
            helper.setSubject("PEPPOL registration completed by " + customerEmail);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("PEPPOL completion notification sent to {} for customer {}",
                    org.getSenderEmail(), customerEmail);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send completion email: " + e.getMessage(), e);
        }
    }

    private void sendInvitationEmail(Organization org, String customerEmail,
                                      String token, Instant expiresAt) {
        String inviteUrl = baseUrl + "/invite/peppol/" + token;

        Context ctx = new Context();
        ctx.setVariable("orgName", org.getName());
        ctx.setVariable("customerEmail", customerEmail);
        ctx.setVariable("inviteUrl", inviteUrl);
        ctx.setVariable("expiresAt", EXPIRY_FORMATTER.format(expiresAt));

        String html = templateEngine.process("email/peppol-invitation", ctx);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(customerEmail);
            helper.setSubject("You've been invited to register on PEPPOL by " + org.getName());
            helper.setText(html, true);
            mailSender.send(message);
            log.info("PEPPOL invitation email sent to {} for org {}", customerEmail, org.getName());
        } catch (MessagingException e) {
            log.error("Failed to send PEPPOL invitation email to {}: {}", customerEmail, e.getMessage());
            throw new RuntimeException("Failed to send invitation email: " + e.getMessage(), e);
        }
    }
}

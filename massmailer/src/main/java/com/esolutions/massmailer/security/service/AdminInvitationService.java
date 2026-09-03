package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.invitation.model.InvitationStatus;
import com.esolutions.massmailer.security.AdminDtos.AdminInvitationDto;
import com.esolutions.massmailer.security.AdminDtos.AdminInvitationTokenValidationResponse;
import com.esolutions.massmailer.security.AdminDtos.AdminUserDto;
import com.esolutions.massmailer.security.model.AdminInvitation;
import com.esolutions.massmailer.security.repository.AdminInvitationRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Core business logic for platform-admin email invitations.
 *
 * <p>Mirrors {@link com.esolutions.massmailer.invitation.service.InvitationService}
 * (PEPPOL customer invitations) — same token/expiry/status model, same email-sending
 * approach via {@link JavaMailSender} + Thymeleaf. Account creation itself delegates to
 * {@link AdminUserService#createUser} so both entry points (direct create, invite) share
 * one source of truth for password/uniqueness validation.
 */
@Service
public class AdminInvitationService {

    private static final Logger log = LoggerFactory.getLogger(AdminInvitationService.class);

    private static final long EXPIRY_HOURS = 72;
    private static final DateTimeFormatter EXPIRY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final AdminInvitationRepository invitationRepo;
    private final AdminUserService adminUserService;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.base-url:https://ap.invoicedirect.biz}")
    private String baseUrl;

    @Value("${massmailer.from-address:noreply@invoicedirect.biz}")
    private String fromAddress;

    public AdminInvitationService(AdminInvitationRepository invitationRepo,
                                   AdminUserService adminUserService,
                                   JavaMailSender mailSender,
                                   TemplateEngine templateEngine) {
        this.invitationRepo = invitationRepo;
        this.adminUserService = adminUserService;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    /**
     * Sends a platform-admin invitation email.
     *
     * <p>Steps:
     * <ol>
     *   <li>Cancel any existing PENDING invitation for the same email</li>
     *   <li>Generate token and persist AdminInvitation with status=PENDING</li>
     *   <li>Send invitation email via JavaMailSender + Thymeleaf</li>
     * </ol>
     */
    @Transactional
    public AdminInvitationDto sendInvitation(String email, String displayName, String invitedByUsername) {
        List<AdminInvitation> pending = invitationRepo.findByEmailAndStatus(email, InvitationStatus.PENDING);
        for (AdminInvitation existing : pending) {
            existing.setStatus(InvitationStatus.CANCELLED);
            invitationRepo.save(existing);
        }

        String token = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(EXPIRY_HOURS * 3600);

        AdminInvitation invitation = AdminInvitation.builder()
                .email(email)
                .displayName(displayName)
                .invitedBy(invitedByUsername)
                .token(token)
                .status(InvitationStatus.PENDING)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();

        invitation = invitationRepo.save(invitation);

        sendInvitationEmail(email, displayName, invitedByUsername, token, expiresAt);

        return toDto(invitation);
    }

    /**
     * Validates an invitation token and returns context for the self-registration page.
     */
    public AdminInvitationTokenValidationResponse validateToken(String token) {
        AdminInvitation invitation = findValidPending(token);
        return new AdminInvitationTokenValidationResponse(
                invitation.getEmail(), invitation.getDisplayName(), invitation.getInvitedBy());
    }

    /**
     * Completes platform-admin self-registration using a valid invitation token.
     * Delegates the actual account creation to {@link AdminUserService#createUser},
     * so password strength and username-uniqueness rules stay in one place.
     */
    @Transactional
    public AdminUserDto completeRegistration(String token, String username, String password) {
        AdminInvitation invitation = findValidPending(token);

        AdminUserDto created = adminUserService.createUser(username, password, invitation.getDisplayName());

        invitation.setStatus(InvitationStatus.COMPLETED);
        invitation.setCompletedAt(Instant.now());
        invitationRepo.save(invitation);

        log.info("Platform admin invitation completed: {} (invited by {})", username, invitation.getInvitedBy());
        return created;
    }

    /**
     * Returns all admin invitations, ordered by createdAt descending.
     *
     * <p>The {@code status} field is virtual: a stored PENDING invitation past its
     * {@code expiresAt} is reported as EXPIRED without mutating the entity.
     */
    public List<AdminInvitationDto> listInvitations() {
        Instant now = Instant.now();
        return invitationRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(inv -> {
                    InvitationStatus virtualStatus = inv.getStatus();
                    if (virtualStatus == InvitationStatus.PENDING && inv.getExpiresAt().isBefore(now)) {
                        virtualStatus = InvitationStatus.EXPIRED;
                    }
                    return new AdminInvitationDto(inv.getId(), inv.getEmail(), inv.getDisplayName(),
                            inv.getInvitedBy(), virtualStatus, inv.getCreatedAt(),
                            inv.getExpiresAt(), inv.getCompletedAt());
                })
                .toList();
    }

    /**
     * Cancels a PENDING invitation.
     */
    @Transactional
    public void cancelInvitation(UUID invitationId) {
        AdminInvitation invitation = invitationRepo.findById(invitationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only PENDING invitations can be cancelled");
        }

        invitation.setStatus(InvitationStatus.CANCELLED);
        invitationRepo.save(invitation);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private AdminInvitation findValidPending(String token) {
        AdminInvitation invitation = invitationRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));

        if (invitation.getStatus() == InvitationStatus.COMPLETED
                || invitation.getStatus() == InvitationStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.GONE, "This invitation link has already been used");
        }
        if (invitation.getStatus() == InvitationStatus.EXPIRED
                || (invitation.getStatus() == InvitationStatus.PENDING
                    && invitation.getExpiresAt().isBefore(Instant.now()))) {
            throw new ResponseStatusException(HttpStatus.GONE, "This invitation link has expired");
        }
        return invitation;
    }

    private AdminInvitationDto toDto(AdminInvitation inv) {
        return new AdminInvitationDto(inv.getId(), inv.getEmail(), inv.getDisplayName(), inv.getInvitedBy(),
                inv.getStatus(), inv.getCreatedAt(), inv.getExpiresAt(), inv.getCompletedAt());
    }

    private void sendInvitationEmail(String email, String displayName, String invitedByUsername,
                                      String token, Instant expiresAt) {
        String inviteUrl = baseUrl + "/invite/admin/" + token;

        Context ctx = new Context();
        ctx.setVariable("displayName", displayName != null && !displayName.isBlank() ? displayName : email);
        ctx.setVariable("invitedBy", invitedByUsername);
        ctx.setVariable("inviteUrl", inviteUrl);
        ctx.setVariable("expiresAt", EXPIRY_FORMATTER.format(expiresAt));

        String html = templateEngine.process("email/admin-invitation", ctx);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("You've been invited to InvoiceDirect as a platform admin");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Admin invitation email sent to {} (invited by {})", email, invitedByUsername);
        } catch (MessagingException e) {
            log.error("Failed to send admin invitation email to {}: {}", email, e.getMessage());
            throw new RuntimeException("Failed to send invitation email: " + e.getMessage(), e);
        }
    }
}

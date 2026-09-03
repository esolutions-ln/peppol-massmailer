package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.security.AdminDtos.AdminPasswordResetTokenValidationResponse;
import com.esolutions.massmailer.security.model.AdminPasswordReset;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminPasswordResetRepository;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
 * "Forgot password" self-service reset for platform admins.
 *
 * <p>Mirrors {@link AdminInvitationService}'s token/email approach, but with a shorter
 * expiry (1 hour, vs 72 hours for account invitations) matching standard password-reset
 * conventions, and without exposing whether a given username exists — {@link #requestReset}
 * always completes silently whether or not a matching, emailable account was found.
 */
@Service
public class AdminPasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordResetService.class);

    private static final long EXPIRY_MINUTES = 60;
    private static final DateTimeFormatter EXPIRY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    private final AdminPasswordResetRepository resetRepo;
    private final AdminUserRepository adminUserRepository;
    private final AdminSessionTokenRepository tokenRepo;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.base-url:https://ap.invoicedirect.biz}")
    private String baseUrl;

    @Value("${massmailer.from-address:noreply@invoicedirect.biz}")
    private String fromAddress;

    public AdminPasswordResetService(AdminPasswordResetRepository resetRepo,
                                      AdminUserRepository adminUserRepository,
                                      AdminSessionTokenRepository tokenRepo,
                                      BCryptPasswordEncoder passwordEncoder,
                                      JavaMailSender mailSender,
                                      TemplateEngine templateEngine) {
        this.resetRepo = resetRepo;
        this.adminUserRepository = adminUserRepository;
        this.tokenRepo = tokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    /**
     * Requests a password reset link for the given username. Always completes without
     * error or distinguishing outcome — whether the username doesn't exist, is inactive,
     * or has no email on file, the caller sees the same generic response. This is a
     * deliberate anti-enumeration measure, not an oversight.
     */
    @Transactional
    public void requestReset(String username) {
        adminUserRepository.findByUsername(username)
                .filter(AdminUser::isActive)
                .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                .ifPresentOrElse(this::issueAndSendToken,
                        () -> log.info("Password reset requested for '{}' — no matching, emailable, active account", username));
    }

    private void issueAndSendToken(AdminUser user) {
        List<AdminPasswordReset> existing = resetRepo.findByAdminUserIdAndUsedAtIsNull(user.getId());
        Instant now = Instant.now();
        for (AdminPasswordReset r : existing) {
            r.setUsedAt(now);
            resetRepo.save(r);
        }

        String token = UUID.randomUUID().toString();
        Instant expiresAt = now.plusSeconds(EXPIRY_MINUTES * 60);
        AdminPasswordReset reset = AdminPasswordReset.builder()
                .adminUserId(user.getId())
                .token(token)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();
        resetRepo.save(reset);

        sendResetEmail(user, token, expiresAt);
    }

    /**
     * Validates a reset token and returns the username it belongs to, for display
     * on the "set a new password" page.
     */
    public AdminPasswordResetTokenValidationResponse validateToken(String token) {
        AdminPasswordReset reset = findValidUnused(token);
        AdminUser user = adminUserRepository.findById(reset.getAdminUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin user not found"));
        return new AdminPasswordResetTokenValidationResponse(user.getUsername());
    }

    /**
     * Completes a password reset. Invalidates every active session for the account —
     * including the one used to request the reset, if any — so a compromised password
     * cannot be reused elsewhere after a reset.
     */
    @Transactional
    public void completeReset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "New password must be at least 8 characters");
        }
        AdminPasswordReset reset = findValidUnused(token);
        AdminUser user = adminUserRepository.findById(reset.getAdminUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin user not found"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        adminUserRepository.save(user);

        reset.setUsedAt(Instant.now());
        resetRepo.save(reset);

        tokenRepo.deleteByAdminUser(user);
        log.info("Password reset completed for admin '{}'", user.getUsername());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private AdminPasswordReset findValidUnused(String token) {
        AdminPasswordReset reset = resetRepo.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reset link not found"));
        if (reset.getUsedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "This reset link has already been used");
        }
        if (reset.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This reset link has expired");
        }
        return reset;
    }

    private void sendResetEmail(AdminUser user, String token, Instant expiresAt) {
        String resetUrl = baseUrl + "/admin/reset-password/" + token;

        Context ctx = new Context();
        ctx.setVariable("username", user.getUsername());
        ctx.setVariable("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        ctx.setVariable("resetUrl", resetUrl);
        ctx.setVariable("expiresAt", EXPIRY_FORMATTER.format(expiresAt));

        String html = templateEngine.process("email/admin-password-reset", ctx);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(user.getEmail());
            helper.setSubject("Reset your InvoiceDirect admin password");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Admin password reset email sent to {} for user {}", user.getEmail(), user.getUsername());
        } catch (MessagingException e) {
            log.error("Failed to send admin password reset email to {}: {}", user.getEmail(), e.getMessage());
            throw new RuntimeException("Failed to send reset email: " + e.getMessage(), e);
        }
    }
}

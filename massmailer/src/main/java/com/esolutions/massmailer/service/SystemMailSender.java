package com.esolutions.massmailer.service;

import com.esolutions.massmailer.brevo.BrevoEmailClient;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

/**
 * Transport for platform-generated mail (admin invitations, password resets,
 * PEPPOL invitations, platform billing invoices).
 *
 * <p>When {@code massmailer.brevo.enabled=true} (production) mail goes out through
 * Brevo's HTTPS API ({@code POST /v3/smtp/email}); otherwise through Spring's
 * {@link JavaMailSender}. Invoice delivery uses {@link SmtpSendService}, which makes
 * the same choice but adds org-specific sender resolution, CC contacts and
 * PDF attachments.</p>
 */
@Component
public class SystemMailSender {

    private static final Logger log = LoggerFactory.getLogger(SystemMailSender.class);

    private final JavaMailSender mailSender;
    private final BrevoEmailClient brevo;

    @Autowired
    public SystemMailSender(JavaMailSender mailSender, ObjectProvider<BrevoEmailClient> brevoProvider,
                            @Value("${spring.mail.password:}") String smtpPassword) {
        this(mailSender, brevoProvider.getIfAvailable());
        if (brevo != null) {
            log.info("Outbound mail transport: Brevo API");
        } else if (smtpPassword == null || smtpPassword.isBlank()) {
            log.warn("Outbound mail transport: SMTP, but SPRING_MAIL_PASSWORD is not set — "
                    + "sends will fail. Production should set BREVO_ENABLED=true and BREVO_API_KEY.");
        } else {
            log.info("Outbound mail transport: SMTP (Brevo disabled)");
        }
    }

    /** Direct constructor — pass {@code brevo = null} to force the SMTP path (tests). */
    public SystemMailSender(JavaMailSender mailSender, BrevoEmailClient brevo) {
        this.mailSender = mailSender;
        this.brevo = brevo;
    }

    /**
     * Sends an HTML email.
     *
     * @param fromName display name; nullable
     * @param toName   display name; nullable
     * @param headers  extra headers; nullable
     * @throws MessagingException on delivery failure (either transport)
     */
    public void sendHtml(String fromEmail, String fromName,
                         String toEmail, String toName,
                         String subject, String html,
                         Map<String, String> headers) throws MessagingException {
        if (brevo != null) {
            sendViaBrevo(fromEmail, fromName, toEmail, toName, subject, html, headers);
        } else {
            sendViaSmtp(fromEmail, fromName, toEmail, toName, subject, html, headers);
        }
    }

    private void sendViaBrevo(String fromEmail, String fromName, String toEmail, String toName,
                              String subject, String html, Map<String, String> headers)
            throws MessagingException {
        var req = BrevoEmailClient.SendRequest.builder()
                .sender(fromEmail, blankToNull(fromName))
                .to(toEmail, blankToNull(toName))
                .subject(subject)
                .html(html)
                .tag("system");
        if (headers != null) headers.forEach(req::header);
        try {
            String messageId = brevo.sendTransactional(req.build());
            log.debug("System mail '{}' sent to {} via Brevo [msgId={}]", subject, toEmail, messageId);
        } catch (IOException e) {
            throw new MessagingException("Brevo send failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted during Brevo send", e);
        }
    }

    private void sendViaSmtp(String fromEmail, String fromName, String toEmail, String toName,
                             String subject, String html, Map<String, String> headers)
            throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        try {
            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(new InternetAddress(fromEmail, fromName, "UTF-8"));
            } else {
                helper.setFrom(fromEmail);
            }
            if (toName != null && !toName.isBlank()) {
                helper.setTo(new InternetAddress(toEmail, toName, "UTF-8"));
            } else {
                helper.setTo(toEmail);
            }
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Encoding error: " + e.getMessage(), e);
        }
        helper.setSubject(subject);
        helper.setText(html, true);
        if (headers != null) {
            for (var h : headers.entrySet()) message.setHeader(h.getKey(), h.getValue());
        }
        mailSender.send(message);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

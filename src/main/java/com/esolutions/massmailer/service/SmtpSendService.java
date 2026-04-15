package com.esolutions.massmailer.service;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.Semaphore;

/**
 * Low-level SMTP send service for fiscalised invoice emails.
 *
 * Responsibilities:
 * - Composes multipart MIME message (HTML body + PDF attachment)
 * - Sets no-reply From / Reply-To / auto-response-suppress headers
 * - Rate-limits via Semaphore to avoid SMTP provider throttling
 * - Retries transient SMTP failures via Spring @Retryable
 */
@Service
public class SmtpSendService {

    private static final Logger log = LoggerFactory.getLogger(SmtpSendService.class);

    private final JavaMailSender mailSender;
    private final MailerProperties props;
    private final Semaphore rateLimiter;

    public SmtpSendService(JavaMailSender mailSender, MailerProperties props, Semaphore rateLimiter) {
        this.mailSender = mailSender;
        this.props = props;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Sends an HTML email with an optional PDF invoice attachment.
     * Retries up to 3× on transient SMTP errors with exponential backoff.
     *
     * @param toEmail       recipient address
     * @param toName        recipient display name (nullable)
     * @param subject       email subject line
     * @param htmlBody      rendered HTML body
     * @param invoiceNumber the fiscal invoice number (for tracking/logging)
     * @param attachment    resolved PDF attachment (nullable — email still sends without it)
     * @return sealed DeliveryResult
     */
    @Retryable(
            retryFor = MessagingException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public DeliveryResult send(String toEmail, String toName, String subject,
                                String htmlBody, String invoiceNumber,
                                ResolvedAttachment attachment) throws MessagingException {
        try {
            rateLimiter.acquire();
            try {
                MimeMessage message = mailSender.createMimeMessage();

                // multipart=true required for attachments
                boolean hasAttachment = attachment != null;
                MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachment, "UTF-8");

                // ── No-Reply sender identity ──
                helper.setFrom(new InternetAddress(props.fromAddress(), props.fromName()));
                helper.setReplyTo(props.fromAddress());

                // ── Recipient ──
                if (toName != null && !toName.isBlank()) {
                    helper.setTo(new InternetAddress(toEmail, toName));
                } else {
                    helper.setTo(toEmail);
                }

                helper.setSubject(subject);
                helper.setText(htmlBody, true);

                // ── Attach the invoice PDF ──
                long attachmentSize = 0;
                if (hasAttachment) {
                    helper.addAttachment(
                            attachment.fileName(),
                            attachment.source(),
                            attachment.contentType()
                    );
                    attachmentSize = attachment.sizeBytes();
                    log.debug("Attached PDF '{}' ({} bytes) for invoice {}",
                            attachment.fileName(), attachmentSize, invoiceNumber);
                }

                // ── Anti-reply & bulk headers ──
                message.setHeader("X-Auto-Response-Suppress", "All");
                message.setHeader("Auto-Submitted", "auto-generated");
                message.setHeader("Precedence", "bulk");

                // ── Custom header for fiscal traceability ──
                if (invoiceNumber != null) {
                    message.setHeader("X-Invoice-Number", invoiceNumber);
                }

                mailSender.send(message);

                String messageId = message.getMessageID();
                log.info("✓ Invoice {} sent to {} [msgId={}, pdf={}bytes]",
                        invoiceNumber, toEmail, messageId, attachmentSize);

                return new DeliveryResult.Delivered(toEmail, invoiceNumber, messageId, attachmentSize);

            } finally {
                rateLimiter.release();
            }
        } catch (MessagingException e) {
            log.error("✗ SMTP failure for {} (invoice {}): {}", toEmail, invoiceNumber, e.getMessage());
            throw e; // let @Retryable handle it
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Thread interrupted during rate-limit wait", false);
        } catch (UnsupportedEncodingException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Encoding error: " + e.getMessage(), false);
        }
    }

    /**
     * Sends with fallback — absorbs exceptions after retry exhaustion
     * and returns a Failed result instead of propagating.
     */
    public DeliveryResult sendWithFallback(String toEmail, String toName, String subject,
                                            String htmlBody, String invoiceNumber,
                                            ResolvedAttachment attachment) {
        try {
            return send(toEmail, toName, subject, htmlBody, invoiceNumber, attachment);
        } catch (MessagingException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    e.getMessage(), isRetryable(e));
        }
    }

    private boolean isRetryable(MessagingException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection")
                || msg.contains("temporarily") || msg.contains("try again");
    }
}

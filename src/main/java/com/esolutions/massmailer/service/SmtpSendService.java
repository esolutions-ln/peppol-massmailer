package com.esolutions.massmailer.service;

import com.esolutions.massmailer.brevo.BrevoEmailClient;
import com.esolutions.massmailer.brevo.BrevoSenderResolver;
import com.esolutions.massmailer.brevo.BrevoSenderResolver.Sender;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.customer.model.Contact;
import com.esolutions.massmailer.customer.service.ContactService;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Outbound mail dispatch for fiscalised invoice emails.
 *
 * Two transports, selected at runtime:
 *  - Brevo HTTPS transactional API (preferred when massmailer.brevo.enabled=true)
 *  - Spring JavaMailSender fallback (SMTP / Gmail OAuth2)
 *
 * Common responsibilities for both transports:
 *  - Per-org "From" resolution via {@link BrevoSenderResolver}
 *  - Rate-limited via Semaphore to avoid provider throttling
 *  - Retried on transient failures via {@code @Retryable}
 */
@Service
public class SmtpSendService {

    private static final Logger log = LoggerFactory.getLogger(SmtpSendService.class);

    private final JavaMailSender mailSender;
    private final MailerProperties props;
    private final Semaphore rateLimiter;
    private final BrevoSenderResolver senderResolver;
    private final BrevoEmailClient brevo; // null when massmailer.brevo.enabled=false
    private final ContactService contactService;

    public SmtpSendService(JavaMailSender mailSender,
                           MailerProperties props,
                           Semaphore rateLimiter,
                           BrevoSenderResolver senderResolver,
                           ObjectProvider<BrevoEmailClient> brevoProvider,
                           ContactService contactService) {
        this.mailSender = mailSender;
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.senderResolver = senderResolver;
        this.brevo = brevoProvider.getIfAvailable();
        this.contactService = contactService;
    }

    private boolean brevoEnabled() {
        return brevo != null && props.brevo() != null && props.brevo().enabled();
    }

    /**
     * Send with no customer-lookup hints. From-address is resolved from the
     * authenticated org (if any), else falls back to MailerProperties defaults.
     */
    @Retryable(
            retryFor = MessagingException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public DeliveryResult send(String toEmail, String toName, String subject,
                                String htmlBody, String invoiceNumber,
                                ResolvedAttachment attachment) throws MessagingException {
        return send(toEmail, toName, subject, htmlBody, invoiceNumber, attachment, null, null);
    }

    /**
     * Send with explicit customer-lookup hints. Used when the caller knows the buyer's
     * account number (= erpCustomerId) or TIN — the sender is resolved by looking up
     * that customer's owning Organisation and using its senderEmail.
     *
     * @param customerAccountNumber buyer's account number / erpCustomerId; nullable
     * @param customerTinNumber     buyer's TIN; nullable (used if accountNumber misses)
     */
    @Retryable(
            retryFor = MessagingException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public DeliveryResult send(String toEmail, String toName, String subject,
                                String htmlBody, String invoiceNumber,
                                ResolvedAttachment attachment,
                                String customerAccountNumber,
                                String customerTinNumber) throws MessagingException {
        Sender from = senderResolver.resolve(customerAccountNumber, customerTinNumber);
        try {
            rateLimiter.acquire();
            try {
                if (brevoEnabled()) {
                    return sendViaBrevo(from, toEmail, toName, subject, htmlBody, invoiceNumber, attachment);
                }
                return sendViaJavaMail(from, toEmail, toName, subject, htmlBody, invoiceNumber, attachment);
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Thread interrupted during rate-limit wait", false);
        }
    }

    /**
     * Sends with fallback — absorbs exceptions after retry exhaustion
     * and returns a Failed result instead of propagating.
     */
    public DeliveryResult sendWithFallback(String toEmail, String toName, String subject,
                                            String htmlBody, String invoiceNumber,
                                            ResolvedAttachment attachment) {
        return sendWithFallback(toEmail, toName, subject, htmlBody, invoiceNumber, attachment, null, null);
    }

    public DeliveryResult sendWithFallback(String toEmail, String toName, String subject,
                                            String htmlBody, String invoiceNumber,
                                            ResolvedAttachment attachment,
                                            String customerAccountNumber,
                                            String customerTinNumber) {
        try {
            return send(toEmail, toName, subject, htmlBody, invoiceNumber, attachment,
                    customerAccountNumber, customerTinNumber);
        } catch (MessagingException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    e.getMessage(), isRetryable(e));
        }
    }

    // ── Brevo HTTPS transactional path ──

    private DeliveryResult sendViaBrevo(Sender from, String toEmail, String toName,
                                        String subject, String htmlBody, String invoiceNumber,
                                        ResolvedAttachment attachment) throws MessagingException {
        try {
            var req = BrevoEmailClient.SendRequest.builder()
                    .sender(from.email(), from.name())
                    .to(toEmail, toName)
                    .replyTo(from.replyTo())
                    .subject(subject)
                    .html(htmlBody)
                    .header("X-Auto-Response-Suppress", "All")
                    .header("Auto-Submitted", "auto-generated")
                    .header("Precedence", "bulk");

            long attachmentSize = 0;
            if (attachment != null) {
                req.attachment(BrevoEmailClient.toAttachment(attachment));
                attachmentSize = attachment.sizeBytes();
            }
            if (invoiceNumber != null) {
                req.header("X-Invoice-Number", invoiceNumber);
                req.tag("invoice");
            }

            var ccContacts = resolveCcContacts(toEmail);
            ccContacts.forEach(c -> req.cc(c.getEmail(), c.getName()));

            String messageId = brevo.sendTransactional(req.build());
            if (ccContacts.isEmpty()) {
                log.info("✓ Invoice {} sent to {} via Brevo [msgId={}, pdf={}bytes, from={}]",
                        invoiceNumber, toEmail, messageId, attachmentSize, from.email());
            } else {
                var ccEmails = ccContacts.stream().map(Contact::getEmail).toList();
                log.info("✓ Invoice {} sent to {} (cc: {}) via Brevo [msgId={}, pdf={}bytes, from={}]",
                        invoiceNumber, toEmail, ccEmails, messageId, attachmentSize, from.email());
            }
            return new DeliveryResult.Delivered(toEmail, invoiceNumber, messageId, attachmentSize);
        } catch (IOException e) {
            log.error("✗ Brevo failure for {} (invoice {}): {}", toEmail, invoiceNumber, e.getMessage());
            // Map to MessagingException so @Retryable engages on transient errors.
            throw new MessagingException("Brevo send failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Interrupted during Brevo send", false);
        }
    }

    // ── JavaMail fallback path ──

    private DeliveryResult sendViaJavaMail(Sender from, String toEmail, String toName,
                                           String subject, String htmlBody, String invoiceNumber,
                                           ResolvedAttachment attachment) throws MessagingException {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            boolean hasAttachment = attachment != null;
            MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachment, "UTF-8");

            helper.setFrom(new InternetAddress(from.email(), from.name()));
            helper.setReplyTo(from.replyTo());

            if (toName != null && !toName.isBlank()) {
                helper.setTo(new InternetAddress(toEmail, toName));
            } else {
                helper.setTo(toEmail);
            }

            var ccContacts = resolveCcContacts(toEmail);
            if (!ccContacts.isEmpty()) {
                var ccAddresses = new InternetAddress[ccContacts.size()];
                for (int i = 0; i < ccContacts.size(); i++) {
                    var c = ccContacts.get(i);
                    ccAddresses[i] = c.getName() != null && !c.getName().isBlank()
                            ? new InternetAddress(c.getEmail(), c.getName())
                            : new InternetAddress(c.getEmail());
                }
                helper.setCc(ccAddresses);
            }

            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            long attachmentSize = 0;
            if (hasAttachment) {
                helper.addAttachment(attachment.fileName(), attachment.source(), attachment.contentType());
                attachmentSize = attachment.sizeBytes();
                log.debug("Attached PDF '{}' ({} bytes) for invoice {}",
                        attachment.fileName(), attachmentSize, invoiceNumber);
            }

            message.setHeader("X-Auto-Response-Suppress", "All");
            message.setHeader("Auto-Submitted", "auto-generated");
            message.setHeader("Precedence", "bulk");
            if (invoiceNumber != null) {
                message.setHeader("X-Invoice-Number", invoiceNumber);
            }

            mailSender.send(message);

            String messageId = message.getMessageID();
            if (ccContacts.isEmpty()) {
                log.info("✓ Invoice {} sent to {} via SMTP [msgId={}, pdf={}bytes, from={}]",
                        invoiceNumber, toEmail, messageId, attachmentSize, from.email());
            } else {
                var ccEmails = ccContacts.stream().map(Contact::getEmail).toList();
                log.info("✓ Invoice {} sent to {} (cc: {}) via SMTP [msgId={}, pdf={}bytes, from={}]",
                        invoiceNumber, toEmail, ccEmails, messageId, attachmentSize, from.email());
            }
            return new DeliveryResult.Delivered(toEmail, invoiceNumber, messageId, attachmentSize);
        } catch (MessagingException e) {
            log.error("✗ SMTP failure for {} (invoice {}): {}", toEmail, invoiceNumber, e.getMessage());
            throw e;
        } catch (UnsupportedEncodingException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Encoding error: " + e.getMessage(), false);
        }
    }

    /**
     * Finds all additional contacts for the same customer (same {@code customerId})
     * to add as CC recipients. Returns empty list if the primary email is not found
     * in the contacts table (e.g. legacy/third-party recipients).
     */
    private List<Contact> resolveCcContacts(String toEmail) {
        if (toEmail == null || toEmail.isBlank()) return List.of();
        return contactService.findByEmail(toEmail.trim().toLowerCase())
                .map(primary -> contactService.findByCustomerId(primary.getCustomerId()).stream()
                        .filter(c -> !c.getEmail().equalsIgnoreCase(toEmail.trim()))
                        .toList())
                .orElse(List.of());
    }

    private boolean isRetryable(MessagingException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection")
                || msg.contains("temporarily") || msg.contains("try again");
    }
}

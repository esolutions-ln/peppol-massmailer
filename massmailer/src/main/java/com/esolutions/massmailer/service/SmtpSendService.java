package com.esolutions.massmailer.service;

import com.esolutions.massmailer.brevo.BrevoEmailClient;
import com.esolutions.massmailer.brevo.BrevoSenderResolver;
import com.esolutions.massmailer.brevo.BrevoSenderResolver.Sender;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.customer.model.Contact;
import com.esolutions.massmailer.customer.service.ContactService;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import com.esolutions.massmailer.trail.MailSendAttempt;
import com.esolutions.massmailer.trail.MailTrailRecorder;
import com.esolutions.massmailer.trail.SendContext;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
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
 *  - Every transport attempt recorded on the email sending trail ({@link MailTrailRecorder})
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
    private final MailTrailRecorder trail;

    public SmtpSendService(JavaMailSender mailSender,
                           MailerProperties props,
                           Semaphore rateLimiter,
                           BrevoSenderResolver senderResolver,
                           ObjectProvider<BrevoEmailClient> brevoProvider,
                           ContactService contactService,
                           MailTrailRecorder trail) {
        this.mailSender = mailSender;
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.senderResolver = senderResolver;
        this.brevo = brevoProvider.getIfAvailable();
        this.contactService = contactService;
        this.trail = trail;
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
            noRetryFor = SmtpAuthenticationException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public DeliveryResult send(String toEmail, String toName, String subject,
                                String htmlBody, String invoiceNumber,
                                ResolvedAttachment attachment,
                                String customerAccountNumber,
                                String customerTinNumber) throws MessagingException {
        return send(toEmail, toName, subject, htmlBody, invoiceNumber, attachment,
                customerAccountNumber, customerTinNumber, SendContext.unspecified());
    }

    /**
     * Full-context send. {@code context} identifies the originating subsystem, org and
     * campaign for the sending trail; background callers (no authenticated org) should
     * pass the owning organisation explicitly.
     */
    @Retryable(
            retryFor = MessagingException.class,
            noRetryFor = SmtpAuthenticationException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0)
    )
    public DeliveryResult send(String toEmail, String toName, String subject,
                                String htmlBody, String invoiceNumber,
                                ResolvedAttachment attachment,
                                String customerAccountNumber,
                                String customerTinNumber,
                                SendContext context) throws MessagingException {
        Sender from = senderResolver.resolve(customerAccountNumber, customerTinNumber);
        List<Contact> ccContacts = resolveCcContacts(toEmail);
        MailTrailRecorder.Attempt attempt = beginAttempt(context, from, toEmail, ccContacts,
                subject, invoiceNumber, attachment);
        try {
            rateLimiter.acquire();
            try {
                DeliveryResult result = brevoEnabled()
                        ? sendViaBrevo(from, toEmail, toName, subject, htmlBody, invoiceNumber, attachment, customerAccountNumber, ccContacts)
                        : sendViaJavaMail(from, toEmail, toName, subject, htmlBody, invoiceNumber, attachment, ccContacts);
                trail.recordResult(attempt, result);
                return result;
            } catch (SmtpAuthenticationException e) {
                MailSendAttempt rec = trail.recordFailure(attempt, e, true, false);
                // Surface the refused IP and trail id to the API caller
                throw new SmtpAuthenticationException(e.getMessage() + diagnosticsSuffix(rec), e);
            } catch (MessagingException e) {
                trail.recordFailure(attempt, e, false, isRetryable(e));
                throw e;
            } finally {
                rateLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DeliveryResult result = new DeliveryResult.Failed(toEmail, invoiceNumber,
                    "Thread interrupted during rate-limit wait", false);
            trail.recordResult(attempt, result);
            return result;
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
        return sendWithFallback(toEmail, toName, subject, htmlBody, invoiceNumber, attachment,
                customerAccountNumber, customerTinNumber, SendContext.unspecified());
    }

    public DeliveryResult sendWithFallback(String toEmail, String toName, String subject,
                                            String htmlBody, String invoiceNumber,
                                            ResolvedAttachment attachment,
                                            String customerAccountNumber,
                                            String customerTinNumber,
                                            SendContext context) {
        try {
            return send(toEmail, toName, subject, htmlBody, invoiceNumber, attachment,
                    customerAccountNumber, customerTinNumber, context);
        } catch (SmtpAuthenticationException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber, e.getMessage(), false);
        } catch (MessagingException e) {
            return new DeliveryResult.Failed(toEmail, invoiceNumber,
                    e.getMessage(), isRetryable(e));
        }
    }

    // ── Brevo HTTPS transactional path ──

    private DeliveryResult sendViaBrevo(Sender from, String toEmail, String toName,
                                        String subject, String htmlBody, String invoiceNumber,
                                        ResolvedAttachment attachment, String accountNumber,
                                        List<Contact> ccContacts) throws MessagingException {
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

            ccContacts.forEach(c -> req.cc(c.getEmail(), c.getName()));

            if (accountNumber != null) {
                req.header("X-Customer-Account-Number", accountNumber);
            }
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
                                           ResolvedAttachment attachment,
                                           List<Contact> ccContacts) throws MessagingException {
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
        } catch (MailAuthenticationException e) {
            // Rejected credentials / unauthorised client IP — retrying won't help.
            String reason = rootCauseMessage(e);
            log.error("✗ SMTP authentication failed for {} (invoice {}): {}", toEmail, invoiceNumber, reason);
            throw new SmtpAuthenticationException("SMTP authentication failed: " + reason, e);
        } catch (MailException e) {
            // JavaMailSender throws Spring's unchecked MailException, not MessagingException.
            // Map it so @Retryable engages and sendWithFallback can absorb it.
            String reason = rootCauseMessage(e);
            log.error("✗ SMTP failure for {} (invoice {}): {}", toEmail, invoiceNumber, reason);
            throw new MessagingException("SMTP send failed: " + reason, e);
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

    private MailTrailRecorder.Attempt beginAttempt(SendContext context, Sender from, String toEmail,
                                                   List<Contact> ccContacts, String subject,
                                                   String invoiceNumber, ResolvedAttachment attachment) {
        SendContext ctx = context != null ? context : SendContext.unspecified();
        var orgId = ctx.organizationId() != null ? ctx.organizationId() : from.organizationId();
        var retryCtx = RetrySynchronizationManager.getContext();
        int attemptNumber = retryCtx == null ? 1 : retryCtx.getRetryCount() + 1;

        MailSendAttempt.Transport transport;
        String host = null;
        Integer port = null;
        if (brevoEnabled()) {
            transport = MailSendAttempt.Transport.BREVO;
            try {
                URI uri = URI.create(props.brevo().baseUrl());
                host = uri.getHost();
                port = uri.getPort() > 0 ? uri.getPort() : 443;
            } catch (IllegalArgumentException ignored) {
                // leave host unknown
            }
        } else {
            transport = MailSendAttempt.Transport.SMTP;
            if (mailSender instanceof JavaMailSenderImpl impl) {
                host = impl.getHost();
                port = impl.getPort() > 0 ? impl.getPort() : null;
            }
        }
        return trail.begin(ctx, orgId, from.email(), toEmail,
                ccContacts.stream().map(Contact::getEmail).toList(),
                subject, invoiceNumber, transport, host, port, attemptNumber,
                attachment != null ? attachment.sizeBytes() : null);
    }

    private static String diagnosticsSuffix(MailSendAttempt rec) {
        if (rec == null) return "";
        StringBuilder sb = new StringBuilder(" [");
        if (rec.egressIp() != null) sb.append("sending IP ").append(rec.egressIp()).append(", ");
        sb.append("trail ").append(rec.id()).append(']');
        return sb.toString();
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage() != null ? root.getMessage() : e.getMessage();
        return msg != null ? msg.strip() : root.getClass().getSimpleName();
    }

    private boolean isRetryable(MessagingException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection")
                || msg.contains("temporarily") || msg.contains("try again");
    }
}

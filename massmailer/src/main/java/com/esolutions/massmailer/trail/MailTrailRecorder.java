package com.esolutions.massmailer.trail;

import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.trail.MailSendAttempt.Outcome;
import com.esolutions.massmailer.trail.MailSendAttempt.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes one {@link MailSendAttempt} per transport attempt.
 *
 * Recording is best-effort: it runs in its own transaction (so a caller's rollback
 * doesn't erase the evidence, and a trail failure can't poison the caller's
 * transaction) and never throws back into the send path.
 */
@Component
public class MailTrailRecorder {

    private static final Logger log = LoggerFactory.getLogger(MailTrailRecorder.class);

    private final MailSendAttemptRepository repo;
    private final EgressIpResolver ipResolver;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public MailTrailRecorder(MailSendAttemptRepository repo,
                             EgressIpResolver ipResolver,
                             PlatformTransactionManager txManager,
                             @Value("${massmailer.trail.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.ipResolver = ipResolver;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.enabled = enabled;
    }

    /** Everything known about an attempt before the transport is invoked. */
    public record Attempt(
            UUID id,
            Instant startedAt,
            long startNanos,
            SendContext context,
            UUID organizationId,
            String senderEmail,
            String recipientEmail,
            List<String> ccEmails,
            String subject,
            String invoiceNumber,
            Transport transport,
            String mailHost,
            Integer mailPort,
            int attemptNumber,
            Long attachmentBytes
    ) {
        public long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    public Attempt begin(SendContext ctx, UUID organizationId, String senderEmail,
                         String recipientEmail, List<String> ccEmails, String subject,
                         String invoiceNumber, Transport transport, String mailHost,
                         Integer mailPort, int attemptNumber, Long attachmentBytes) {
        return new Attempt(UUID.randomUUID(), Instant.now(), System.nanoTime(),
                ctx == null ? SendContext.unspecified() : ctx, organizationId, senderEmail,
                recipientEmail, ccEmails == null ? List.of() : ccEmails, subject, invoiceNumber,
                transport, mailHost, mailPort, attemptNumber, attachmentBytes);
    }

    /** Record a transport-level outcome that came back as a {@link DeliveryResult}. */
    public MailSendAttempt recordResult(Attempt a, DeliveryResult result) {
        try {
            MailSendAttempt row = switch (result) {
                case DeliveryResult.Delivered d -> row(a, Outcome.DELIVERED, false, false, d.messageId(),
                        null, null, null, ipResolver.current(), null, d.attachmentSizeBytes());
                case DeliveryResult.Failed f -> {
                    String text = f.errorMessage();
                    boolean ipDenied = SmtpFailureClassifier.isIpDenied(text);
                    yield row(a, ipDenied ? Outcome.IP_DENIED : Outcome.FAILED, f.retryable(), ipDenied, null,
                            SmtpFailureClassifier.responseCode(text), SmtpFailureClassifier.enhancedStatus(text),
                            text, ipDenied ? ipResolver.fresh() : ipResolver.current(),
                            SmtpFailureClassifier.reportedIp(text), a.attachmentBytes());
                }
                case DeliveryResult.Skipped s -> row(a, Outcome.SKIPPED, false, false, null,
                        null, null, s.reason(), null, null, a.attachmentBytes());
            };
            return save(row);
        } catch (Exception e) {
            log.error("Failed to record mail trail for {} (invoice {}): {}",
                    a.recipientEmail(), a.invoiceNumber(), e.toString());
            return null;
        }
    }

    /**
     * Record a transport failure raised as an exception.
     *
     * @param authFailure true when the provider rejected the login (SMTP auth / Brevo 401)
     */
    public MailSendAttempt recordFailure(Attempt a, Throwable error, boolean authFailure, boolean retryable) {
        try {
            String text = SmtpFailureClassifier.chainText(error);
            Integer code = SmtpFailureClassifier.responseCode(text);
            boolean auth = authFailure || (a.transport() == Transport.BREVO && code != null && code == 401);
            boolean ipDenied = SmtpFailureClassifier.isIpDenied(text);
            Outcome outcome = ipDenied ? Outcome.IP_DENIED : auth ? Outcome.AUTH_FAILED : Outcome.FAILED;
            // On a denial, look the IP up again rather than trusting the cache — that's the IP to allow-list.
            String egress = (ipDenied || auth) ? ipResolver.fresh() : ipResolver.current();
            MailSendAttempt row = row(a, outcome, retryable && !auth && !ipDenied, ipDenied, null,
                    code, SmtpFailureClassifier.enhancedStatus(text), error.getMessage(), egress,
                    SmtpFailureClassifier.reportedIp(text), a.attachmentBytes());
            if (ipDenied) {
                log.error("✗ {} refused sending IP {} (local {}) for {} [{} {}] — allow-list this IP with the provider. Trail id {}",
                        a.mailHost(), egress != null ? egress : "<unknown>", row.localIp(), a.recipientEmail(),
                        code != null ? code : "", row.enhancedStatus() != null ? row.enhancedStatus() : "", a.id());
            }
            return save(row);
        } catch (Exception e) {
            log.error("Failed to record mail trail for {} (invoice {}): {}",
                    a.recipientEmail(), a.invoiceNumber(), e.toString());
            return null;
        }
    }

    private MailSendAttempt save(MailSendAttempt row) {
        if (!enabled) return row;
        tx.executeWithoutResult(status -> repo.insert(row));
        return row;
    }

    private MailSendAttempt row(Attempt a, Outcome outcome, boolean retryable, boolean ipDenied,
                                String messageId, Integer code, String enhanced, String error,
                                String egressIp, String reportedIp, Long attachmentBytes) {
        return new MailSendAttempt(
                a.id(), a.startedAt(), a.organizationId(),
                a.context().campaignId(), a.context().source(),
                a.invoiceNumber(), a.recipientEmail(),
                a.ccEmails().isEmpty() ? null : String.join(",", a.ccEmails()),
                a.senderEmail(), a.subject(), a.transport(), a.mailHost(), a.mailPort(),
                a.attemptNumber(), outcome, retryable, ipDenied, messageId, code, enhanced, error,
                egressIp, reportedIp, ipResolver.localAddressFor(a.mailHost(), a.mailPort()),
                attachmentBytes, a.elapsedMs());
    }
}

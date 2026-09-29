package com.esolutions.massmailer.trail;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the email sending trail — a single transport attempt for one recipient.
 *
 * IP fields, for diagnosing provider-side rejections such as
 * {@code 525 5.7.1 Unauthorized IP address}:
 *  - {@code egressIp}: public IP the mail provider sees (looked up externally;
 *    re-resolved on every auth/IP denial so it reflects the IP actually refused)
 *  - {@code serverReportedIp}: IP quoted back in the provider's rejection text, if any
 *  - {@code localIp}: this host's interface address used to route to the mail host
 *    (the container IP when running behind NAT)
 *
 * @param responseCode   SMTP reply code (e.g. 525) or Brevo HTTP status (e.g. 401)
 * @param enhancedStatus SMTP enhanced status code (e.g. 5.7.1)
 * @param durationMs     wall time for the attempt, including rate-limit wait
 */
public record MailSendAttempt(
        UUID id,
        Instant createdAt,
        UUID organizationId,
        UUID campaignId,
        SendContext.Source source,
        String invoiceNumber,
        String recipientEmail,
        String ccEmails,
        String senderEmail,
        String subject,
        Transport transport,
        String mailHost,
        Integer mailPort,
        int attemptNumber,
        Outcome outcome,
        boolean retryable,
        boolean ipDenied,
        String messageId,
        Integer responseCode,
        String enhancedStatus,
        String errorMessage,
        String egressIp,
        String serverReportedIp,
        String localIp,
        Long attachmentBytes,
        long durationMs
) {

    public enum Transport { SMTP, BREVO }

    public enum Outcome {
        DELIVERED,
        FAILED,
        /** Provider rejected the login (bad credentials, token, etc.). */
        AUTH_FAILED,
        /** Provider refused the connection/login because of the sending IP. */
        IP_DENIED,
        SKIPPED
    }
}

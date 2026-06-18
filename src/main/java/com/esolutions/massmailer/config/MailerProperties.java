package com.esolutions.massmailer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration for the mass mailer engine.
 *
 * @param fromAddress              SMTP From: address
 * @param fromName                 SMTP From: display name
 * @param rateLimit                concurrent send permits
 * @param batchSize                recipients per StructuredTaskScope batch
 * @param maxRetries               SMTP send retries per recipient
 * @param retryBackoff             initial backoff in millis between retries
 * @param fiscalValidationEnabled  enforce ZIMRA fiscal markers in PDF bytes
 * @param pdfInboxBasePath         absolute directory that file-path PDFs must reside under
 *                                 (defence-in-depth against path traversal). Blank disables file-path
 *                                 PDF resolution entirely — force callers to use Base64 mode.
 * @param maxPdfBytes              maximum size in bytes for a decoded PDF; rejects payload before decode
 */
@ConfigurationProperties(prefix = "massmailer")
public record MailerProperties(
        String fromAddress,
        String fromName,
        int rateLimit,
        int batchSize,
        int maxRetries,
        long retryBackoff,
        boolean fiscalValidationEnabled,
        String pdfInboxBasePath,
        long maxPdfBytes,
        Brevo brevo
) {
    public MailerProperties {
        if (rateLimit <= 0) rateLimit = 1000;
        if (batchSize <= 0) batchSize = 5000;
        if (maxRetries < 0) maxRetries = 3;
        if (retryBackoff <= 0) retryBackoff = 2000L;
        if (maxPdfBytes <= 0) maxPdfBytes = 10L * 1024 * 1024; // 10 MB default
        if (brevo == null) brevo = new Brevo(false, null, null, 30);
    }

    /**
     * Brevo (Sendinblue) transactional email config.
     *
     * @param enabled  switch to route outbound mail through Brevo's REST API
     * @param apiKey   Brevo account API key (env var BREVO_API_KEY)
     * @param baseUrl  Brevo API base URL — defaults to https://api.brevo.com/v3
     * @param timeoutSeconds  HTTP timeout per Brevo call
     */
    public record Brevo(
            boolean enabled,
            String apiKey,
            String baseUrl,
            int timeoutSeconds
    ) {
        public Brevo {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.brevo.com/v3";
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }
}

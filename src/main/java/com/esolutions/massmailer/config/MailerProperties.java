package com.esolutions.massmailer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration for the mass mailer engine.
 * Java 25 record — immutable, no boilerplate.
 */
@ConfigurationProperties(prefix = "massmailer")
public record MailerProperties(
        String fromAddress,
        String fromName,
        int rateLimit,
        int batchSize,
        int maxRetries,
        long retryBackoff,
        boolean fiscalValidationEnabled
) {
    public MailerProperties {
        if (rateLimit <= 0) rateLimit = 1000;
        if (batchSize <= 0) batchSize = 5000;
        if (maxRetries < 0) maxRetries = 3;
        if (retryBackoff <= 0) retryBackoff = 2000L;
    }
}

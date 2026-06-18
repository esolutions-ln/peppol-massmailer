package com.esolutions.forwarder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Forwarder configuration — all bound from environment variables / application.yml.
 *
 * Example .env:
 * <pre>
 *   FORWARDER_MAILER_URL=https://ap.invoicedirect.biz
 *   FORWARDER_MAILER_ADMIN_USER=ops@example.com
 *   FORWARDER_MAILER_ADMIN_PASS=...
 *   FORWARDER_INBOX_DIR=/var/lib/invoicedirect/inbox
 *   FORWARDER_DEFAULT_ORG_ID=d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f
 *   FORWARDER_BATCH_WINDOW_SECONDS=30
 * </pre>
 */
@ConfigurationProperties(prefix = "forwarder")
public record ForwarderProperties(
        Mailer mailer,
        String inboxDir,
        String processedDir,
        String failedDir,
        String ledgerPath,
        UUID defaultOrgId,
        int batchWindowSeconds,
        int statusPollSeconds
) {
    public ForwarderProperties {
        if (batchWindowSeconds <= 0) batchWindowSeconds = 30;
        if (statusPollSeconds <= 0) statusPollSeconds = 60;
    }

    public record Mailer(String url, String adminUser, String adminPass) {}
}

package com.esolutions.massmailer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Configuration for the PDF folder watcher service.
 *
 * <p>When enabled, a background virtual thread monitors {@code inboxDirectory}
 * for new PDF files. Each PDF must be accompanied by a companion JSON sidecar
 * file ({@code {invoiceNumber}.json}) describing the recipient, invoice metadata,
 * and ZIMRA fiscal fields. On detection both files are processed and moved to
 * {@code inboxDirectory/processed/} on success or {@code inboxDirectory/failed/}
 * on error.</p>
 *
 * <p>Set via environment variables or {@code application.yml}:</p>
 * <pre>
 *   massmailer.pdf-watcher.enabled=true
 *   massmailer.pdf-watcher.inbox-directory=/var/lib/invoicedirect/inbox
 *   massmailer.pdf-watcher.default-organization-id=&lt;orgId&gt;
 * </pre>
 */
@ConfigurationProperties(prefix = "massmailer.pdf-watcher")
public record PdfWatcherProperties(

        /**
         * Whether the PDF folder watcher is active.
         * Defaults to false — set to true to activate the background watcher thread.
         */
        boolean enabled,

        /**
         * Absolute path to the inbox directory to watch.
         * The watcher creates {@code processed/} and {@code failed/} subdirectories
         * automatically on startup.
         */
        String inboxDirectory,

        /**
         * Default organization ID used when the sidecar JSON does not specify one.
         * Required if sidecars omit {@code organizationId}.
         */
        UUID defaultOrganizationId

) {}

package com.esolutions.watcher;

import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

public record WatcherConfig(
        Path inboxDirectory,
        Path emailedDirectory,
        Path failedDirectory,
        Path ledgerFile,
        String apiBaseUrl,
        String apiKey,
        UUID organizationId,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int sidecarWaitMs,
        int pollStatusSeconds
) {
    public static WatcherConfig from(Properties props) {
        return new WatcherConfig(
                path(props, "inbox.directory", "/var/lib/invoicedirect/inbox"),
                path(props, "emailed.directory", "/var/lib/invoicedirect/emailed"),
                path(props, "failed.directory", "/var/lib/invoicedirect/failed"),
                path(props, "ledger.file", "/var/lib/invoicedirect/ledger.json"),
                str(props, "api.base.url", "https://ap.invoicedirect.biz"),
                str(props, "api.key", ""),
                uuid(props, "organization.id", null),
                intVal(props, "api.connect.timeout.seconds", 10),
                intVal(props, "api.read.timeout.seconds", 60),
                intVal(props, "sidecar.wait.ms", 3000),
                intVal(props, "poll.status.seconds", 60)
        );
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("api.key is required — set it to your organization's API key");
        }
    }

    private static Path path(Properties p, String key, String def) {
        return Path.of(str(p, key, def));
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }

    private static UUID uuid(Properties p, String key, UUID def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try { return UUID.fromString(v.trim()); } catch (IllegalArgumentException e) { return def; }
    }

    private static int intVal(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }
}

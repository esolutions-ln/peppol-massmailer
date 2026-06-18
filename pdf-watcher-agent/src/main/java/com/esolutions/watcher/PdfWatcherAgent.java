package com.esolutions.watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * InvoiceDirect PDF Watcher Agent — entry point.
 * <p>
 * Watches a folder for fiscalised invoice PDFs paired with .json sidecar
 * files (containing organisation ID, invoice metadata, recipient info).
 * Each PDF+sidecar pair is immediately forwarded as a single-invoice campaign
 * to the InvoiceDirect cloud service via REST API.
 * <p>
 * Run:
 * <pre>java -jar pdf-watcher-agent-1.0.0.jar</pre>
 * <p>
 * With custom config:
 * <pre>java -Dwatcher.config=C:\InvoiceDirect\watcher.properties -jar pdf-watcher-agent-1.0.0.jar</pre>
 * <p>
 * Runs as a Windows service (via WinSW) or scheduled task.
 */
public class PdfWatcherAgent {

    private static final Logger log = LoggerFactory.getLogger(PdfWatcherAgent.class);

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  InvoiceDirect PDF Watcher Agent v1.0.0  ");
        System.out.println("═══════════════════════════════════════════");

        WatcherConfig config = loadConfig();
        config.validate();

        log.info("Inbox    : {}", config.inboxDirectory());
        log.info("Emailed  : {}", config.emailedDirectory());
        log.info("Failed   : {}", config.failedDirectory());
        log.info("Ledger   : {}", config.ledgerFile());
        log.info("Endpoint : {}", config.apiBaseUrl());
        log.info("Org ID   : {}", config.organizationId());

        var ledger = new LedgerStore(config.ledgerFile());
        log.info("Loaded ledger — {} invoices previously sent", ledger.totalSent());

        var api = new ApiClient(config);

        if (!api.healthCheck()) {
            log.warn("Initial health check failed — remote API may be unreachable");
        } else {
            log.info("Remote API health check passed");
        }

        var watcher = new FolderWatcher(config, api, ledger);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping watcher");
            watcher.stop();
        }, "shutdown-hook"));

        log.info("Watcher is now active — Ctrl+C to stop");
        watcher.start();
    }

    static WatcherConfig loadConfig() throws IOException {
        // 1. System property override: -Dwatcher.config=/path/to/watcher.properties
        String configPath = System.getProperty("watcher.config");

        if (configPath != null && !configPath.isBlank()) {
            Path path = Path.of(configPath);
            if (Files.exists(path)) {
                try (var in = Files.newInputStream(path)) {
                    Properties props = new Properties();
                    props.load(in);
                    log.info("Config loaded from: {}", path.toAbsolutePath());
                    return WatcherConfig.from(props);
                }
            }
            log.warn("Specified config not found: {} — trying defaults", configPath);
        }

        // 2. watcher.properties in the working directory
        Path localConfig = Path.of("watcher.properties");
        if (Files.exists(localConfig)) {
            try (var in = Files.newInputStream(localConfig)) {
                Properties props = new Properties();
                props.load(in);
                log.info("Config loaded from: {}", localConfig.toAbsolutePath());
                return WatcherConfig.from(props);
            }
        }

        // 3. watcher.properties next to the JAR
        Path jarDir = findJarDir();
        if (jarDir != null) {
            Path jarConfig = jarDir.resolve("watcher.properties");
            if (Files.exists(jarConfig)) {
                try (var in = Files.newInputStream(jarConfig)) {
                    Properties props = new Properties();
                    props.load(in);
                    log.info("Config loaded from: {}", jarConfig.toAbsolutePath());
                    return WatcherConfig.from(props);
                }
            }
        }

        // 4. Bundled defaults from classpath
        try (InputStream in = PdfWatcherAgent.class.getResourceAsStream("/watcher.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                log.warn("Using bundled default config — create watcher.properties in the working directory");
                return WatcherConfig.from(props);
            }
        }

        throw new IllegalStateException(
                "No config found. Create watcher.properties next to the JAR, " +
                "or set -Dwatcher.config=C:\\path\\to\\watcher.properties");
    }

    private static Path findJarDir() {
        try {
            var code = PdfWatcherAgent.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                Path jar = Path.of(code.getLocation().toURI());
                if (jar.toString().endsWith(".jar")) {
                    return jar.getParent();
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }
}

package com.esolutions.watcher;

import com.esolutions.watcher.common.SidecarData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FolderWatcherIntegrationTest {

    @TempDir
    Path tempDir;

    private HttpServer stubApi;
    private int stubPort;
    private final CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();

    private Path inboxDir;
    private Path processedDir;
    private Path failedDir;
    private Path ledgerFile;
    private UUID orgId;
    private UUID campaignId;
    private SidecarData sidecar;
    private FolderWatcher watcher;

    @BeforeEach
    void setUp() throws IOException {
        orgId = UUID.randomUUID();
        campaignId = UUID.randomUUID();

        inboxDir = tempDir.resolve("inbox");
        processedDir = tempDir.resolve("processed");
        failedDir = tempDir.resolve("failed");
        ledgerFile = tempDir.resolve("ledger.json");

        Files.createDirectories(inboxDir);
        Files.createDirectories(processedDir);
        Files.createDirectories(failedDir);

        Map<String, Object> templateVars = Map.of("companyName", (Object) "Acme Holdings");
        Map<String, Object> emptyMerge = Map.of();

        // Minimal fiscalised sidecar
        sidecar = new SidecarData(
                orgId,
                "Test Campaign",
                "Your Invoice INV-0001",
                "invoice",
                "INV-0001",
                "buyer@acme.co.zw",
                "Alice Moyo",
                "Acme Holdings",
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                new BigDecimal("2400.00"),
                new BigDecimal("360.00"),
                "USD",
                "FD-SN-12345",
                "60",
                "10001",
                "AAAA-BBBB-1111",
                "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111",
                "VAT-001",
                "TIN-001",
                null,
                null,
                null, null, null, null, null,
                templateVars,
                emptyMerge
        );

        // Start HTTP stub server
        stubApi = HttpServer.create(new InetSocketAddress(0), 0);
        stubPort = stubApi.getAddress().getPort();

        stubApi.createContext("/actuator/health", exchange -> {
            byte[] resp = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        stubApi.createContext("/api/v1/campaigns", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);

            String resp = String.format(
                    "{\"id\":\"%s\",\"name\":\"test\",\"status\":\"QUEUED\"}", campaignId);
            byte[] respBytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();
        });

        stubApi.setExecutor(null); // single-threaded executor
        stubApi.start();

        var config = new WatcherConfig(
                inboxDir, processedDir, failedDir, ledgerFile,
                "http://localhost:" + stubPort,
                "test-api-key",
                orgId,
                5, 30, 3000, 60
        );

        var api = new ApiClient(config);
        var ledger = new LedgerStore(ledgerFile);
        watcher = new FolderWatcher(config, api, ledger);
    }

    @AfterEach
    void tearDown() {
        if (stubApi != null) stubApi.stop(0);
    }

    @Test
    void dispatchesPdfSuccessfully() throws Exception {
        var pdfPath = inboxDir.resolve("INV-0001.pdf");
        var sidecarPath = inboxDir.resolve("INV-0001.json");

        // Write test PDF — must start with %PDF- magic bytes
        Files.write(pdfPath, new byte[]{
                0x25, 0x50, 0x44, 0x46, 0x2D,  // %PDF-
                0x31, 0x2E, 0x34, 0x0A,        // 1.4\n
                0x25, 0x25, 0x45, 0x4F, 0x46    // %%EOF
        });

        // Write sidecar JSON
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.writeValue(sidecarPath.toFile(), sidecar);

        // Act
        watcher.processPdf(pdfPath);

        // Assert: PDF moved to processed
        assertFalse(Files.exists(pdfPath), "PDF should be moved from inbox");
        assertTrue(Files.exists(processedDir.resolve("INV-0001.pdf")),
                "PDF should be in processed dir");

        // Assert: sidecar moved to processed
        assertFalse(Files.exists(sidecarPath), "Sidecar should be moved from inbox");
        assertTrue(Files.exists(processedDir.resolve("INV-0001.json")),
                "Sidecar should be in processed dir");

        // Assert: API received the campaign request
        assertEquals(1, receivedBodies.size(), "API should receive exactly one POST");
        var sentJson = mapper.readTree(receivedBodies.get(0));

        assertEquals("Test Campaign", sentJson.get("name").asText());
        assertEquals("Your Invoice INV-0001", sentJson.get("subject").asText());
        assertEquals("invoice", sentJson.get("templateName").asText());
        assertEquals(orgId.toString(), sentJson.get("organizationId").asText());

        var recipients = sentJson.get("recipients");
        assertEquals(1, recipients.size());
        assertEquals("buyer@acme.co.zw", recipients.get(0).get("email").asText());
        assertEquals("INV-0001", recipients.get(0).get("invoiceNumber").asText());
        assertEquals(2400.0, recipients.get(0).get("totalAmount").asDouble(), 0.001);

        // Assert: PDF bytes sent as Base64
        assertNotNull(recipients.get(0).get("pdfBase64").asText());
        assertTrue(recipients.get(0).get("pdfBase64").asText().length() > 0);

        // Assert: ledger has the record
        var ledgerPath = ledgerFile;
        assertTrue(Files.exists(ledgerPath));
        var ledgerJson = mapper.readTree(ledgerPath.toFile());
        assertEquals(1, ledgerJson.size());
        assertEquals(campaignId.toString(), ledgerJson.get(0).get("campaignId").asText());
    }

    @Test
    void movesToFailedWhenSidecarMissing() throws Exception {
        var pdfPath = inboxDir.resolve("NO-SIDECAR.pdf");

        Files.write(pdfPath, new byte[]{
                0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A
        });

        watcher.processPdf(pdfPath);

        assertFalse(Files.exists(pdfPath), "PDF should be moved from inbox");
        assertTrue(Files.exists(failedDir.resolve("NO-SIDECAR.pdf")),
                "PDF should be in failed dir");
        assertEquals(0, receivedBodies.size(), "API should not have been called");
    }

    @Test
    void checksPdfMagicBytes() throws Exception {
        var pdfPath = inboxDir.resolve("BAD-PDF.pdf");
        var sidecarPath = inboxDir.resolve("BAD-PDF.json");

        // Write invalid PDF (doesn't start with %PDF-)
        Files.write(pdfPath, "Not a PDF file".getBytes());

        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.writeValue(sidecarPath.toFile(), sidecar);

        watcher.processPdf(pdfPath);

        assertFalse(Files.exists(pdfPath), "Invalid PDF should be moved from inbox");
        assertTrue(Files.exists(failedDir.resolve("BAD-PDF.pdf")),
                "Invalid PDF should be in failed dir");
        assertTrue(Files.exists(failedDir.resolve("BAD-PDF.json")),
                "Sidecar should be in failed dir alongside PDF");
        assertEquals(0, receivedBodies.size(), "API should not have been called");
    }

    @Test
    void skipsAlreadyDispatchedInvoice() throws Exception {
        var pdfPath = inboxDir.resolve("INV-0001.pdf");
        var sidecarPath = inboxDir.resolve("INV-0001.json");

        Files.write(pdfPath, new byte[]{
                0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A
        });

        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.writeValue(sidecarPath.toFile(), sidecar);

        // First call → successful dispatch
        watcher.processPdf(pdfPath);

        // Re-create the files (originals were moved to processed)
        Files.write(pdfPath, new byte[]{
                0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A
        });
        mapper.writeValue(sidecarPath.toFile(), sidecar);

        // Second call with same invoice → should skip due to in-session dedup
        watcher.processPdf(pdfPath);

        // PDF should still be in inbox (not moved to processed/failed on skip)
        assertTrue(Files.exists(pdfPath),
                "Duplicate PDF should remain in inbox (skipped, not processed)");
        assertEquals(1, receivedBodies.size(),
                "API should have been called only once");
    }

    @Test
    void healthCheckEndpointReachable() {
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + stubPort + "/actuator/health"))
                    .GET()
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
        } catch (Exception e) {
            fail("Health check should be reachable", e);
        }
    }
}

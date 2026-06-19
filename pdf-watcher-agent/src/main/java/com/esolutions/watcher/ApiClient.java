package com.esolutions.watcher;

import com.esolutions.watcher.common.RetryTemplate;
import com.esolutions.watcher.common.SidecarData;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);

    private final WatcherConfig config;
    private final HttpClient http;
    private final ObjectMapper json;
    private final RetryTemplate retry;

    public ApiClient(WatcherConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                .build();
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.retry = RetryTemplate.defaultTransient();
    }

    public boolean healthCheck() {
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(config.apiBaseUrl() + "/actuator/health"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    public CampaignResult createCampaign(
            UUID organizationId,
            SidecarData sidecar,
            byte[] pdfBytes,
            Path pdfPath
    ) throws Exception {
        String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
        String invoiceNumber = sidecar.effectiveInvoiceNumber(pdfPath);

        InvoiceEntry entry = new InvoiceEntry(
                sidecar.recipientEmail(),
                sidecar.recipientName() != null ? sidecar.recipientName() : "",
                invoiceNumber,
                sidecar.invoiceDate(),
                sidecar.dueDate(),
                sidecar.totalAmount(),
                sidecar.vatAmount(),
                sidecar.currency(),
                sidecar.fiscalDeviceSerialNumber(),
                sidecar.fiscalDayNumber(),
                sidecar.globalInvoiceCounter(),
                sidecar.verificationCode(),
                sidecar.qrCodeUrl(),
                base64Pdf,
                invoiceNumber + ".pdf",
                sidecar.mergeFields()
        );

        CampaignRequest body = new CampaignRequest(
                sidecar.effectiveCampaignName(),
                sidecar.effectiveSubject(),
                sidecar.effectiveTemplateName(),
                sidecar.templateVariables(),
                organizationId,
                null,
                List.of(entry)
        );

        String jsonBody = json.writeValueAsString(body);

        return retry.execute(() -> {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(config.apiBaseUrl() + "/api/v1/campaigns"))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", config.apiKey())
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body();

            if (resp.statusCode() == 202 || resp.statusCode() == 200) {
                CampaignCreated created = json.readValue(respBody, CampaignCreated.class);
                log.info("Campaign {} created (status={})", created.id(), created.status());
                return new CampaignResult(true, created.id(), null);
            }

            String msg = String.format("API returned %d: %s", resp.statusCode(), respBody);
            log.error(msg);
            return new CampaignResult(false, null, msg);
        }, "createCampaign(" + invoiceNumber + ")");
    }

    public record CampaignResult(boolean success, UUID campaignId, String error) {}

    // ── DTOs ──

    public record InvoiceEntry(
            String email,
            String name,
            String invoiceNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal totalAmount,
            BigDecimal vatAmount,
            String currency,
            String fiscalDeviceSerialNumber,
            String fiscalDayNumber,
            String globalInvoiceCounter,
            String verificationCode,
            String qrCodeUrl,
            String pdfBase64,
            String pdfFileName,
            Map<String, Object> mergeFields
    ) {}

    public record CampaignRequest(
            String name,
            String subject,
            String templateName,
            Map<String, Object> templateVariables,
            UUID organizationId,
            String callbackUrl,
            List<InvoiceEntry> recipients
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignCreated(UUID id, String name, String status) {}
}

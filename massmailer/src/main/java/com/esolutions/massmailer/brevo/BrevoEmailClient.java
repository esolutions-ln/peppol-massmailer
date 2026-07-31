package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Brevo (Sendinblue) transactional email REST client.
 *
 * Wraps the two endpoints we need:
 *   GET  /v3/account           — startup key validation (also exposes verified senders)
 *   POST /v3/smtp/email        — send a transactional email
 *
 * Docs: https://developers.brevo.com/reference/sendtransacemail
 */
@Component
@ConditionalOnProperty(prefix = "massmailer.brevo", name = "enabled", havingValue = "true")
public class BrevoEmailClient {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailClient.class);

    private final MailerProperties.Brevo cfg;
    private final HttpClient http;
    private final ObjectMapper json;

    public BrevoEmailClient(MailerProperties props, ObjectMapper json) {
        this.cfg = props.brevo();
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "massmailer.brevo.enabled=true but BREVO_API_KEY is not set");
        }
    }

    @PostConstruct
    void validateOnStartup() {
        try {
            Account account = getAccount();
            log.info("Brevo connected: {} ({} {}) plan={}",
                    account.email, account.firstName, account.lastName,
                    account.plan != null && !account.plan.isEmpty()
                            ? account.plan.get(0).type : "unknown");
        } catch (Exception e) {
            log.error("Brevo /v3/account validation failed — key may be invalid: {}", e.getMessage());
            // Do not crash the app — log and continue so the rest of the system still boots.
        }
    }

    /** Calls GET /v3/account. */
    public Account getAccount() throws IOException, InterruptedException {
        HttpRequest req = baseRequest("/account").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Brevo /account failed: " + resp.statusCode() + " " + resp.body());
        }
        return json.readValue(resp.body(), Account.class);
    }

    /**
     * Send a transactional email via POST /v3/smtp/email.
     *
     * @return the messageId returned by Brevo
     */
    public String sendTransactional(SendRequest send) throws IOException, InterruptedException {
        String body = json.writeValueAsString(send);
        HttpRequest req = baseRequest("/smtp/email")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Brevo /smtp/email failed: " + resp.statusCode() + " " + resp.body());
        }
        SendResponse parsed = json.readValue(resp.body(), SendResponse.class);
        return parsed.messageId;
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl() + path))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("accept", "application/json")
                .header("api-key", cfg.apiKey());
    }

    // ── Helpers ──

    /** Base64-encode a resolved PDF for Brevo's attachment field. */
    public static Attachment toAttachment(ResolvedAttachment a) throws IOException {
        byte[] bytes;
        try (var in = a.source().getInputStream()) {
            bytes = in.readAllBytes();
        }
        return new Attachment(Base64.getEncoder().encodeToString(bytes), a.fileName());
    }

    // ── DTOs ──

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contact(String email, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(String content, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendRequest(
            Contact sender,
            List<Contact> to,
            List<Contact> cc,
            String replyTo,
            String subject,
            String htmlContent,
            String textContent,
            List<Attachment> attachment,
            Map<String, String> headers,
            List<String> tags
    ) {
        public static class Builder {
            private Contact sender;
            private final List<Contact> to = new ArrayList<>();
            private final List<Contact> ccList = new ArrayList<>();
            private String replyTo;
            private String subject;
            private String htmlContent;
            private List<Attachment> attachments;
            private Map<String, String> headers;
            private List<String> tags;

            public Builder sender(String email, String name) { this.sender = new Contact(email, name); return this; }
            public Builder to(String email, String name) { this.to.add(new Contact(email, name)); return this; }
            public Builder cc(String email, String name) { this.ccList.add(new Contact(email, name)); return this; }
            public Builder replyTo(String email) { this.replyTo = email; return this; }
            public Builder subject(String s) { this.subject = s; return this; }
            public Builder html(String html) { this.htmlContent = html; return this; }
            public Builder attachment(Attachment a) {
                if (attachments == null) attachments = new ArrayList<>();
                attachments.add(a); return this;
            }
            public Builder header(String k, String v) {
                if (headers == null) headers = new java.util.LinkedHashMap<>();
                headers.put(k, v); return this;
            }
            public Builder tag(String t) {
                if (tags == null) tags = new ArrayList<>();
                tags.add(t); return this;
            }
            public SendRequest build() {
                return new SendRequest(sender, to, ccList.isEmpty() ? null : ccList,
                        replyTo, subject, htmlContent, null,
                        attachments, headers, tags);
            }
        }
        public static Builder builder() { return new Builder(); }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SendResponse {
        public String messageId;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Account {
        public String email;
        public String firstName;
        public String lastName;
        public String companyName;
        public List<PlanEntry> plan;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PlanEntry {
        public String type;
        public Integer credits;
        public String creditsType;
    }
}

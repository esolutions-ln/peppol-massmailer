package com.esolutions.massmailer.brevo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the POST /v3/smtp/email payload to the shape documented by Brevo:
 * sender/to/cc/replyTo are {email, name} objects, attachments are base64
 * {content, name}, and absent fields are omitted rather than sent as null.
 */
class BrevoSendRequestJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void matchesBrevoTransactionalSchema() throws Exception {
        var req = BrevoEmailClient.SendRequest.builder()
                .sender("billing@acme.test", "Acme Billing")
                .to("john@example.com", "John Doe")
                .cc("jane@example.com", null)
                .replyTo("accounts@acme.test")
                .subject("Invoice INV-1")
                .html("<p>Hello</p>")
                .attachment(new BrevoEmailClient.Attachment("JVBERi0=", "INV-1.pdf"))
                .header("X-Invoice-Number", "INV-1")
                .tag("invoice")
                .build();

        JsonNode node = json.readTree(json.writeValueAsString(req));

        assertThat(node.get("sender").get("email").asText()).isEqualTo("billing@acme.test");
        assertThat(node.get("sender").get("name").asText()).isEqualTo("Acme Billing");
        assertThat(node.get("to").get(0).get("email").asText()).isEqualTo("john@example.com");
        assertThat(node.get("cc").get(0).has("name")).isFalse();
        assertThat(node.get("replyTo").isObject()).isTrue();
        assertThat(node.get("replyTo").get("email").asText()).isEqualTo("accounts@acme.test");
        assertThat(node.get("htmlContent").asText()).isEqualTo("<p>Hello</p>");
        assertThat(node.has("textContent")).isFalse();
        assertThat(node.get("attachment").get(0).get("content").asText()).isEqualTo("JVBERi0=");
        assertThat(node.get("attachment").get(0).get("name").asText()).isEqualTo("INV-1.pdf");
        assertThat(node.get("headers").get("X-Invoice-Number").asText()).isEqualTo("INV-1");
        assertThat(node.get("tags").get(0).asText()).isEqualTo("invoice");
    }

    @Test
    void blankReplyToAndEmptyCcAreOmitted() throws Exception {
        var req = BrevoEmailClient.SendRequest.builder()
                .sender("no-reply@platform.test", null)
                .to("john@example.com", null)
                .replyTo(" ")
                .subject("s")
                .html("<p/>")
                .build();

        JsonNode node = json.readTree(json.writeValueAsString(req));

        assertThat(node.has("replyTo")).isFalse();
        assertThat(node.has("cc")).isFalse();
        assertThat(node.has("attachment")).isFalse();
        assertThat(node.get("sender").has("name")).isFalse();
    }

    @Test
    void onlyRateLimitAndServerErrorsAreRetryable() {
        assertThat(new BrevoEmailClient.BrevoApiException(429, "").retryable()).isTrue();
        assertThat(new BrevoEmailClient.BrevoApiException(503, "").retryable()).isTrue();
        assertThat(new BrevoEmailClient.BrevoApiException(400, "").retryable()).isFalse();
        assertThat(new BrevoEmailClient.BrevoApiException(401, "").retryable()).isFalse();
    }
}

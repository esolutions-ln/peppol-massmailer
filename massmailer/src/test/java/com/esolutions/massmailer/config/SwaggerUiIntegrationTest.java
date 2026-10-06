package com.esolutions.massmailer.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger is on by default (no SWAGGER_ENABLED set): the documented entry point
 * /swagger-ui.html must redirect to the UI, and the UI page and its OpenAPI spec
 * must be reachable without auth.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwaggerUiIntegrationTest {

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void swaggerUiHtmlRedirectsToUi() throws Exception {
        var resp = get("/swagger-ui.html");
        assertThat(resp.statusCode()).isEqualTo(302);
        assertThat(resp.headers().firstValue("Location")).hasValueSatisfying(
                loc -> assertThat(loc).contains("/swagger-ui/index.html"));
    }

    @Test
    void uiPageAndSpecAreServed() throws Exception {
        assertThat(get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
        var spec = get("/v3/api-docs");
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body()).contains("\"openapi\"");
    }

    @Test
    void mailerSpecDocumentsBrevoDeliveryAndWebhook() throws Exception {
        var spec = get("/v3/api-docs/mailer-pdf");
        assertThat(spec.statusCode()).isEqualTo(200);
        assertThat(spec.body())
                .contains("\"version\":\"1.1.0\"")
                .contains("Email Delivery (Brevo)")
                .contains("/webhooks/brevo/transactional")
                .contains("BrevoWebhookToken")
                .contains("Brevo Webhooks")
                .doesNotContain("smtp.gmail.com");
    }
}

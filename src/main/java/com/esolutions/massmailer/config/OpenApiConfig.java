package com.esolutions.massmailer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Centralised OpenAPI 3.0 / Swagger configuration.
 *
 * Once running, ERP integration specialists can access:
 *   - Swagger UI:  http://{host}:{port}/swagger-ui.html
 *   - OpenAPI JSON: http://{host}:{port}/v3/api-docs
 *   - OpenAPI YAML: http://{host}:{port}/v3/api-docs.yaml
 *
 * The YAML/JSON spec can be imported into Postman, Insomnia,
 * or any OpenAPI-compatible tool for testing.
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public OpenAPI massMailerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("eSolutions Fiscalised Invoice Mass Mailer API")
                        .version("1.0.0")
                        .description("""
                                Production-grade REST API for mass-mailing fiscalised invoice PDFs \
                                to customers via a no-reply email address.

                                ## Multi-ERP Integration

                                This service supports multiple ERP systems through a Hexagonal Architecture \
                                adapter pattern. Each ERP has a dedicated adapter that normalises its \
                                invoice model into a canonical format:

                                | ERP | Adapter | PDF Source |
                                |---|---|---|
                                | **Sage Intacct** | XML API → ARINVOICE | Export directory |
                                | **QuickBooks Online** | REST API → Invoice | `/invoice/{id}/pdf` endpoint |
                                | **Dynamics 365 F&O** | OData → SalesInvoice | Document Mgmt / export dir |
                                | **Generic (Direct)** | No ERP fetch | Caller provides PDF in payload |

                                ## Two Integration Modes

                                **Mode 1 — ERP-Driven** (`POST /api/v1/erp/dispatch`): \
                                Pass the ERP source + invoice IDs. The Mass Mailer fetches invoice \
                                data and PDFs from the ERP, then dispatches automatically.

                                **Mode 2 — Direct Payload** (`POST /api/v1/campaigns` or `/api/v1/mail/invoice`): \
                                The calling system provides complete invoice data + PDF (file path or Base64) \
                                in the request body. No ERP API call is made.

                                ## Fiscal Compliance (ZIMRA)

                                The email template includes fiscal device serial number, fiscal day number, \
                                global invoice counter, verification code, and QR code URL fields — all \
                                rendered in the email body for Zimbabwe Revenue Authority compliance.
                                """)
                        .contact(new Contact()
                                .name("eSolutions Integration Team")
                                .email("integration@esolutions.co.zw")
                                .url("https://esolutions.co.zw"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://esolutions.co.zw/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local Development"),
                        new Server()
                                .url("https://ap.invoicedirect.biz")
                                .description("Production (InvoiceDirect)")))
                .tags(List.of(
                        new Tag()
                                .name("Invoice Campaigns")
                                .description("""
                                        Batch operations — create a campaign with multiple invoice \
                                        recipients, each carrying their own PDF attachment. The campaign \
                                        dispatches asynchronously and can be polled for status."""),
                        new Tag()
                                .name("Single Invoice Email")
                                .description("""
                                        Send a single fiscalised invoice email synchronously. \
                                        Ideal for real-time dispatch from POS / ERP on invoice creation, \
                                        or for re-sending a specific invoice to a customer."""),
                        new Tag()
                                .name("ERP-Driven Invoice Dispatch")
                                .description("""
                                        Fetch invoices directly from a configured ERP system (Sage Intacct, \
                                        QuickBooks Online, or Dynamics 365), then dispatch as an email campaign. \
                                        The Mass Mailer handles invoice data retrieval, PDF download, \
                                        and email dispatch — the caller only provides ERP source + invoice IDs. \
                                        Check GET /api/v1/erp/adapters to see which ERPs are active."""),
                        new Tag()
                                .name("Organization Registry")
                                .description("""
                                        Manage sender organizations (tenants) in the delivery network. \
                                        Each org has its own no-reply sender identity, ERP connection, \
                                        and billing relationship. In PEPPOL terms, these are the "senders" \
                                        whose invoices flow through our access point."""),
                        new Tag()
                                .name("Billing & Metering")
                                .description("""
                                        Per-invoice billing with tiered rate profiles. Every invoice delivered \
                                        through the channel is metered. Create rate profiles with volume-based \
                                        tiers, view monthly billing summaries, audit individual usage records, \
                                        and estimate costs. Billing periods are monthly (YYYY-MM format)."""),
                        new Tag()
                                .name("Health & Monitoring")
                                .description("Actuator health, metrics, and ERP adapter health check endpoints."),
                        new Tag()
                                .name("My Invoice Dashboard")
                                .description("""
                                        Authenticated organisation dashboard — requires X-API-Key header. \
                                        View your own campaigns, invoice delivery status, and billing. \
                                        Every endpoint is scoped to your organisation only.""")))
                .externalDocs(new ExternalDocumentation()
                        .description("Mass Mailer Integration Guide")
                        .url("https://docs.invoicedirect.biz/mass-mailer"))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Your organisation API key — issued at registration via POST /api/v1/organizations")));
    }
}

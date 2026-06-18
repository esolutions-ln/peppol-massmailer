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
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Centralised OpenAPI 3.0 / Swagger configuration.
 *
 * Once running, ERP integration specialists can access:
 *   - Swagger UI:  http://{host}:{port}/swagger-ui.html
 *   - OpenAPI JSON (all):     http://{host}:{port}/v3/api-docs
 *   - OpenAPI JSON (Mailer):  http://{host}:{port}/v3/api-docs/mailer-pdf
 *   - OpenAPI JSON (PEPPOL):  http://{host}:{port}/v3/api-docs/peppol
 *
 * <h3>API Groups</h3>
 * The Swagger UI displays a "Select a definition" dropdown at the top to switch
 * between the two product surfaces:
 *
 *   <b>mailer-pdf</b> — Fiscalised invoice PDF email channel. Campaigns,
 *   single sends, ERP-driven dispatch, organisations, customers, billing, admin.
 *
 *   <b>peppol</b> — PEPPOL 4-corner e-invoice network. AS4 inbound (C3),
 *   eRegistry (SMP), participant routing, delivery dashboard, invitations,
 *   and Sage Network webhooks.
 *
 * The YAML/JSON spec can be imported into Postman, Insomnia,
 * or any OpenAPI-compatible tool for testing.
 */
@Configuration
public class OpenApiConfig {

    // ── Controller package roots used to split the two groups ──

    private static final String[] MAILER_PDF_PACKAGES = {
            "com.esolutions.massmailer.controller",                    // Campaigns, SingleMail, ErpCampaign, OrgInvoiceDashboard
            "com.esolutions.massmailer.organization.controller",       // Organization Registry
            "com.esolutions.massmailer.customer.controller",           // Customer Registry
            "com.esolutions.massmailer.billing.controller",            // Billing & Metering
            "com.esolutions.massmailer.security"                        // Admin Auth + Admin Users
    };

    private static final String[] PEPPOL_PACKAGES = {
            "com.esolutions.massmailer.peppol.controller",             // AS4 Receive, eRegistry, Delivery Dashboard
            "com.esolutions.massmailer.invitation.controller",         // PEPPOL Invitations
            "com.esolutions.massmailer.infrastructure.adapters.sage"   // Sage Network e-invoice webhooks
    };

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
                        // ── Mailer (PDF) channel ──
                        new Tag()
                                .name("Invoice Campaigns")
                                .description("""
                                        **[Mailer / PDF]** Batch operations — create a campaign with multiple invoice \
                                        recipients, each carrying their own PDF attachment. The campaign \
                                        dispatches asynchronously and can be polled for status."""),
                        new Tag()
                                .name("Single Invoice Email")
                                .description("""
                                        **[Mailer / PDF]** Send a single fiscalised invoice email synchronously. \
                                        Ideal for real-time dispatch from POS / ERP on invoice creation, \
                                        or for re-sending a specific invoice to a customer."""),
                        new Tag()
                                .name("ERP-Driven Invoice Dispatch")
                                .description("""
                                        **[Mailer / PDF]** Fetch invoices directly from a configured ERP system \
                                        (Sage Intacct, QuickBooks Online, or Dynamics 365), then dispatch as an \
                                        email campaign. The Mass Mailer handles invoice data retrieval, PDF download, \
                                        and email dispatch — the caller only provides ERP source + invoice IDs. \
                                        Check GET /api/v1/erp/adapters to see which ERPs are active."""),
                        new Tag()
                                .name("My Invoice Dashboard")
                                .description("""
                                        **[Mailer / PDF]** Authenticated organisation dashboard — requires X-API-Key header. \
                                        View your own campaigns, invoice delivery status, and billing. \
                                        Every endpoint is scoped to your organisation only."""),
                        new Tag()
                                .name("Organization Registry")
                                .description("""
                                        Manage sender organizations (tenants) in the delivery network. \
                                        Each org has its own no-reply sender identity, ERP connection, \
                                        and billing relationship. In PEPPOL terms, these are the "senders" \
                                        whose invoices flow through our access point."""),
                        new Tag()
                                .name("Customer Registry")
                                .description("""
                                        Per-organisation customer contact list — the recipient directory used \
                                        by both the PDF mailer (for delivery addresses) and PEPPOL routing \
                                        (linked to participant IDs in the eRegistry)."""),
                        new Tag()
                                .name("Billing & Metering")
                                .description("""
                                        Per-invoice billing with tiered rate profiles. Every invoice delivered \
                                        through the channel is metered. Create rate profiles with volume-based \
                                        tiers, view monthly billing summaries, audit individual usage records, \
                                        and estimate costs. Billing periods are monthly (YYYY-MM format)."""),
                        new Tag()
                                .name("Admin Auth")
                                .description("Platform-admin session login / logout. Issues bearer tokens for the admin UI."),
                        new Tag()
                                .name("Admin Users")
                                .description("Platform-admin user management — create, list, and disable admin accounts."),

                        // ── PEPPOL e-invoice network ──
                        new Tag()
                                .name("PEPPOL Inbound (C3 Receive)")
                                .description("""
                                        **[PEPPOL]** AS4 inbound endpoint — Corner 3 (C3) of the 4-corner model. \
                                        Receives PEPPOL BIS 3.0 UBL invoices from peer Access Points, persists \
                                        proof of receipt, and queues onward delivery to the buyer's ERP (C4)."""),
                        new Tag()
                                .name("eRegistry — PEPPOL Access Points")
                                .description("""
                                        **[PEPPOL]** Local equivalent of the PEPPOL SMP (Service Metadata Publisher). \
                                        Register Access Points (sender / receiver / gateway) and link customer \
                                        contacts to their participant IDs and preferred delivery channel \
                                        (PEPPOL UBL or PDF email)."""),
                        new Tag()
                                .name("PEPPOL Delivery Dashboard")
                                .description("""
                                        **[PEPPOL]** Per-organisation delivery statistics — aggregate counts, \
                                        success rate, 30-day trend, failed delivery audit, and manual retry."""),
                        new Tag()
                                .name("PEPPOL Invitations")
                                .description("""
                                        **[PEPPOL]** Invite a customer to onboard onto the PEPPOL network. \
                                        Generates a tokenised invitation URL that a recipient can use to \
                                        self-register their participant ID and preferred delivery channel."""),
                        new Tag()
                                .name("Sage Network Webhooks")
                                .description("""
                                        **[PEPPOL]** Inbound webhook for Sage Network e-invoice status push \
                                        notifications. Sage signs payloads with HMAC-SHA256 and posts to \
                                        `/webhooks/sage/einvoice-status/{orgId}` on every status transition."""),

                        // ── Shared / operational ──
                        new Tag()
                                .name("Health & Monitoring")
                                .description("Actuator health, metrics, and ERP adapter health check endpoints.")))
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

    // ═══════════════════════════════════════════════════════════════
    //  Grouped OpenAPI surfaces — populates the dropdown in Swagger UI
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mailer (PDF) channel — fiscalised invoice PDF email endpoints.
     *
     * Includes: invoice campaigns, single-mail, ERP-driven dispatch, the per-org
     * invoice dashboard, organisations, customer contacts, billing, and admin.
     *
     * Spec: GET /v3/api-docs/mailer-pdf
     */
    @Bean
    public GroupedOpenApi mailerPdfApi() {
        return GroupedOpenApi.builder()
                .group("mailer-pdf")
                .displayName("Mailer (PDF)")
                .packagesToScan(MAILER_PDF_PACKAGES)
                .build();
    }

    /**
     * PEPPOL channel — e-invoice network endpoints (4-corner model, AS4, eRegistry).
     *
     * Includes: AS4 inbound (C3), eRegistry/SMP, participant routing, delivery
     * dashboard, PEPPOL invitations, and Sage Network webhooks.
     *
     * Spec: GET /v3/api-docs/peppol
     */
    @Bean
    public GroupedOpenApi peppolApi() {
        return GroupedOpenApi.builder()
                .group("peppol")
                .displayName("PEPPOL")
                .packagesToScan(PEPPOL_PACKAGES)
                .build();
    }

    /**
     * Combined view — every documented endpoint in one spec.
     *
     * Spec: GET /v3/api-docs/all
     */
    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("all")
                .displayName("All endpoints")
                .pathsToMatch("/api/**", "/peppol/**", "/webhooks/**")
                .build();
    }
}

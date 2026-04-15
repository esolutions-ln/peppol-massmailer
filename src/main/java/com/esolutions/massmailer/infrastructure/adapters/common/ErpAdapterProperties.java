package com.esolutions.massmailer.infrastructure.adapters.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration for all ERP adapters — loaded from application.yml.
 *
 * <p>Each ERP has its own subtree of properties:
 * <pre>
 * erp:
 *   sage:
 *     base-url: https://api.intacct.com/ia/xml/xmlgw.phtml
 *     sender-id: YOUR_SENDER_ID
 *     sender-password: YOUR_PASSWORD
 *     company-id: YOUR_COMPANY
 *     invoice-pdf-dir: /var/lib/sage/invoices
 *   quickbooks:
 *     base-url: https://quickbooks.api.intuit.com/v3
 *     client-id: YOUR_CLIENT_ID
 *     client-secret: YOUR_SECRET
 *     realm-id: YOUR_REALM
 *   dynamics365:
 *     base-url: https://YOUR_ORG.api.crm.dynamics.com/api/data/v9.2
 *     tenant-id: YOUR_TENANT
 *     client-id: YOUR_CLIENT_ID
 *     client-secret: YOUR_SECRET
 *     invoice-pdf-dir: /var/lib/d365/invoices
 * </pre>
 */
@ConfigurationProperties(prefix = "erp")
public record ErpAdapterProperties(
        SageConfig sage,
        QuickBooksConfig quickbooks,
        Dynamics365Config dynamics365,
        OdooConfig odoo
) {

    public record SageConfig(
            String baseUrl,
            String senderId,
            String senderPassword,
            String companyId,
            String userId,
            String userPassword,
            /** Directory where Sage exports fiscalised PDFs */
            String invoicePdfDir,
            /** Sage Network e-Invoice API base URL (e.g. https://api.sageone.com/network) */
            String networkBaseUrl,
            /** Bearer token / API key for Sage Network e-Invoice API */
            String networkApiKey,
            int timeoutSeconds
    ) {
        public SageConfig {
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }

    public record QuickBooksConfig(
            String baseUrl,
            String clientId,
            String clientSecret,
            String realmId,
            String refreshToken,
            /** QB invoice PDF download requires a separate API call */
            int timeoutSeconds
    ) {
        public QuickBooksConfig {
            if (baseUrl == null || baseUrl.isBlank())
                baseUrl = "https://quickbooks.api.intuit.com/v3";
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }

    public record Dynamics365Config(
            String baseUrl,
            String tenantId,
            String clientId,
            String clientSecret,
            /** D365 can store invoice PDFs in SharePoint or Blob — this is the local mount */
            String invoicePdfDir,
            int timeoutSeconds
    ) {
        public Dynamics365Config {
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }

    /**
     * Odoo JSON-RPC / REST API configuration.
     *
     * Odoo exposes invoices via two APIs:
     *   - JSON-RPC (all versions): POST /web/dataset/call_kw
     *   - REST API (Odoo 17+):     GET /api/account.move/{id}
     *
     * Authentication:
     *   - API Key (Odoo 14+): set in X-Odoo-JSONRPC-Session or Authorization header
     *   - Username/password session: POST /web/session/authenticate
     *
     * PDF download:
     *   - GET /report/pdf/account.report_invoice/{id}  (returns PDF bytes)
     */
    public record OdooConfig(
            /** Base URL of the Odoo instance, e.g. https://mycompany.odoo.com */
            String baseUrl,
            /** Odoo database name */
            String database,
            /** Odoo user login (email) */
            String username,
            /** Odoo API key (preferred) or password */
            String apiKey,
            /** Odoo numeric user ID — resolved at startup via authenticate call */
            Integer uid,
            int timeoutSeconds
    ) {
        public OdooConfig {
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }
}

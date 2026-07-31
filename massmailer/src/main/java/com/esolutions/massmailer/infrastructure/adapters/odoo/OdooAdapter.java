package com.esolutions.massmailer.infrastructure.adapters.odoo;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.*;
import com.esolutions.massmailer.domain.ports.ErpIntegrationException;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Odoo ERP adapter — Anti-Corruption Layer implementation.
 *
 * <h3>ACL Responsibilities</h3>
 * <ul>
 *   <li>Translates Odoo {@code account.move} records → {@link CanonicalInvoice}</li>
 *   <li>Maps Odoo field names:
 *       name → invoiceNumber, invoice_date → invoiceDate,
 *       invoice_date_due → dueDate, amount_total → totalAmount,
 *       amount_tax → vatAmount, currency_id → currency,
 *       partner_id → recipientCompany, invoice_partner_email → recipientEmail</li>
 *   <li>Downloads invoice PDFs via Odoo's report endpoint</li>
 *   <li>Handles Odoo JSON-RPC authentication (API key or session)</li>
 * </ul>
 *
 * <h3>Odoo Invoice PDF Flow</h3>
 * <pre>
 * GET {baseUrl}/report/pdf/account.report_invoice/{id}
 * Cookie: session_id={session}   (or)
 * Authorization: Bearer {api_key}
 * </pre>
 * Returns raw PDF bytes which are Base64-encoded for the canonical model.
 *
 * <h3>Odoo JSON-RPC Invoice Fetch</h3>
 * <pre>
 * POST {baseUrl}/web/dataset/call_kw
 * {
 *   "jsonrpc": "2.0", "method": "call",
 *   "params": {
 *     "model": "account.move",
 *     "method": "search_read",
 *     "args": [[["name", "in", ["INV/2026/0001"]]]],
 *     "kwargs": {
 *       "fields": ["name","invoice_date","invoice_date_due","amount_total",
 *                  "amount_tax","currency_id","partner_id","invoice_partner_email",
 *                  "state","move_type"],
 *       "limit": 100
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>Activated only when {@code erp.odoo.base-url} is configured.</p>
 */
@Component
@ConditionalOnProperty(prefix = "erp.odoo", name = "base-url", matchIfMissing = false)
public class OdooAdapter implements ErpInvoicePort {

    private static final Logger log = LoggerFactory.getLogger(OdooAdapter.class);
    private static final DateTimeFormatter ODOO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ErpAdapterProperties.OdooConfig config;
    private final RestTemplate restTemplate;

    public OdooAdapter(ErpAdapterProperties props, RestTemplate restTemplate) {
        this.config = props.odoo();
        this.restTemplate = restTemplate;
        log.info("Odoo adapter initialised — baseUrl={}, database={}", config.baseUrl(), config.database());
    }

    @Override
    public ErpSource supports() {
        return ErpSource.ODOO;
    }

    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        log.debug("Fetching {} invoices from Odoo [db={}]", invoiceIds.size(), tenantId);

        // ── Build JSON-RPC search_read request ──
        // tenantId is used as the Odoo database name when multi-tenant
        String database = (tenantId != null && !tenantId.isBlank()) ? tenantId : config.database();

        var body = buildSearchReadRequest(database, invoiceIds);
        var headers = buildHeaders();
        var entity = new HttpEntity<>(body, headers);

        try {
            var response = restTemplate.exchange(
                    config.baseUrl() + "/web/dataset/call_kw",
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            return parseInvoiceResponse(response.getBody(), database);

        } catch (Exception e) {
            log.error("Odoo API call failed: {}", e.getMessage());
            throw new ErpIntegrationException(ErpSource.ODOO,
                    "Failed to fetch invoices from Odoo: " + e.getMessage(), e);
        }
    }

    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        // ── Download PDF from Odoo report endpoint ──
        // invoiceId here is the Odoo numeric record ID
        String database = (tenantId != null && !tenantId.isBlank()) ? tenantId : config.database();

        String pdfUrl = config.baseUrl() + "/report/pdf/account.report_invoice/" + invoiceId;
        log.debug("Downloading Odoo invoice PDF: {}", pdfUrl);

        var headers = buildHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_PDF));
        var entity = new HttpEntity<>(headers);

        try {
            var response = restTemplate.exchange(pdfUrl, HttpMethod.GET, entity, byte[].class);
            if (response.getBody() == null || response.getBody().length == 0) {
                throw new ErpIntegrationException(ErpSource.ODOO,
                        "Empty PDF response for Odoo invoice " + invoiceId);
            }
            log.debug("Downloaded Odoo PDF for invoice {} ({} bytes)", invoiceId, response.getBody().length);
            return Base64.getEncoder().encodeToString(response.getBody());

        } catch (ErpIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new ErpIntegrationException(ErpSource.ODOO,
                    "Failed to download PDF for Odoo invoice " + invoiceId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean healthCheck(String tenantId) {
        // ── Call Odoo version endpoint — no auth required ──
        try {
            var response = restTemplate.getForEntity(
                    config.baseUrl() + "/web/webclient/version_info", String.class);
            boolean up = response.getStatusCode().is2xxSuccessful();
            log.debug("Odoo health check: {}", up ? "UP" : "DOWN");
            return up;
        } catch (Exception e) {
            log.warn("Odoo health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── JSON-RPC request builder ──

    private Map<String, Object> buildSearchReadRequest(String database, List<String> invoiceNames) {
        // Domain filter: name IN ['INV/2026/0001', ...] AND move_type = 'out_invoice' AND state = 'posted'
        var domain = List.of(
                List.of("name", "in", invoiceNames),
                List.of("move_type", "=", "out_invoice"),
                List.of("state", "=", "posted")
        );

        var kwargs = new LinkedHashMap<String, Object>();
        kwargs.put("fields", List.of(
                "id", "name", "invoice_date", "invoice_date_due",
                "amount_untaxed", "amount_tax", "amount_total",
                "currency_id", "partner_id",
                "invoice_partner_email", "invoice_partner_display_name",
                "state", "move_type", "ref"
        ));
        kwargs.put("limit", 200);

        var params = new LinkedHashMap<String, Object>();
        params.put("model", "account.move");
        params.put("method", "search_read");
        params.put("args", List.of(domain));
        params.put("kwargs", kwargs);

        // Include database and auth context
        if (database != null) params.put("db", database);

        return Map.of(
                "jsonrpc", "2.0",
                "method", "call",
                "id", 1,
                "params", params
        );
    }

    private HttpHeaders buildHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Odoo 14+ API key authentication
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            headers.set("Authorization", "Bearer " + config.apiKey());
        }

        return headers;
    }

    // ── ACL: Odoo account.move → CanonicalInvoice ──

    @SuppressWarnings("unchecked")
    private List<CanonicalInvoice> parseInvoiceResponse(Map<String, Object> body, String database) {
        if (body == null) {
            throw new ErpIntegrationException(ErpSource.ODOO, "Null response from Odoo API");
        }

        // Check for JSON-RPC error
        if (body.containsKey("error")) {
            var error = (Map<String, Object>) body.get("error");
            throw new ErpIntegrationException(ErpSource.ODOO,
                    "Odoo API error: " + error.get("message"));
        }

        var result = body.get("result");
        if (!(result instanceof List)) {
            throw new ErpIntegrationException(ErpSource.ODOO,
                    "Unexpected Odoo response format — expected list of records");
        }

        var records = (List<Map<String, Object>>) result;
        log.debug("Odoo returned {} invoice records", records.size());

        return records.stream()
                .map(r -> mapOdooInvoice(r, database))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private CanonicalInvoice mapOdooInvoice(Map<String, Object> r, String database) {
        // ── Extract fields with safe casting ──
        String erpId       = String.valueOf(r.getOrDefault("id", ""));
        String invoiceNum  = str(r.get("name"));
        String invoiceDate = str(r.get("invoice_date"));
        String dueDate     = str(r.get("invoice_date_due"));

        // Financial amounts
        BigDecimal subtotal = decimal(r.get("amount_untaxed"));
        BigDecimal vat      = decimal(r.get("amount_tax"));
        BigDecimal total    = decimal(r.get("amount_total"));

        // Currency — Odoo returns [id, "USD"] tuple
        String currency = "USD";
        if (r.get("currency_id") instanceof List<?> currencyTuple && currencyTuple.size() >= 2) {
            currency = String.valueOf(currencyTuple.get(1));
        }

        // Partner (customer) — Odoo returns [id, "Company Name"] tuple
        String recipientCompany = null;
        if (r.get("partner_id") instanceof List<?> partnerTuple && partnerTuple.size() >= 2) {
            recipientCompany = String.valueOf(partnerTuple.get(1));
        }

        // Email — from invoice_partner_email or partner contact
        String recipientEmail = str(r.get("invoice_partner_email"));
        String recipientName  = str(r.get("invoice_partner_display_name"));

        // PDF — resolved via Odoo report endpoint using the numeric record ID
        var pdfSource = new PdfSource(
                null, null,
                config.baseUrl() + "/report/pdf/account.report_invoice/" + erpId,
                (invoiceNum != null ? invoiceNum.replace("/", "-") : erpId) + ".pdf"
        );

        return new CanonicalInvoice(
                ErpSource.ODOO,
                database,
                erpId,
                recipientEmail,
                recipientName,
                recipientCompany,
                invoiceNum,
                parseDate(invoiceDate),
                parseDate(dueDate),
                subtotal,
                vat,
                total,
                currency,
                FiscalMetadata.EMPTY, // Fiscal data comes from ZIMRA device, not Odoo
                pdfSource,
                Map.of("odooRef", r.getOrDefault("ref", ""))
        );
    }

    // ── Helpers ──

    private String str(Object val) {
        if (val == null || Boolean.FALSE.equals(val)) return null;
        return val.toString().trim();
    }

    private BigDecimal decimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        try { return new BigDecimal(val.toString()); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private LocalDate parseDate(String val) {
        if (val == null || val.isBlank()) return null;
        try { return LocalDate.parse(val, ODOO_DATE); }
        catch (Exception e) { return null; }
    }
}

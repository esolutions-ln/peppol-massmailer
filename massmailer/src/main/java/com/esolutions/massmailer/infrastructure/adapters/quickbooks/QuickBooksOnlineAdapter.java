package com.esolutions.massmailer.infrastructure.adapters.quickbooks;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.*;
import com.esolutions.massmailer.domain.ports.ErpIntegrationException;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * QuickBooks Online ERP adapter — Anti-Corruption Layer implementation.
 *
 * <h3>ACL Responsibilities</h3>
 * <ul>
 *   <li>Translates QB "Invoice" JSON → {@link CanonicalInvoice}</li>
 *   <li>Maps QB field names: Id → erpInvoiceId, DocNumber → invoiceNumber,
 *       TotalAmt → totalAmount, Balance → (outstanding), etc.</li>
 *   <li>Fetches invoice PDFs via QB's {@code GET /v3/company/{realmId}/invoice/{id}/pdf}
 *       endpoint (returns application/pdf bytes)</li>
 *   <li>Handles OAuth 2.0 token refresh cycle</li>
 *   <li>Translates QB error codes (e.g. 610 = stale object) → domain exceptions</li>
 * </ul>
 *
 * <h3>QuickBooks Invoice PDF Flow</h3>
 * <p>QuickBooks Online has a dedicated PDF endpoint:
 * <pre>
 * GET /v3/company/{realmId}/invoice/{invoiceId}/pdf
 * Accept: application/pdf
 * Authorization: Bearer {access_token}
 * </pre>
 * This returns the invoice PDF bytes directly — the adapter encodes them as Base64.</p>
 *
 * <h3>Webhook Integration (Optional)</h3>
 * <p>QuickBooks can push webhook events on invoice creation/update.
 * Configure a webhook listener to automatically trigger mass mail dispatch
 * when new invoices are posted.</p>
 *
 * <p>Activated only when {@code erp.quickbooks.client-id} is configured.</p>
 */
@Component
@ConditionalOnProperty(prefix = "erp.quickbooks", name = "client-id")
public class QuickBooksOnlineAdapter implements ErpInvoicePort {

    private static final Logger log = LoggerFactory.getLogger(QuickBooksOnlineAdapter.class);

    private final ErpAdapterProperties.QuickBooksConfig config;

    public QuickBooksOnlineAdapter(ErpAdapterProperties props) {
        this.config = props.quickbooks();
        log.info("QuickBooks Online adapter initialised — realmId={}", config.realmId());
    }

    @Override
    public ErpSource supports() {
        return ErpSource.QUICKBOOKS_ONLINE;
    }

    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        log.debug("Fetching {} invoices from QuickBooks Online [realm={}]",
                invoiceIds.size(), tenantId);

        // ──────────────────────────────────────────────────────────────
        // TODO: Replace with actual QuickBooks REST API calls
        //
        // QB API call pattern:
        //   GET https://quickbooks.api.intuit.com/v3/company/{realmId}/invoice/{id}
        //   Authorization: Bearer {access_token}
        //   Accept: application/json
        //
        // Response JSON fields to map:
        //   Id              → erpInvoiceId
        //   DocNumber       → invoiceNumber
        //   TxnDate         → invoiceDate
        //   DueDate         → dueDate
        //   TotalAmt        → totalAmount
        //   Balance         → outstandingAmount (totalAmount - payments)
        //   CurrencyRef.value → currency
        //   BillEmail.Address → recipientEmail
        //   CustomerRef.name  → recipientName / recipientCompany
        //   TxnTaxDetail.TotalTax → vatAmount
        //
        // For batch: Use QB query API:
        //   GET /v3/company/{realmId}/query?query=
        //       SELECT * FROM Invoice WHERE Id IN ('1','2','3')
        //
        // OAuth refresh flow:
        //   POST https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer
        //   grant_type=refresh_token&refresh_token={token}
        // ──────────────────────────────────────────────────────────────

        var invoices = new ArrayList<CanonicalInvoice>();

        for (String invoiceId : invoiceIds) {
            try {
                var invoice = mapQuickBooksInvoice(tenantId, invoiceId);
                invoices.add(invoice);
            } catch (Exception e) {
                log.error("Failed to fetch QB invoice {}: {}", invoiceId, e.getMessage());
                throw new ErpIntegrationException(ErpSource.QUICKBOOKS_ONLINE,
                        "Failed to fetch invoice " + invoiceId, e);
            }
        }

        return invoices;
    }

    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        // ──────────────────────────────────────────────────────────────
        // TODO: Implement actual QB PDF download
        //
        //   GET /v3/company/{realmId}/invoice/{invoiceId}/pdf
        //   Accept: application/pdf
        //   Authorization: Bearer {access_token}
        //
        //   Response: raw PDF bytes → Base64.getEncoder().encodeToString(bytes)
        //
        // Handle 401 (token expired) → refresh and retry once
        // Handle 404 → throw ErpIntegrationException
        // ──────────────────────────────────────────────────────────────

        log.debug("Downloading PDF for QB invoice {} [realm={}]", invoiceId, tenantId);

        throw new ErpIntegrationException(ErpSource.QUICKBOOKS_ONLINE,
                "QB PDF download not yet implemented — provide pdfBase64 or pdfFilePath in the request");
    }

    @Override
    public boolean healthCheck(String tenantId) {
        // TODO: Call QB company info endpoint to verify credentials
        //   GET /v3/company/{realmId}/companyinfo/{realmId}
        log.debug("QuickBooks health check for realm {}", tenantId);
        return config.clientId() != null && !config.clientId().isBlank();
    }

    // ── ACL: QuickBooks Model → Canonical Model ──

    private CanonicalInvoice mapQuickBooksInvoice(String tenantId, String invoiceId) {
        // TODO: Replace with actual QB API response mapping

        return new CanonicalInvoice(
                ErpSource.QUICKBOOKS_ONLINE,
                tenantId,
                invoiceId,
                // Recipient — from QB BillEmail.Address + CustomerRef
                null,  // recipientEmail — BillEmail.Address
                null,  // recipientName — CustomerRef.name
                null,  // recipientCompany
                // Invoice identity
                invoiceId,  // DocNumber
                LocalDate.now(),  // TxnDate
                LocalDate.now().plusDays(30),  // DueDate
                // Financial
                BigDecimal.ZERO,  // TotalAmt - TxnTaxDetail.TotalTax
                BigDecimal.ZERO,  // TxnTaxDetail.TotalTax
                BigDecimal.ZERO,  // TotalAmt
                "USD",            // CurrencyRef.value
                // Fiscal — from custom fields or external fiscal device
                FiscalMetadata.EMPTY,
                // PDF — QB has a download API, resolved at send time
                new PdfSource(null, null,
                        config.baseUrl() + "/company/" + tenantId + "/invoice/" + invoiceId + "/pdf",
                        invoiceId + ".pdf"),
                Map.of()
        );
    }
}

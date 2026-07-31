package com.esolutions.massmailer.infrastructure.adapters.dynamics365;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.*;
import com.esolutions.massmailer.domain.ports.ErpIntegrationException;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Microsoft Dynamics 365 Finance & Operations adapter — Anti-Corruption Layer.
 *
 * <h3>ACL Responsibilities</h3>
 * <ul>
 *   <li>Translates D365 F&O "SalesInvoiceV2" / "FreeTextInvoice" OData entities
 *       → {@link CanonicalInvoice}</li>
 *   <li>Maps D365 field names: InvoiceId → erpInvoiceId, InvoiceNumber → invoiceNumber,
 *       InvoiceDate → invoiceDate, TotalChargeAmount → totalAmount, etc.</li>
 *   <li>Resolves invoice PDFs from D365 Document Management (SharePoint integration)
 *       or a shared export directory</li>
 *   <li>Handles Azure AD OAuth 2.0 client-credentials flow for API auth</li>
 *   <li>Translates D365 OData error responses → domain exceptions</li>
 * </ul>
 *
 * <h3>D365 Invoice PDF Flow — Two Options</h3>
 *
 * <b>Option A: Document Management (SharePoint)</b>
 * <p>D365 stores generated invoice PDFs in Document Management, often backed by
 * SharePoint. The adapter queries the document attachment entity:
 * <pre>
 * GET /data/SalesInvoiceHeaders({InvoiceId})/DocumentAttachments
 *   ?$filter=TypeId eq 'File' and FileName endswith '.pdf'
 * </pre>
 * Then downloads the PDF from the returned SharePoint URL.</p>
 *
 * <b>Option B: Shared Directory Export</b>
 * <p>A D365 batch job (recurring) exports fiscalised PDFs to a mounted network share.
 * This adapter reads from that directory using the configured {@code invoicePdfDir}.</p>
 *
 * <b>Option C: Print Management (SSRS)</b>
 * <p>D365 uses SQL Server Reporting Services (SSRS) for invoice reports.
 * The Print Management framework can be configured to auto-export PDFs to a
 * network share or email destination on posting.</p>
 *
 * <p>Activated only when {@code erp.dynamics365.base-url} is configured.</p>
 */
@Component
@ConditionalOnProperty(prefix = "erp.dynamics365", name = "base-url")
public class Dynamics365Adapter implements ErpInvoicePort {

    private static final Logger log = LoggerFactory.getLogger(Dynamics365Adapter.class);

    private final ErpAdapterProperties.Dynamics365Config config;

    public Dynamics365Adapter(ErpAdapterProperties props) {
        this.config = props.dynamics365();
        log.info("Dynamics 365 adapter initialised — tenant={}, pdfDir={}",
                config.tenantId(), config.invoicePdfDir());
    }

    @Override
    public ErpSource supports() {
        return ErpSource.DYNAMICS_365;
    }

    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        log.debug("Fetching {} invoices from Dynamics 365 [tenant={}]",
                invoiceIds.size(), tenantId);

        // ──────────────────────────────────────────────────────────────
        // TODO: Replace with actual D365 OData API calls
        //
        // D365 F&O OData pattern:
        //   GET https://{org}.api.crm.dynamics.com/data/SalesInvoiceHeadersV2
        //       ?$filter=InvoiceNumber in ('INV-001','INV-002')
        //       &$expand=SalesInvoiceLines
        //   Authorization: Bearer {access_token}
        //   Accept: application/json
        //
        // Response JSON fields to map:
        //   InvoiceId                → erpInvoiceId
        //   InvoiceNumber            → invoiceNumber
        //   InvoiceDate              → invoiceDate
        //   DueDate                  → dueDate
        //   InvoiceAmountInclTax     → totalAmount (D365 F&O: TotalChargeAmount or custom)
        //   TotalTaxAmount           → vatAmount
        //   CurrencyCode             → currency
        //   InvoiceCustomerAccountNumber → (lookup contact for email)
        //   OrderingCustomerName     → recipientCompany
        //
        // For Business Central (BC) the entity is different:
        //   GET /api/v2.0/companies({id})/salesInvoices
        //   Fields: number, invoiceDate, dueDate, totalAmountIncludingTax,
        //           currencyCode, customerName, customerNumber
        //
        // Azure AD token:
        //   POST https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token
        //   scope=https://{org}.dynamics.com/.default
        //   grant_type=client_credentials
        // ──────────────────────────────────────────────────────────────

        var invoices = new ArrayList<CanonicalInvoice>();

        for (String invoiceId : invoiceIds) {
            try {
                var invoice = mapD365Invoice(tenantId, invoiceId);
                invoices.add(invoice);
            } catch (Exception e) {
                log.error("Failed to fetch D365 invoice {}: {}", invoiceId, e.getMessage());
                throw new ErpIntegrationException(ErpSource.DYNAMICS_365,
                        "Failed to fetch invoice " + invoiceId, e);
            }
        }

        return invoices;
    }

    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        // ── Strategy 1: Read from export directory ──
        if (config.invoicePdfDir() != null && !config.invoicePdfDir().isBlank()) {
            Path pdfPath = Path.of(config.invoicePdfDir(), invoiceId + ".pdf");
            if (Files.exists(pdfPath)) {
                try {
                    return Base64.getEncoder().encodeToString(Files.readAllBytes(pdfPath));
                } catch (IOException e) {
                    throw new ErpIntegrationException(ErpSource.DYNAMICS_365,
                            "Failed to read D365 PDF: " + pdfPath, e);
                }
            }
        }

        // ── Strategy 2: Fetch from D365 Document Management ──
        // TODO: Query DocumentAttachments entity for the invoice
        //   GET /data/SalesInvoiceHeaders('{invoiceId}')/DocumentAttachments
        //     ?$filter=TypeId eq 'File'
        //   Then download the attachment URL

        throw new ErpIntegrationException(ErpSource.DYNAMICS_365,
                "PDF not found for D365 invoice " + invoiceId
                        + " — check export directory or Document Management");
    }

    @Override
    public boolean healthCheck(String tenantId) {
        // TODO: Call D365 company info endpoint to verify credentials
        //   GET /data/Companies
        log.debug("Dynamics 365 health check for tenant {}", tenantId);
        return config.baseUrl() != null && !config.baseUrl().isBlank();
    }

    // ── ACL: D365 Model → Canonical Model ──

    private CanonicalInvoice mapD365Invoice(String tenantId, String invoiceId) {
        // TODO: Replace with actual D365 OData response mapping

        Path pdfDir = config.invoicePdfDir() != null ? Path.of(config.invoicePdfDir()) : null;
        Path pdfPath = pdfDir != null ? pdfDir.resolve(invoiceId + ".pdf") : null;
        boolean hasPdf = pdfPath != null && Files.exists(pdfPath);

        return new CanonicalInvoice(
                ErpSource.DYNAMICS_365,
                tenantId,
                invoiceId,
                // Recipient — from D365 customer master
                null,  // recipientEmail — from CustTable / contact
                null,  // recipientName
                null,  // recipientCompany — OrderingCustomerName
                // Invoice identity
                invoiceId,  // InvoiceNumber
                LocalDate.now(),  // InvoiceDate
                LocalDate.now().plusDays(30),  // DueDate
                // Financial
                BigDecimal.ZERO,  // InvoiceAmountExclTax
                BigDecimal.ZERO,  // TotalTaxAmount
                BigDecimal.ZERO,  // InvoiceAmountInclTax
                "USD",            // CurrencyCode
                // Fiscal — from custom fields / fiscal device integration
                FiscalMetadata.EMPTY,
                // PDF
                new PdfSource(
                        hasPdf ? pdfPath.toAbsolutePath().toString() : null,
                        null, null,
                        invoiceId + ".pdf"
                ),
                Map.of()
        );
    }
}

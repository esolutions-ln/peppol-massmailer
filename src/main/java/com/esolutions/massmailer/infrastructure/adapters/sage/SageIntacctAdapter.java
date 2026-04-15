package com.esolutions.massmailer.infrastructure.adapters.sage;

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
 * Sage Intacct ERP adapter — Anti-Corruption Layer implementation.
 *
 * <h3>ACL Responsibilities</h3>
 * <ul>
 *   <li>Translates Sage "ARINVOICE" records → {@link CanonicalInvoice}</li>
 *   <li>Maps Sage field names: RECORDNO → erpInvoiceId, DOCNUMBER → invoiceNumber,
 *       TOTALDUE → totalAmount, etc.</li>
 *   <li>Resolves invoice PDFs from Sage's PDF export directory (configured per tenant)</li>
 *   <li>Handles Sage-specific error codes → domain exceptions</li>
 *   <li>Manages Sage API session (sender credentials + company login)</li>
 * </ul>
 *
 * <h3>Sage Intacct Invoice PDF Flow</h3>
 * <p>Sage Intacct doesn't provide a direct "download PDF" API. The typical pattern is:
 * <ol>
 *   <li>Configure Sage to auto-export fiscalised invoices to a shared directory</li>
 *   <li>Sage writes PDFs with naming convention: {@code {DOCNUMBER}.pdf}</li>
 *   <li>This adapter reads from that directory</li>
 * </ol>
 * <p>Alternatively, use Sage's "Smart Events" to trigger a webhook when an invoice
 * is posted, and the webhook handler writes the PDF to the shared directory.</p>
 *
 * <p>Activated only when {@code erp.sage.base-url} is configured.</p>
 */
@Component
@ConditionalOnProperty(prefix = "erp.sage", name = "base-url")
public class SageIntacctAdapter implements ErpInvoicePort {

    private static final Logger log = LoggerFactory.getLogger(SageIntacctAdapter.class);

    private final ErpAdapterProperties.SageConfig config;

    public SageIntacctAdapter(ErpAdapterProperties props) {
        this.config = props.sage();
        log.info("Sage Intacct adapter initialised — company={}, pdfDir={}",
                config.companyId(), config.invoicePdfDir());
    }

    @Override
    public ErpSource supports() {
        return ErpSource.SAGE_INTACCT;
    }

    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        log.debug("Fetching {} invoices from Sage Intacct [company={}]", invoiceIds.size(), tenantId);

        // ──────────────────────────────────────────────────────────────
        // TODO: Replace with actual Sage Intacct XML API call
        //
        // Sage API call pattern:
        //   POST https://api.intacct.com/ia/xml/xmlgw.phtml
        //   Body: XML envelope with <readByQuery> on ARINVOICE object
        //   Filter: DOCNUMBER IN ('INV-001', 'INV-002', ...)
        //
        // Response XML fields to map:
        //   RECORDNO        → erpInvoiceId
        //   DOCNUMBER       → invoiceNumber
        //   WHENCREATED     → invoiceDate
        //   WHENDUE         → dueDate
        //   TOTALENTERED    → subtotalAmount
        //   TOTALPAID       → (subtract from total for balance)
        //   TOTALDUE        → totalAmount
        //   CURRENCY        → currency
        //   CUSTOMERID      → (lookup contact for email)
        //   CUSTOMERNAME    → recipientCompany
        //   CONTACTEMAIL    → recipientEmail (via CUSTOMER.CONTACTINFO.EMAIL)
        //
        // Fiscal metadata must come from custom fields or a separate
        // fiscal device integration table.
        // ──────────────────────────────────────────────────────────────

        var invoices = new ArrayList<CanonicalInvoice>();

        for (String invoiceId : invoiceIds) {
            try {
                // ACL: Map Sage fields → canonical model
                var invoice = mapSageInvoice(tenantId, invoiceId);
                invoices.add(invoice);
            } catch (Exception e) {
                log.error("Failed to fetch Sage invoice {}: {}", invoiceId, e.getMessage());
                throw new ErpIntegrationException(ErpSource.SAGE_INTACCT,
                        "Failed to fetch invoice " + invoiceId, e);
            }
        }

        return invoices;
    }

    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        // Resolve PDF from Sage export directory
        Path pdfDir = Path.of(config.invoicePdfDir());
        Path pdfPath = pdfDir.resolve(invoiceId + ".pdf");

        if (!Files.exists(pdfPath)) {
            throw new ErpIntegrationException(ErpSource.SAGE_INTACCT,
                    "PDF not found in Sage export directory: " + pdfPath);
        }

        try {
            byte[] bytes = Files.readAllBytes(pdfPath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new ErpIntegrationException(ErpSource.SAGE_INTACCT,
                    "Failed to read Sage PDF: " + pdfPath, e);
        }
    }

    @Override
    public boolean healthCheck(String tenantId) {
        // TODO: Call Sage API <getAPISession> to verify credentials
        log.debug("Sage Intacct health check for company {}", tenantId);
        return config.baseUrl() != null && !config.baseUrl().isBlank();
    }

    // ── ACL: Sage Model → Canonical Model ──

    private CanonicalInvoice mapSageInvoice(String tenantId, String invoiceId) {
        // TODO: Replace with actual Sage API response mapping
        // This shows the field mapping contract your team will implement

        Path pdfPath = Path.of(config.invoicePdfDir(), invoiceId + ".pdf");
        boolean hasPdf = Files.exists(pdfPath);

        return new CanonicalInvoice(
                ErpSource.SAGE_INTACCT,
                tenantId,
                invoiceId,
                // Recipient — from Sage CUSTOMER.CONTACTINFO
                null,  // recipientEmail — fetch from Sage CUSTOMER object
                null,  // recipientName
                null,  // recipientCompany — CUSTOMERNAME
                // Invoice identity
                invoiceId,  // DOCNUMBER
                LocalDate.now(),  // WHENCREATED
                LocalDate.now().plusDays(30),  // WHENDUE
                // Financial — from Sage TOTALENTERED / TOTALDUE
                BigDecimal.ZERO,  // subtotalAmount
                BigDecimal.ZERO,  // vatAmount (Sage: TOTALTAX or custom field)
                BigDecimal.ZERO,  // totalAmount — TOTALDUE
                "USD",            // CURRENCY
                // Fiscal — from custom fields or fiscal device integration
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

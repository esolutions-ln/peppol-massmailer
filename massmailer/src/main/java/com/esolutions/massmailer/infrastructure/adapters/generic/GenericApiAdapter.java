package com.esolutions.massmailer.infrastructure.adapters.generic;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.*;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generic / direct API adapter — always active as the default fallback.
 *
 * <p>This adapter is used when the caller provides the complete invoice
 * data and PDF attachment directly in the REST API payload (file path or
 * Base64), without needing the Mass Mailer to fetch anything from an ERP.
 *
 * <p><b>Use cases:</b>
 * <ul>
 *   <li>ERP has its own webhook/export pipeline that produces ready-to-send invoices</li>
 *   <li>Custom invoicing system that isn't one of the built-in adapters</li>
 *   <li>Testing and development</li>
 *   <li>Manual re-send of specific invoices</li>
 * </ul>
 *
 * <p>The {@code fetchInvoices} method is a no-op — it throws because
 * the Generic adapter doesn't fetch from any ERP. Invoice data comes
 * directly in the campaign/single-mail request payload.</p>
 */
@Component
public class GenericApiAdapter implements ErpInvoicePort {

    private static final Logger log = LoggerFactory.getLogger(GenericApiAdapter.class);

    @Override
    public ErpSource supports() {
        return ErpSource.GENERIC_API;
    }

    @Override
    public List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds) {
        // Generic adapter doesn't fetch — data comes in the request payload
        throw new UnsupportedOperationException(
                "Generic API adapter does not fetch invoices from an ERP. " +
                "Provide full invoice data and PDF in the API request payload.");
    }

    @Override
    public String fetchInvoicePdfAsBase64(String tenantId, String invoiceId) {
        throw new UnsupportedOperationException(
                "Generic API adapter does not download PDFs from an ERP. " +
                "Provide pdfFilePath or pdfBase64 in the API request payload.");
    }

    @Override
    public boolean healthCheck(String tenantId) {
        return true; // Always healthy — no external dependency
    }
}

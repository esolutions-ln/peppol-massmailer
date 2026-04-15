package com.esolutions.massmailer.domain.ports;

import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;

import java.util.List;

/**
 * Port interface for ERP invoice integration (Hexagonal Architecture).
 *
 * <p>Each ERP system (Sage Intacct, QuickBooks Online, Dynamics 365)
 * provides an adapter that implements this interface. The domain
 * layer (CampaignOrchestrator, SmtpSendService) depends only on
 * this port — never on ERP-specific classes.</p>
 *
 * <h3>SOLID Compliance</h3>
 * <ul>
 *   <li><b>O (Open/Closed)</b> — Adding a new ERP = new adapter class, no modification to existing code</li>
 *   <li><b>L (Liskov)</b> — Any adapter is substitutable through this interface</li>
 *   <li><b>D (Dependency Inversion)</b> — Domain depends on this abstraction, not on Sage/QB/D365 SDKs</li>
 * </ul>
 */
public interface ErpInvoicePort {

    /**
     * @return The ERP source this adapter handles
     */
    ErpSource supports();

    /**
     * Fetches invoices from the ERP and transforms them into canonical form.
     *
     * The adapter handles:
     * - ERP-specific API authentication
     * - Pagination / rate limiting
     * - Field mapping (ERP model → CanonicalInvoice)
     * - PDF retrieval (download, export, or path resolution)
     * - Error translation (ERP error codes → domain exceptions)
     *
     * @param tenantId   ERP tenant/company identifier
     * @param invoiceIds List of ERP-native invoice identifiers to fetch
     * @return List of normalised canonical invoices
     * @throws ErpIntegrationException if the ERP call fails
     */
    List<CanonicalInvoice> fetchInvoices(String tenantId, List<String> invoiceIds);

    /**
     * Fetches a single invoice from the ERP.
     *
     * @param tenantId  ERP tenant/company identifier
     * @param invoiceId ERP-native invoice identifier
     * @return Normalised canonical invoice
     * @throws ErpIntegrationException if the ERP call fails
     */
    default CanonicalInvoice fetchInvoice(String tenantId, String invoiceId) {
        var results = fetchInvoices(tenantId, List.of(invoiceId));
        if (results.isEmpty()) {
            throw new ErpIntegrationException(supports(),
                    "Invoice not found in " + supports() + ": " + invoiceId);
        }
        return results.getFirst();
    }

    /**
     * Resolves the PDF for an invoice from the ERP's document storage.
     * Some ERPs require a separate API call to download the PDF
     * (e.g. QuickBooks invoice PDF endpoint, D365 document attachments).
     *
     * @param tenantId  ERP tenant/company identifier
     * @param invoiceId ERP-native invoice identifier
     * @return PDF bytes as Base64 string
     * @throws ErpIntegrationException if the PDF cannot be retrieved
     */
    String fetchInvoicePdfAsBase64(String tenantId, String invoiceId);

    /**
     * Validates connectivity to the ERP system.
     * Used for health checks and startup verification.
     *
     * @param tenantId ERP tenant/company identifier
     * @return true if the ERP is reachable and authenticated
     */
    boolean healthCheck(String tenantId);
}

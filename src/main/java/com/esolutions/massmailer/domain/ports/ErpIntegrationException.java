package com.esolutions.massmailer.domain.ports;

import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;

/**
 * Thrown when an ERP adapter encounters an error during invoice
 * fetch, PDF download, or connectivity check.
 *
 * <p>Carries the {@link ErpSource} so error handlers can distinguish
 * between Sage API timeouts vs QuickBooks auth failures vs D365
 * document retrieval errors.</p>
 */
public class ErpIntegrationException extends RuntimeException {

    private final ErpSource erpSource;
    private final String erpErrorCode;

    public ErpIntegrationException(ErpSource erpSource, String message) {
        super("[" + erpSource + "] " + message);
        this.erpSource = erpSource;
        this.erpErrorCode = null;
    }

    public ErpIntegrationException(ErpSource erpSource, String message, Throwable cause) {
        super("[" + erpSource + "] " + message, cause);
        this.erpSource = erpSource;
        this.erpErrorCode = null;
    }

    public ErpIntegrationException(ErpSource erpSource, String erpErrorCode, String message, Throwable cause) {
        super("[" + erpSource + ":" + erpErrorCode + "] " + message, cause);
        this.erpSource = erpSource;
        this.erpErrorCode = erpErrorCode;
    }

    public ErpSource getErpSource() { return erpSource; }
    public String getErpErrorCode() { return erpErrorCode; }
}

package com.esolutions.massmailer.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Canonical (ERP-agnostic) fiscalised invoice representation.
 *
 * This is the Anti-Corruption Layer (ACL) boundary — every ERP adapter
 * normalizes its native invoice format into this record before the
 * mail dispatch pipeline touches it.
 *
 * <p>Sage calls it a "Transaction", QuickBooks calls it an "Invoice object",
 * D365 calls it a "Free Text Invoice" or "Sales Invoice" — this record
 * is what the Mass Mailer actually works with.</p>
 */
public record CanonicalInvoice(

        // ── Source ERP ──
        ErpSource erpSource,
        String erpTenantId,
        String erpInvoiceId,

        // ── Recipient ──
        String recipientEmail,
        String recipientName,
        String recipientCompany,

        // ── Invoice identity ──
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,

        // ── Financial ──
        BigDecimal subtotalAmount,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String currency,

        // ── Fiscal device / ZIMRA ──
        FiscalMetadata fiscalMetadata,

        // ── PDF Attachment ──
        PdfSource pdfSource,

        // ── Extra merge fields for the email template ──
        Map<String, Object> additionalMergeFields
) {

    /**
     * Supported ERP source systems.
     * Each has a corresponding adapter implementation.
     */
    public enum ErpSource {
        SAGE_INTACCT,
        QUICKBOOKS_ONLINE,
        DYNAMICS_365,
        ODOO,
        GENERIC_API
    }

    /**
     * ZIMRA fiscal device metadata — optional, populated when
     * the invoice has been signed by a fiscal device.
     */
    public record FiscalMetadata(
            String fiscalDeviceSerialNumber,
            String fiscalDayNumber,
            String globalInvoiceCounter,
            String verificationCode,
            String qrCodeUrl
    ) {
        public static final FiscalMetadata EMPTY = new FiscalMetadata(null, null, null, null, null);

        public boolean isPresent() {
            return verificationCode != null && !verificationCode.isBlank();
        }
    }

    /**
     * PDF source — the adapter resolves this from the ERP's native storage.
     * Exactly one of filePath, base64, or downloadUrl should be non-null.
     */
    public record PdfSource(
            /** Absolute path on shared filesystem (e.g. Sage export dir, D365 mounted share) */
            String filePath,
            /** Base64-encoded PDF bytes (e.g. QuickBooks API download, then encoded) */
            String base64,
            /** Direct download URL — the adapter will fetch + encode before dispatch */
            String downloadUrl,
            /** Attachment filename */
            String fileName
    ) {
        public PdfSource {
            if (fileName == null || fileName.isBlank()) {
                fileName = "invoice.pdf";
            }
        }

        public boolean hasSource() {
            return (filePath != null && !filePath.isBlank())
                    || (base64 != null && !base64.isBlank())
                    || (downloadUrl != null && !downloadUrl.isBlank());
        }
    }
}

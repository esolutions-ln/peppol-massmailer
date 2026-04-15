package com.esolutions.massmailer.dto;

import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable DTO records for the fiscalised invoice mass-mailing API.
 * Every field is annotated with {@code @Schema} for OpenAPI documentation.
 */
public final class MailDtos {

    private MailDtos() {}

    // ══════════════════════════════════════════════
    //  Request DTOs
    // ══════════════════════════════════════════════

    @Schema(description = "Creates a mass-mail campaign for sending fiscalised invoice PDFs to multiple recipients")
    public record CampaignRequest(

            @Schema(description = "Human-readable campaign name for tracking",
                    example = "March 2026 Invoices", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String name,

            @Schema(description = "Email subject line sent to all recipients",
                    example = "Your Invoice from eSolutions", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String subject,

            @Schema(description = "Thymeleaf template name (without path/extension). " +
                    "Use 'invoice' for the built-in fiscalised invoice template",
                    example = "invoice", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String templateName,

            @Schema(description = "Shared template variables applied to all recipients. " +
                    "Per-recipient mergeFields override these.",
                    example = """
                            {"companyName": "eSolutions", "accountsEmail": "accounts@esolutions.co.zw"}""")
            Map<String, Object> templateVariables,

            @Schema(description = "Organization ID — used for source tracking and metering",
                    example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID organizationId,

            @Schema(description = "List of invoice recipients — each carries their own PDF and fiscal metadata",
                    requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1)
            @NotEmpty @Valid List<InvoiceRecipientEntry> recipients

    ) {}

    @Schema(description = "A single invoice recipient within a campaign, carrying PDF attachment and fiscal metadata")
    public record InvoiceRecipientEntry(

            // ── Contact ──
            @Schema(description = "Recipient email address",
                    example = "customer@acmecorp.co.zw", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Email String email,

            @Schema(description = "Recipient display name (used in greeting)",
                    example = "Alice Moyo")
            String name,

            // ── Invoice identity ──
            @Schema(description = "Unique fiscal invoice number",
                    example = "INV-2026-0100", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String invoiceNumber,

            @Schema(description = "Invoice issue date", example = "2026-03-01")
            LocalDate invoiceDate,

            @Schema(description = "Payment due date", example = "2026-03-31")
            LocalDate dueDate,

            // ── Financial ──
            @Schema(description = "Total invoice amount (inclusive of VAT)", example = "2400.00")
            BigDecimal totalAmount,

            @Schema(description = "VAT amount", example = "360.00")
            BigDecimal vatAmount,

            @Schema(description = "ISO 4217 currency code. Zimbabwe supports: USD (US Dollar), " +
                    "ZWG (Zimbabwe Gold), ZAR (South African Rand), GBP, EUR. " +
                    "Must be specified — no default applied.",
                    example = "USD")
            String currency,

            // ── Fiscal device / ZIMRA compliance ──
            @Schema(description = "Serial number of the fiscal device that signed this invoice",
                    example = "FD-SN-12345")
            String fiscalDeviceSerialNumber,

            @Schema(description = "Fiscal day number on the device", example = "60")
            String fiscalDayNumber,

            @Schema(description = "Sequential global invoice counter from the fiscal device",
                    example = "10001")
            String globalInvoiceCounter,

            @Schema(description = "ZIMRA fiscal verification code",
                    example = "AAAA-BBBB-1111")
            String verificationCode,

            @Schema(description = "URL to the ZIMRA verification QR code image. " +
                    "Rendered as a scannable QR in the email body.",
                    example = "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111")
            String qrCodeUrl,

            // ── PDF Attachment (supply ONE) ──
            @Schema(description = "Absolute path to the fiscalised PDF on a shared filesystem. " +
                    "Typical for ERP integrations where PDFs are written to a mounted volume. " +
                    "Supply this OR pdfBase64, not both.",
                    example = "/var/lib/odoo/invoices/INV-2026-0100.pdf")
            String pdfFilePath,

            @Schema(description = "Base64-encoded PDF bytes. Use this when the calling system " +
                    "cannot share a filesystem (e.g. cloud ERP / remote API). " +
                    "Supply this OR pdfFilePath, not both.",
                    example = "JVBERi0xLjQK...")
            String pdfBase64,

            @Schema(description = "Filename for the PDF attachment. Defaults to '{invoiceNumber}.pdf' if omitted.",
                    example = "INV-2026-0100.pdf")
            String pdfFileName,

            @Schema(description = "Additional per-recipient template merge fields (override campaign-level variables)",
                    example = """
                            {"recipientName": "Alice Moyo", "customNote": "Thank you for your prompt payment"}""")
            Map<String, Object> mergeFields

    ) {
        public InvoiceRecipientEntry {
            if (pdfFileName == null || pdfFileName.isBlank()) {
                pdfFileName = invoiceNumber + ".pdf";
            }
            // No currency default — callers must specify. Zimbabwe supports USD, ZWG, ZAR, etc.
        }
    }

    @Schema(description = "Send a single fiscalised invoice email synchronously (not part of a campaign)")
    public record SingleInvoiceMailRequest(

            @Schema(description = "Recipient email address",
                    example = "customer@acmecorp.co.zw", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Email String to,

            @Schema(description = "Recipient display name", example = "Acme Corporation")
            String recipientName,

            @Schema(description = "Email subject line",
                    example = "Invoice INV-2026-0042 from eSolutions", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String subject,

            @Schema(description = "Template name — use 'invoice' for the fiscalised invoice template",
                    example = "invoice", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String templateName,

            @Schema(description = "Fiscal invoice number",
                    example = "INV-2026-0042", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String invoiceNumber,

            @Schema(description = "Invoice issue date", example = "2026-03-23")
            LocalDate invoiceDate,

            @Schema(description = "Payment due date", example = "2026-04-22")
            LocalDate dueDate,

            @Schema(description = "Total amount (VAT inclusive)", example = "1250.00")
            BigDecimal totalAmount,

            @Schema(description = "VAT amount", example = "187.50")
            BigDecimal vatAmount,

            @Schema(description = "ISO 4217 currency code", example = "USD")
            String currency,

            @Schema(description = "Fiscal device serial number", example = "FD-SN-12345")
            String fiscalDeviceSerialNumber,

            @Schema(description = "Fiscal day number", example = "42")
            String fiscalDayNumber,

            @Schema(description = "Global invoice counter", example = "0001234")
            String globalInvoiceCounter,

            @Schema(description = "ZIMRA verification code", example = "ABCD-EFGH-1234")
            String verificationCode,

            @Schema(description = "ZIMRA QR code image URL",
                    example = "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234")
            String qrCodeUrl,

            @Schema(description = "Absolute path to the PDF file on disk",
                    example = "/var/lib/odoo/invoices/INV-2026-0042.pdf")
            String pdfFilePath,

            @Schema(description = "Base64-encoded PDF bytes (alternative to pdfFilePath)",
                    example = "JVBERi0xLjQK...")
            String pdfBase64,

            @Schema(description = "PDF attachment filename", example = "INV-2026-0042.pdf")
            String pdfFileName,

            @Schema(description = "Additional template variables",
                    example = """
                            {"companyName": "eSolutions", "paymentInstructions": "<p>Bank: CBZ</p>"}""")
            Map<String, Object> variables

    ) {}

    // ══════════════════════════════════════════════
    //  Response DTOs
    // ══════════════════════════════════════════════

    @Schema(description = "Campaign delivery status — poll this to track dispatch progress")
    public record CampaignResponse(

            @Schema(description = "Campaign UUID", example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID id,

            @Schema(description = "Campaign name", example = "March 2026 Invoices")
            String name,

            @Schema(description = "Current status: CREATED, QUEUED, IN_PROGRESS, COMPLETED, PARTIALLY_FAILED, FAILED, CANCELLED",
                    example = "COMPLETED")
            String status,

            @Schema(description = "Total number of invoice recipients", example = "150")
            int totalRecipients,

            @Schema(description = "Number of invoices successfully emailed", example = "148")
            int sent,

            @Schema(description = "Number of failed deliveries", example = "2")
            int failed,

            @Schema(description = "Number of skipped recipients (e.g. missing PDF)", example = "0")
            int skipped,

            @Schema(description = "Campaign creation timestamp (ISO 8601)", example = "2026-03-24T08:00:00Z")
            String createdAt,

            @Schema(description = "Dispatch start timestamp", example = "2026-03-24T08:00:01Z")
            String startedAt,

            @Schema(description = "Dispatch completion timestamp", example = "2026-03-24T08:02:30Z")
            String completedAt

    ) {}

    @Schema(description = "Response after creating a new campaign")
    public record CampaignCreatedResponse(

            @Schema(description = "UUID of the newly created campaign — use this to poll status",
                    example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID campaignId,

            @Schema(description = "Confirmation message", example = "Invoice campaign queued for dispatch")
            String message,

            @Schema(description = "Number of invoice recipients queued", example = "3")
            int recipientCount

    ) {}

    @Schema(description = "Single invoice email delivery result")
    public record SingleMailResponse(

            @Schema(description = "Delivery status: delivered, failed, or skipped", example = "delivered")
            String status,

            @Schema(description = "Recipient email address", example = "customer@acmecorp.co.zw")
            String recipient,

            @Schema(description = "Invoice number for correlation", example = "INV-2026-0042")
            String invoiceNumber,

            @Schema(description = "SMTP message ID (present only on success)",
                    example = "<abc123@smtp.gmail.com>", nullable = true)
            String messageId,

            @Schema(description = "Error message (present only on failure)", nullable = true,
                    example = "Could not connect to SMTP host: Connection timed out")
            String error,

            @Schema(description = "Whether the failure is transient and worth retrying", example = "true")
            boolean retryable

    ) {}

    @Schema(description = "Standard error response")
    public record ErrorResponse(

            @Schema(description = "HTTP status code", example = "400")
            int status,

            @Schema(description = "Error category", example = "Validation Failed")
            String error,

            @Schema(description = "Detailed error message",
                    example = "recipients[0].invoiceNumber: must not be blank")
            String message,

            @Schema(description = "Request path that caused the error", example = "/api/v1/campaigns")
            String path

    ) {}

    // ══════════════════════════════════════════════
    //  Multi-PDF Upload Dispatch DTO
    // ══════════════════════════════════════════════

    @Schema(description = """
            Metadata for one invoice within a multi-PDF upload batch. \
            Paired with a PDF file part by matching on invoiceNumber. \
            The customer is auto-registered in the registry before dispatch.""")
    public record InvoiceUploadEntry(

            @Schema(description = "Must match the corresponding PDF part name in the multipart request",
                    example = "INV-2026-0100", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String invoiceNumber,

            @Schema(example = "alice@acmecorp.co.zw", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Email String recipientEmail,

            @Schema(example = "Alice Moyo") String recipientName,
            @Schema(example = "Acme Corporation") String recipientCompany,

            @Schema(example = "2026-03-01") LocalDate invoiceDate,
            @Schema(example = "2026-03-31") LocalDate dueDate,
            @Schema(example = "2400.00") BigDecimal totalAmount,
            @Schema(example = "360.00") BigDecimal vatAmount,
            @Schema(description = "ISO 4217 currency code. Zimbabwe: USD, ZWG (Zimbabwe Gold), ZAR, GBP, EUR",
                    example = "ZWG") String currency,

            // Fiscal / ZIMRA
            @Schema(example = "FD-SN-12345") String fiscalDeviceSerialNumber,
            @Schema(example = "60") String fiscalDayNumber,
            @Schema(example = "10001") String globalInvoiceCounter,
            @Schema(example = "AAAA-BBBB-1111") String verificationCode,
            @Schema(example = "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111") String qrCodeUrl
    ) {
        public InvoiceUploadEntry {
            // No currency default — callers must specify. Zimbabwe supports USD, ZWG, ZAR, etc.
        }
    }

    @Schema(description = "Campaign-level metadata for a multi-PDF upload dispatch")
    public record MultiPdfDispatchMetadata(

            @Schema(example = "March 2026 Invoices", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String campaignName,

            @Schema(example = "Your Invoice from eSolutions", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String subject,

            @Schema(example = "invoice", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String templateName,

            @Schema(description = "Organization ID — customers will be registered under this org",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            UUID organizationId,

            @Schema(description = "Shared template variables (companyName, accountsEmail, etc.)",
                    example = """
                            {"companyName": "eSolutions", "accountsEmail": "accounts@esolutions.co.zw"}""")
            Map<String, Object> templateVariables,

            @Schema(description = "One entry per invoice PDF being uploaded",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty @Valid List<InvoiceUploadEntry> invoices
    ) {}

    // ══════════════════════════════════════════════
    //  ERP-Driven Dispatch DTO
    // ══════════════════════════════════════════════

    @Schema(description = """
            Request to fetch invoices from a specific ERP system and dispatch them as an email campaign. \
            The Mass Mailer uses the appropriate adapter (Sage, QuickBooks, D365) to fetch invoice data \
            and PDFs, then creates and dispatches the campaign automatically.""")
    public record ErpDispatchRequest(

            @Schema(description = "Campaign name for tracking",
                    example = "Sage March 2026 Invoices", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String campaignName,

            @Schema(description = "Email subject line",
                    example = "Your Invoice from eSolutions", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String subject,

            @Schema(description = "Email template name — use 'invoice' for the fiscalised invoice template",
                    example = "invoice", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String templateName,

            @Schema(description = """
                    ERP source system to fetch invoices from. Must match a configured adapter.

                    | Value | ERP System | Adapter |
                    |---|---|---|
                    | `SAGE_INTACCT` | Sage Intacct | XML API → ARINVOICE |
                    | `QUICKBOOKS_ONLINE` | QuickBooks Online | REST API → Invoice |
                    | `DYNAMICS_365` | Microsoft Dynamics 365 F&O / BC | OData → SalesInvoice |
                    | `GENERIC_API` | Direct payload (no ERP fetch) | Use /api/v1/campaigns instead |
                    """,
                    example = "SAGE_INTACCT", requiredMode = Schema.RequiredMode.REQUIRED)
            ErpSource erpSource,

            @Schema(description = """
                    ERP tenant / company identifier. Meaning varies by ERP:
                    - **Sage**: Company ID (e.g. 'ESOLUTIONS_ZW')
                    - **QuickBooks**: Realm ID (e.g. '4620816365182009070')
                    - **D365**: Azure AD tenant ID or legal entity""",
                    example = "ESOLUTIONS_ZW", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String tenantId,

            @Schema(description = """
                    List of ERP-native invoice identifiers to fetch and dispatch. \
                    The adapter will retrieve invoice metadata + PDF for each.

                    - **Sage**: DOCNUMBER (e.g. 'INV-2026-0100')
                    - **QuickBooks**: Invoice Id (e.g. '1045')
                    - **D365**: InvoiceNumber (e.g. 'FML-INV-001')""",
                    example = "[\"INV-2026-0100\", \"INV-2026-0101\"]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty List<String> invoiceIds,

            @Schema(description = "Shared template variables (companyName, accountsEmail, etc.)",
                    example = """
                            {"companyName": "eSolutions", "accountsEmail": "accounts@esolutions.co.zw"}""")
            Map<String, Object> templateVariables,

            @Schema(description = """
                    Organization ID — customers fetched from the ERP will be registered \
                    in the customer registry under this org before dispatch. \
                    If omitted, customer registration is skipped.""",
                    example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID organizationId

    ) {}
}

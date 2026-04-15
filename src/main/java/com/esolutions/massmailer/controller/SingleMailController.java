package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.dto.MailDtos.*;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.service.PdfAttachmentResolver;
import com.esolutions.massmailer.service.PdfAttachmentResolver.ResolvedAttachment;
import com.esolutions.massmailer.service.SmtpSendService;
import com.esolutions.massmailer.service.TemplateRenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Single-invoice email endpoint for real-time dispatch from POS / ERP.
 *
 * <p>Unlike the campaign API (which is asynchronous and batched), this endpoint
 * sends one invoice email <b>synchronously</b> and returns the delivery result
 * immediately. Ideal for:
 * <ul>
 *   <li>Real-time email on invoice creation in Odoo / Sage Intacct</li>
 *   <li>Re-sending a specific invoice to a customer</li>
 *   <li>Testing the email + PDF attachment flow</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(name = "Single Invoice Email")
public class SingleMailController {

    private final SmtpSendService smtpService;
    private final TemplateRenderService templateService;
    private final PdfAttachmentResolver pdfResolver;
    private final ObjectMapper objectMapper;

    public SingleMailController(SmtpSendService smtpService,
                                 TemplateRenderService templateService,
                                 PdfAttachmentResolver pdfResolver,
                                 ObjectMapper objectMapper) {
        this.smtpService = smtpService;
        this.templateService = templateService;
        this.pdfResolver = pdfResolver;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/mail/invoice — Send single fiscalised invoice
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Send a single fiscalised invoice email with PDF attachment",
            description = """
                    Sends one invoice email **synchronously** and returns the delivery \
                    result immediately. The response tells the caller whether the email \
                    was delivered, failed, or skipped.

                    ## Integration Pattern

                    Call this endpoint from your ERP/POS system immediately after \
                    fiscalising an invoice:

                    ```
                    ERP generates invoice
                      → Fiscal device signs it
                        → PDF written to disk / encoded as Base64
                          → POST /api/v1/mail/invoice
                            → Customer receives email with PDF attached
                    ```

                    ## PDF Attachment

                    Supply the invoice PDF via **one** of:
                    - `pdfFilePath` — absolute path on a shared volume \
                      (e.g. `/var/lib/odoo/invoices/INV-2026-0042.pdf`)
                    - `pdfBase64` — Base64-encoded PDF bytes in the payload

                    The service validates the PDF magic bytes (`%PDF-`) before \
                    attaching — corrupt or non-PDF files are rejected with a 400 error.

                    ## Email Template

                    Use `templateName: "invoice"` for the built-in fiscalised invoice \
                    template which renders:
                    - Invoice summary table (number, dates, amounts)
                    - Fiscal verification box (device serial, verification code, QR)
                    - PDF attachment notice
                    - No-reply footer

                    ## Fiscal Fields (ZIMRA)

                    All fiscal device fields are optional but recommended for compliance:
                    - `fiscalDeviceSerialNumber` — device that signed the invoice
                    - `fiscalDayNumber` — fiscal day counter
                    - `globalInvoiceCounter` — sequential invoice counter on the device
                    - `verificationCode` — code for online verification
                    - `qrCodeUrl` — URL to the ZIMRA verification QR image
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Invoice email delivered successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class),
                            examples = @ExampleObject(
                                    name = "Delivered",
                                    value = """
                                            {
                                              "status": "delivered",
                                              "recipient": "customer@acmecorp.co.zw",
                                              "invoiceNumber": "INV-2026-0042",
                                              "messageId": "<abc123@smtp.gmail.com>",
                                              "error": null,
                                              "retryable": false
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error or PDF resolution failure",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class),
                            examples = {
                                    @ExampleObject(
                                            name = "PDF Not Found",
                                            value = """
                                                    {
                                                      "status": "failed",
                                                      "recipient": "customer@acmecorp.co.zw",
                                                      "invoiceNumber": "INV-2026-0042",
                                                      "messageId": null,
                                                      "error": "PDF error: PDF file not found: /var/lib/odoo/invoices/INV-2026-0042.pdf",
                                                      "retryable": false
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "Invalid PDF",
                                            value = """
                                                    {
                                                      "status": "failed",
                                                      "recipient": "customer@acmecorp.co.zw",
                                                      "invoiceNumber": "INV-2026-0042",
                                                      "messageId": null,
                                                      "error": "PDF error: File does not have valid PDF magic bytes (%PDF-): Base64 input",
                                                      "retryable": false
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "SMTP delivery failure (may be retryable)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class),
                            examples = @ExampleObject(
                                    name = "SMTP Timeout",
                                    value = """
                                            {
                                              "status": "failed",
                                              "recipient": "customer@acmecorp.co.zw",
                                              "invoiceNumber": "INV-2026-0042",
                                              "messageId": null,
                                              "error": "Could not connect to SMTP host: Connection timed out",
                                              "retryable": true
                                            }
                                            """
                            )
                    )
            )
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Single invoice email request with PDF attachment",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SingleInvoiceMailRequest.class),
                    examples = {
                            @ExampleObject(
                                    name = "File Path (Odoo/ERP integration)",
                                    summary = "PDF on shared filesystem — typical for server-side ERP",
                                    value = """
                                            {
                                              "to": "customer@acmecorp.co.zw",
                                              "recipientName": "Acme Corporation",
                                              "subject": "Invoice INV-2026-0042 from eSolutions",
                                              "templateName": "invoice",
                                              "invoiceNumber": "INV-2026-0042",
                                              "invoiceDate": "2026-03-23",
                                              "dueDate": "2026-04-22",
                                              "totalAmount": 1250.00,
                                              "vatAmount": 187.50,
                                              "currency": "USD",
                                              "fiscalDeviceSerialNumber": "FD-SN-12345",
                                              "fiscalDayNumber": "42",
                                              "globalInvoiceCounter": "0001234",
                                              "verificationCode": "ABCD-EFGH-1234",
                                              "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=ABCD-EFGH-1234",
                                              "pdfFilePath": "/var/lib/odoo/invoices/INV-2026-0042.pdf",
                                              "variables": {
                                                "companyName": "eSolutions",
                                                "accountsEmail": "accounts@esolutions.co.zw",
                                                "companyAddress": "123 Samora Machel Ave, Harare",
                                                "paymentInstructions": "<p>Bank: CBZ | Account: 12345678 | Branch: Harare</p>"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "Base64 (API upload)",
                                    summary = "PDF bytes inline — for remote/cloud ERP integration",
                                    value = """
                                            {
                                              "to": "vendor@example.com",
                                              "recipientName": "Vendor Ltd",
                                              "subject": "Invoice INV-2026-0099",
                                              "templateName": "invoice",
                                              "invoiceNumber": "INV-2026-0099",
                                              "invoiceDate": "2026-03-20",
                                              "dueDate": "2026-04-19",
                                              "totalAmount": 500.00,
                                              "vatAmount": 75.00,
                                              "currency": "ZWG",
                                              "fiscalDeviceSerialNumber": "FD-SN-99999",
                                              "verificationCode": "WXYZ-5678-ABCD",
                                              "pdfBase64": "JVBERi0xLjQK... (full Base64 string)",
                                              "pdfFileName": "INV-2026-0099.pdf",
                                              "variables": {
                                                "companyName": "InvoiceDirect"
                                              }
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "Minimal (ZWG, no fiscal fields)",
                                    summary = "Bare minimum required fields",
                                    value = """
                                            {
                                              "to": "client@example.com",
                                              "subject": "Your Invoice",
                                              "templateName": "invoice",
                                              "invoiceNumber": "INV-2026-0300",
                                              "totalAmount": 1000.00,
                                              "currency": "ZWG",
                                              "pdfFilePath": "/invoices/INV-2026-0300.pdf"
                                            }
                                            """
                            )
                    }
            )
    )
    @PostMapping(value = "/invoice",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SingleMailResponse> sendInvoice(
            @Valid @RequestBody SingleInvoiceMailRequest request) {

        // ── 1. Resolve PDF attachment ──
        ResolvedAttachment pdf = null;
        if (request.pdfFilePath() != null || request.pdfBase64() != null) {
            try {
                String fileName = (request.pdfFileName() != null)
                        ? request.pdfFileName()
                        : request.invoiceNumber() + ".pdf";
                pdf = pdfResolver.resolve(request.pdfFilePath(), request.pdfBase64(), fileName);
            } catch (PdfAttachmentResolver.PdfResolutionException e) {
                return ResponseEntity.badRequest().body(new SingleMailResponse(
                        "failed", request.to(), request.invoiceNumber(),
                        null, "PDF error: " + e.getMessage(), false
                ));
            }
        }

        // ── 2. Build template variables (merge invoice metadata) ──
        var vars = buildTemplateVars(request);

        // ── 3. Render HTML body ──
        String html = templateService.render(request.templateName(), vars, Map.of());

        // ── 4. Send via SMTP with PDF attachment ──
        DeliveryResult result = smtpService.sendWithFallback(
                request.to(), request.recipientName(),
                request.subject(), html,
                request.invoiceNumber(), pdf);

        // ── 5. Pattern match on sealed result ──
        return switch (result) {
            case DeliveryResult.Delivered d -> ResponseEntity.ok(new SingleMailResponse(
                    "delivered", d.recipientEmail(), d.invoiceNumber(),
                    d.messageId(), null, false
            ));
            case DeliveryResult.Failed f -> ResponseEntity.status(502).body(new SingleMailResponse(
                    "failed", f.recipientEmail(), f.invoiceNumber(),
                    null, f.errorMessage(), f.retryable()
            ));
            case DeliveryResult.Skipped s -> ResponseEntity.ok(new SingleMailResponse(
                    "skipped", s.recipientEmail(), s.invoiceNumber(),
                    null, s.reason(), false
            ));
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/mail/invoice/upload — Send with PDF as multipart
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Send a single invoice email with PDF uploaded as multipart file",
            description = """
                    Accepts the invoice PDF as a real file upload (`multipart/form-data`). \
                    Use this when the calling system cannot share a filesystem with the \
                    Mass Mailer — e.g. a remote Sage Intacct, QuickBooks, or any cloud ERP.

                    ## Request Parts

                    | Part | Type | Description |
                    |---|---|---|
                    | `pdf` | file | The invoice PDF binary |
                    | `metadata` | JSON string | Invoice metadata (same fields as `/api/v1/mail/invoice` minus pdfFilePath/pdfBase64) |

                    ## Example (curl)

                    ```bash
                    curl -X POST https://ap.invoicedirect.biz/api/v1/mail/invoice/upload \\
                      -F "pdf=@/path/to/INV-2026-0042.pdf" \\
                      -F 'metadata={"to":"customer@acme.co.zw","subject":"Your Invoice","templateName":"invoice",\
                    "invoiceNumber":"INV-2026-0042","totalAmount":1250.00,"currency":"USD",\
                    "variables":{"companyName":"eSolutions"}}'
                    ```
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice email delivered",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid PDF or metadata",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class))),
            @ApiResponse(responseCode = "502", description = "SMTP delivery failure",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SingleMailResponse.class)))
    })
    @PostMapping(value = "/invoice/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SingleMailResponse> sendInvoiceWithUpload(
            @RequestPart("pdf") MultipartFile pdfFile,
            @RequestPart("metadata") String metadataJson) {

        // ── 1. Parse metadata JSON ──
        SingleInvoiceMailRequest request;
        try {
            request = objectMapper.readValue(metadataJson, SingleInvoiceMailRequest.class);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new SingleMailResponse(
                    "failed", "unknown", "unknown",
                    null, "Invalid metadata JSON: " + e.getMessage(), false
            ));
        }

        // ── 2. Resolve PDF from uploaded bytes ──
        ResolvedAttachment pdf;
        try {
            String fileName = (request.pdfFileName() != null && !request.pdfFileName().isBlank())
                    ? request.pdfFileName()
                    : (pdfFile.getOriginalFilename() != null ? pdfFile.getOriginalFilename()
                    : request.invoiceNumber() + ".pdf");
            pdf = pdfResolver.resolveFromBytes(pdfFile.getBytes(), fileName);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new SingleMailResponse(
                    "failed", request.to(), request.invoiceNumber(),
                    null, "Failed to read uploaded PDF: " + e.getMessage(), false
            ));
        } catch (PdfAttachmentResolver.PdfResolutionException e) {
            return ResponseEntity.badRequest().body(new SingleMailResponse(
                    "failed", request.to(), request.invoiceNumber(),
                    null, "PDF error: " + e.getMessage(), false
            ));
        }

        // ── 3. Build template vars, render, send ──
        var vars = buildTemplateVars(request);
        String html = templateService.render(request.templateName(), vars, Map.of());
        DeliveryResult result = smtpService.sendWithFallback(
                request.to(), request.recipientName(),
                request.subject(), html,
                request.invoiceNumber(), pdf);

        return switch (result) {
            case DeliveryResult.Delivered d -> ResponseEntity.ok(new SingleMailResponse(
                    "delivered", d.recipientEmail(), d.invoiceNumber(), d.messageId(), null, false));
            case DeliveryResult.Failed f -> ResponseEntity.status(502).body(new SingleMailResponse(
                    "failed", f.recipientEmail(), f.invoiceNumber(), null, f.errorMessage(), f.retryable()));
            case DeliveryResult.Skipped s -> ResponseEntity.ok(new SingleMailResponse(
                    "skipped", s.recipientEmail(), s.invoiceNumber(), null, s.reason(), false));
        };
    }

    private Map<String, Object> buildTemplateVars(SingleInvoiceMailRequest r) {
        var vars = new HashMap<String, Object>();

        if (r.recipientName() != null)              vars.put("recipientName", r.recipientName());
        if (r.invoiceNumber() != null)               vars.put("invoiceNumber", r.invoiceNumber());
        if (r.invoiceDate() != null)                 vars.put("invoiceDate", r.invoiceDate().toString());
        if (r.dueDate() != null)                     vars.put("dueDate", r.dueDate().toString());
        if (r.totalAmount() != null)                 vars.put("totalAmount", r.totalAmount().toPlainString());
        if (r.vatAmount() != null)                   vars.put("vatAmount", r.vatAmount().toPlainString());
        if (r.currency() != null) {
            vars.put("currency", r.currency());
            vars.put("currencySymbol",
                    com.esolutions.massmailer.domain.model.ZimbabweCurrency.symbolFor(r.currency()));
        }
        if (r.fiscalDeviceSerialNumber() != null)    vars.put("fiscalDeviceSerialNumber", r.fiscalDeviceSerialNumber());
        if (r.fiscalDayNumber() != null)             vars.put("fiscalDayNumber", r.fiscalDayNumber());
        if (r.globalInvoiceCounter() != null)        vars.put("globalInvoiceCounter", r.globalInvoiceCounter());
        if (r.verificationCode() != null)            vars.put("verificationCode", r.verificationCode());
        if (r.qrCodeUrl() != null)                   vars.put("qrCodeUrl", r.qrCodeUrl());

        if (r.variables() != null) vars.putAll(r.variables());

        return vars;
    }
}

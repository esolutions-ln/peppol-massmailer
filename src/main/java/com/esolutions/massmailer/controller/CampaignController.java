package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.dto.MailDtos.*;
import com.esolutions.massmailer.service.CampaignOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API for mass-mailing fiscalised invoice PDFs.
 *
 * <h3>Integration Flow (ERP → Mass Mailer)</h3>
 * <ol>
 *   <li>ERP generates and fiscalises invoices → writes PDFs to shared volume</li>
 *   <li>ERP calls {@code POST /api/v1/campaigns} with invoice metadata + PDF paths per recipient</li>
 *   <li>Mass Mailer dispatches asynchronously — each recipient gets a personalised HTML email
 *       with their invoice PDF attached</li>
 *   <li>ERP polls {@code GET /api/v1/campaigns/{id}} to confirm delivery status</li>
 *   <li>If any failed, ERP calls {@code POST /api/v1/campaigns/{id}/retry}</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/campaigns")
@Tag(name = "Invoice Campaigns")
public class CampaignController {

    private final CampaignOrchestrator orchestrator;

    public CampaignController(CampaignOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/campaigns — Create & dispatch invoice campaign
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Create and dispatch a mass invoice email campaign",
            description = """
                    Creates a new campaign with one or more invoice recipients, each carrying \
                    their own PDF attachment (via file path or Base64). The campaign is immediately \
                    queued for asynchronous dispatch on virtual threads.

                    **PDF Attachment Modes** — supply ONE per recipient:
                    - `pdfFilePath`: Absolute path on a shared filesystem (e.g. `/var/lib/odoo/invoices/INV-001.pdf`)
                    - `pdfBase64`: Base64-encoded PDF bytes in the JSON payload

                    **Template**: Use `"invoice"` for the built-in fiscalised invoice template, \
                    or create custom templates under `templates/email/`.

                    **Fiscal fields**: `fiscalDeviceSerialNumber`, `fiscalDayNumber`, `globalInvoiceCounter`, \
                    `verificationCode`, and `qrCodeUrl` are rendered in the email body for ZIMRA compliance.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Campaign accepted and queued for dispatch",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CampaignCreatedResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "campaignId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                                      "message": "Invoice campaign queued for dispatch",
                                      "recipientCount": 3
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error — missing required fields, invalid email, etc.",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "status": 400,
                                      "error": "Validation Failed",
                                      "message": "recipients[0].invoiceNumber: must not be blank",
                                      "path": "/api/v1/campaigns"
                                    }
                                    """)
                    )
            )
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Campaign definition with invoice recipients and PDF attachments",
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CampaignRequest.class),
                    examples = {
                            @ExampleObject(
                                    name = "File Path Mode",
                                    summary = "PDFs on shared filesystem (typical ERP integration)",
                                    value = """
                                            {
                                              "name": "March 2026 Invoices",
                                              "subject": "Your Invoice from eSolutions",
                                              "templateName": "invoice",
                                              "templateVariables": {
                                                "companyName": "eSolutions",
                                                "accountsEmail": "accounts@esolutions.co.zw",
                                                "companyAddress": "123 Samora Machel Ave, Harare"
                                              },
                                              "recipients": [
                                                {
                                                  "email": "alice@acmecorp.co.zw",
                                                  "name": "Alice Moyo",
                                                  "invoiceNumber": "INV-2026-0100",
                                                  "invoiceDate": "2026-03-01",
                                                  "dueDate": "2026-03-31",
                                                  "totalAmount": 2400.00,
                                                  "vatAmount": 360.00,
                                                  "currency": "USD",
                                                  "fiscalDeviceSerialNumber": "FD-SN-12345",
                                                  "fiscalDayNumber": "60",
                                                  "globalInvoiceCounter": "10001",
                                                  "verificationCode": "AAAA-BBBB-1111",
                                                  "qrCodeUrl": "https://fdms.zimra.co.zw/verify?code=AAAA-BBBB-1111",
                                                  "pdfFilePath": "/var/lib/odoo/invoices/INV-2026-0100.pdf",
                                                  "pdfFileName": "INV-2026-0100.pdf"
                                                },
                                                {
                                                  "email": "bob@vendorltd.co.zw",
                                                  "name": "Bob Chirwa",
                                                  "invoiceNumber": "INV-2026-0101",
                                                  "invoiceDate": "2026-03-01",
                                                  "dueDate": "2026-03-31",
                                                  "totalAmount": 850.00,
                                                  "vatAmount": 127.50,
                                                  "currency": "USD",
                                                  "fiscalDeviceSerialNumber": "FD-SN-12345",
                                                  "fiscalDayNumber": "60",
                                                  "globalInvoiceCounter": "10002",
                                                  "verificationCode": "CCCC-DDDD-2222",
                                                  "pdfFilePath": "/var/lib/odoo/invoices/INV-2026-0101.pdf"
                                                }
                                              ]
                                            }
                                            """
                            ),
                            @ExampleObject(
                                    name = "Base64 Mode",
                                    summary = "PDF bytes inline (API upload from remote ERP)",
                                    value = """
                                            {
                                              "name": "Single Invoice Batch",
                                              "subject": "Your Fiscalised Invoice",
                                              "templateName": "invoice",
                                              "templateVariables": {
                                                "companyName": "InvoiceDirect"
                                              },
                                              "recipients": [
                                                {
                                                  "email": "customer@example.com",
                                                  "name": "Jane Smith",
                                                  "invoiceNumber": "INV-2026-0200",
                                                  "invoiceDate": "2026-03-24",
                                                  "dueDate": "2026-04-23",
                                                  "totalAmount": 5000.00,
                                                  "vatAmount": 750.00,
                                                  "currency": "ZWG",
                                                  "fiscalDeviceSerialNumber": "FD-SN-99999",
                                                  "verificationCode": "WXYZ-5678-ABCD",
                                                  "pdfBase64": "JVBERi0xLjQK... (Base64-encoded PDF bytes)",
                                                  "pdfFileName": "INV-2026-0200.pdf"
                                                }
                                              ]
                                            }
                                            """
                            )
                    }
            )
    )
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CampaignCreatedResponse> createAndDispatch(
            @Valid @RequestBody CampaignRequest request) {

        var campaign = orchestrator.createCampaign(request);
        orchestrator.dispatchCampaign(campaign.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CampaignCreatedResponse(
                        campaign.getId(),
                        "Invoice campaign queued for dispatch",
                        campaign.getTotalRecipients()
                ));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/campaigns/{id} — Poll campaign delivery status
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Get campaign delivery status",
            description = """
                    Returns the current delivery status of a campaign including counts \
                    of sent, failed, and skipped invoices. The ERP should poll this \
                    endpoint after creating a campaign to track progress.

                    **Status values**: `CREATED`, `QUEUED`, `IN_PROGRESS`, `COMPLETED`, \
                    `PARTIALLY_FAILED`, `FAILED`, `CANCELLED`
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Campaign status retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CampaignResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                                      "name": "March 2026 Invoices",
                                      "status": "COMPLETED",
                                      "totalRecipients": 3,
                                      "sent": 3,
                                      "failed": 0,
                                      "skipped": 0,
                                      "createdAt": "2026-03-24T08:00:00Z",
                                      "startedAt": "2026-03-24T08:00:01Z",
                                      "completedAt": "2026-03-24T08:00:12Z"
                                    }
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Campaign not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CampaignResponse> getStatus(
            @Parameter(description = "Campaign UUID returned from the create endpoint", required = true,
                    example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            @PathVariable UUID id) {
        return ResponseEntity.ok(orchestrator.getCampaignStatus(id));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/campaigns — List all campaigns
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "List all campaigns",
            description = "Returns all campaigns ordered by creation date (newest first). " +
                    "Useful for dashboard views or audit trails."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Campaign list retrieved",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CampaignResponse.class)
            )
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CampaignResponse>> listAll() {
        return ResponseEntity.ok(orchestrator.listCampaigns());
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/campaigns/{id}/retry — Retry failed invoices
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Retry failed invoice recipients",
            description = """
                    Re-dispatches all failed recipients in a campaign that have not \
                    exceeded the maximum retry count (default: 3). The retry runs \
                    asynchronously — poll the campaign status endpoint to track progress.

                    **Common failure reasons that benefit from retry:**
                    - SMTP connection timeout (transient)
                    - Rate limit exceeded at provider (transient)
                    - Temporary DNS resolution failure

                    **Failures that will NOT succeed on retry:**
                    - Invalid recipient email address
                    - Missing or corrupt PDF attachment
                    - Permanent SMTP rejection (550 errors)
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Retry accepted and queued"),
            @ApiResponse(
                    responseCode = "404",
                    description = "Campaign not found",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    @PostMapping(value = "/{id}/retry")
    public ResponseEntity<Void> retryFailed(
            @Parameter(description = "Campaign UUID", required = true)
            @PathVariable UUID id) {
        orchestrator.retryFailed(id);
        return ResponseEntity.accepted().build();
    }
}

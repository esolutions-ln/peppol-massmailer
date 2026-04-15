package com.esolutions.massmailer.controller;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.domain.model.CanonicalInvoice;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.ErpSource;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.FiscalMetadata;
import com.esolutions.massmailer.domain.model.CanonicalInvoice.PdfSource;
import com.esolutions.massmailer.domain.ports.ErpInvoicePort;
import com.esolutions.massmailer.dto.MailDtos.*;
import com.esolutions.massmailer.infrastructure.adapters.common.ErpAdapterRegistry;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord;
import com.esolutions.massmailer.peppol.service.PeppolDeliveryService;
import com.esolutions.massmailer.service.CampaignOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ERP-driven invoice mailing with customer registry enforcement.
 *
 * <h3>Customer Registry Flow</h3>
 * <p>Before any invoice is dispatched, every recipient is upserted into the
 * persistent customer contact registry scoped to the sending organization.
 * This ensures:</p>
 * <ul>
 *   <li>Every customer who receives an invoice has a traceable record</li>
 *   <li>Delivery statistics accumulate per customer across campaigns</li>
 *   <li>Unsubscribed customers can be blocked before dispatch</li>
 *   <li>Customer data is enriched from ERP on each dispatch</li>
 * </ul>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/v1/erp/dispatch} — ERP pulls invoice IDs, mailer fetches from ERP</li>
 *   <li>{@code POST /api/v1/erp/dispatch/upload} — Client pushes multiple PDFs as multipart</li>
 *   <li>{@code GET  /api/v1/erp/adapters} — List active ERP adapters</li>
 *   <li>{@code GET  /api/v1/erp/health/{erpSource}} — ERP connectivity check</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/erp")
@Tag(name = "ERP-Driven Invoice Dispatch")
public class ErpCampaignController {

    private static final Logger log = LoggerFactory.getLogger(ErpCampaignController.class);

    private final ErpAdapterRegistry adapterRegistry;
    private final CampaignOrchestrator orchestrator;
    private final CustomerContactService customerService;
    private final CustomerContactRepository customerRepo;
    private final PeppolDeliveryService peppolService;
    private final OrganizationRepository orgRepo;
    private final ObjectMapper objectMapper;

    public ErpCampaignController(ErpAdapterRegistry adapterRegistry,
                                  CampaignOrchestrator orchestrator,
                                  CustomerContactService customerService,
                                  CustomerContactRepository customerRepo,
                                  PeppolDeliveryService peppolService,
                                  OrganizationRepository orgRepo,
                                  ObjectMapper objectMapper) {
        this.adapterRegistry = adapterRegistry;
        this.orchestrator = orchestrator;
        this.customerService = customerService;
        this.customerRepo = customerRepo;
        this.peppolService = peppolService;
        this.orgRepo = orgRepo;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/erp/dispatch — Fetch from ERP + register + dispatch
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Fetch invoices from ERP, register customers, and dispatch campaign",
            description = """
                    Fetches invoice data and PDFs from the specified ERP system, then:

                    1. **Validates** all fetched invoices have recipient email addresses
                    2. **Registers** each recipient in the customer contact registry (upsert)
                    3. **Creates** a campaign and dispatches invoice emails

                    Provide `organizationId` to enable customer registration. Without it,
                    dispatch still works but customers are not persisted to the registry.

                    ## Supported ERP Systems

                    | ERP Source | Adapter | PDF Resolution |
                    |---|---|---|
                    | `SAGE_INTACCT` | Sage XML API → ARINVOICE | Base64 from API |
                    | `QUICKBOOKS_ONLINE` | QB REST API → Invoice | `/invoice/{id}/pdf` |
                    | `DYNAMICS_365` | D365 OData → SalesInvoiceHeadersV2 | Document Management |
                    | `ODOO` | Odoo JSON-RPC → account.move | `/report/pdf/account.report_invoice/{id}` |
                    | `GENERIC_API` | No ERP fetch — use `/api/v1/erp/dispatch/upload` instead | n/a |
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Campaign accepted — customers registered and invoices queued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CampaignCreatedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error or missing recipient emails",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "ERP connectivity failure",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErpDispatchRequest.class),
                    examples = {
                            @ExampleObject(name = "Sage Intacct", value = """
                                    {
                                      "campaignName": "Sage March Invoices",
                                      "subject": "Your Invoice from eSolutions",
                                      "templateName": "invoice",
                                      "erpSource": "SAGE_INTACCT",
                                      "tenantId": "ESOLUTIONS_ZW",
                                      "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                                      "invoiceIds": ["INV-2026-0100", "INV-2026-0101"],
                                      "templateVariables": {
                                        "companyName": "eSolutions",
                                        "accountsEmail": "accounts@esolutions.co.zw"
                                      }
                                    }
                                    """),
                            @ExampleObject(name = "QuickBooks Online", value = """
                                    {
                                      "campaignName": "QB March Invoices",
                                      "subject": "Your Invoice",
                                      "templateName": "invoice",
                                      "erpSource": "QUICKBOOKS_ONLINE",
                                      "tenantId": "4620816365182009070",
                                      "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                                      "invoiceIds": ["1045", "1046", "1047"],
                                      "templateVariables": {"companyName": "InvoiceDirect"}
                                    }
                                    """)
                    }))
    @PostMapping(value = "/dispatch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fetchAndDispatch(@Valid @RequestBody ErpDispatchRequest request) {

        // 1. Resolve the correct ERP adapter
        ErpInvoicePort adapter = adapterRegistry.getAdapter(request.erpSource());

        // 2. Fetch invoices from ERP (ACL normalises to canonical model)
        List<CanonicalInvoice> canonicalInvoices = adapter.fetchInvoices(
                request.tenantId(), request.invoiceIds());

        // 3. Register customers in the registry BEFORE dispatch
        if (request.organizationId() != null) {
            try {
                customerService.upsertAll(request.organizationId(), canonicalInvoices);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse(
                        400, "Customer Registration Failed", e.getMessage(), "/api/v1/erp/dispatch"));
            }
        }

        // 4. Route each invoice: PEPPOL (BIS 3.0) or EMAIL (PDF)
        return routeAndDispatch(request.organizationId(), canonicalInvoices,
                request.campaignName(), request.subject(),
                request.templateName(), request.templateVariables(),
                "/api/v1/erp/dispatch");
    }

    // ═══════════════════════════════════════════════════════════════
    //  POST /api/v1/erp/dispatch/upload — Multi-PDF upload + dispatch
    // ═══════════════════════════════════════════════════════════════

    @Operation(
            summary = "Upload multiple invoice PDFs and dispatch as a campaign",
            description = """
                    Accepts multiple PDF files as a `multipart/form-data` request alongside
                    a JSON metadata part describing each invoice and its recipient.

                    **Before dispatch**, every recipient is upserted into the customer
                    contact registry under the specified organization.

                    ## Request Parts

                    | Part | Type | Description |
                    |---|---|---|
                    | `metadata` | JSON string | `MultiPdfDispatchMetadata` — campaign info + per-invoice entries |
                    | `{invoiceNumber}` | file | PDF file for that invoice — part name must match `invoiceNumber` in metadata |

                    ## Example (curl)

                    ```bash
                    curl -X POST https://ap.invoicedirect.biz/api/v1/erp/dispatch/upload \\
                      -F 'metadata={
                        "campaignName":"March 2026 Invoices",
                        "subject":"Your Invoice from eSolutions",
                        "templateName":"invoice",
                        "organizationId":"d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                        "templateVariables":{"companyName":"eSolutions"},
                        "invoices":[
                          {"invoiceNumber":"INV-2026-0100","recipientEmail":"alice@acme.co.zw",
                           "recipientName":"Alice Moyo","totalAmount":2400.00,"currency":"USD"},
                          {"invoiceNumber":"INV-2026-0101","recipientEmail":"bob@vendor.co.zw",
                           "recipientName":"Bob Chirwa","totalAmount":850.00,"currency":"USD"}
                        ]
                      }' \\
                      -F "INV-2026-0100=@/path/to/INV-2026-0100.pdf" \\
                      -F "INV-2026-0101=@/path/to/INV-2026-0101.pdf"
                    ```
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Campaign accepted — customers registered, PDFs queued",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CampaignCreatedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing PDF parts, invalid metadata, or missing recipient emails",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/dispatch/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadAndDispatch(
            @RequestPart("metadata") String metadataJson,
            @RequestParam Map<String, MultipartFile> allParts) {

        // 1. Parse metadata
        MultiPdfDispatchMetadata metadata;
        try {
            metadata = objectMapper.readValue(metadataJson, MultiPdfDispatchMetadata.class);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    400, "Invalid Metadata", "Could not parse metadata JSON: " + e.getMessage(),
                    "/api/v1/erp/dispatch/upload"));
        }

        // 2. Validate all PDF parts are present before touching the DB
        List<String> missingPdfs = metadata.invoices().stream()
                .map(InvoiceUploadEntry::invoiceNumber)
                .filter(inv -> !allParts.containsKey(inv) || allParts.get(inv).isEmpty())
                .toList();

        if (!missingPdfs.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    400, "Missing PDF Parts",
                    "No PDF file part found for invoice(s): " + missingPdfs
                            + ". Each invoiceNumber must have a matching multipart file part.",
                    "/api/v1/erp/dispatch/upload"));
        }

        // 3. Build canonical invoices from uploaded PDFs + metadata
        List<CanonicalInvoice> canonicalInvoices = new ArrayList<>();
        for (InvoiceUploadEntry entry : metadata.invoices()) {
            MultipartFile pdfFile = allParts.get(entry.invoiceNumber());
            byte[] pdfBytes;
            try {
                pdfBytes = pdfFile.getBytes();
            } catch (IOException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse(
                        400, "PDF Read Error",
                        "Failed to read PDF for invoice " + entry.invoiceNumber() + ": " + e.getMessage(),
                        "/api/v1/erp/dispatch/upload"));
            }

            String base64Pdf = Base64.getEncoder().encodeToString(pdfBytes);
            String fileName = pdfFile.getOriginalFilename() != null
                    ? pdfFile.getOriginalFilename()
                    : entry.invoiceNumber() + ".pdf";

            canonicalInvoices.add(new CanonicalInvoice(
                    ErpSource.GENERIC_API,
                    metadata.organizationId() != null ? metadata.organizationId().toString() : null,
                    entry.invoiceNumber(),
                    entry.recipientEmail(),
                    entry.recipientName(),
                    entry.recipientCompany(),
                    entry.invoiceNumber(),
                    entry.invoiceDate() != null ? entry.invoiceDate() : LocalDate.now(),
                    entry.dueDate(),
                    null,
                    entry.vatAmount(),
                    entry.totalAmount(),
                    entry.currency(),
                    new FiscalMetadata(
                            entry.fiscalDeviceSerialNumber(),
                            entry.fiscalDayNumber(),
                            entry.globalInvoiceCounter(),
                            entry.verificationCode(),
                            entry.qrCodeUrl()
                    ),
                    new PdfSource(null, base64Pdf, null, fileName),
                    Map.of()
            ));
        }

        // 4. Register customers in the registry BEFORE dispatch
        if (metadata.organizationId() != null) {
            try {
                customerService.upsertAll(metadata.organizationId(), canonicalInvoices);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(new ErrorResponse(
                        400, "Customer Registration Failed", e.getMessage(),
                        "/api/v1/erp/dispatch/upload"));
            }
        }

        // 5. Route each invoice: PEPPOL or EMAIL
        return routeAndDispatch(metadata.organizationId(), canonicalInvoices,
                metadata.campaignName(), metadata.subject(),
                metadata.templateName(), metadata.templateVariables(),
                "/api/v1/erp/dispatch/upload");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shared dual-channel router: PEPPOL (BIS 3.0) vs EMAIL (PDF)
    // ═══════════════════════════════════════════════════════════════

    private ResponseEntity<?> routeAndDispatch(UUID organizationId,
                                                List<CanonicalInvoice> invoices,
                                                String campaignName, String subject,
                                                String templateName,
                                                Map<String, Object> templateVars,
                                                String requestPath) {
        // Resolve org-level default delivery mode
        DeliveryMode orgMode = DeliveryMode.EMAIL;
        if (organizationId != null) {
            orgMode = orgRepo.findById(organizationId)
                    .map(org -> org.getDeliveryMode() != null ? org.getDeliveryMode() : DeliveryMode.EMAIL)
                    .orElse(DeliveryMode.EMAIL);
        }

        var peppolInvoices = new ArrayList<CanonicalInvoice>();
        var emailInvoices  = new ArrayList<CanonicalInvoice>();

        for (CanonicalInvoice inv : invoices) {
            // Resolve effective delivery mode: customer override → org default
            DeliveryMode effectiveMode = orgMode;
            if (organizationId != null && inv.recipientEmail() != null) {
                CustomerContact contact = customerRepo
                        .findByOrganizationIdAndEmail(organizationId,
                                inv.recipientEmail().trim().toLowerCase())
                        .orElse(null);
                if (contact != null && contact.getDeliveryMode() != null) {
                    effectiveMode = contact.getDeliveryMode();
                }
            }

            switch (effectiveMode) {
                case AS4  -> peppolInvoices.add(inv);
                case BOTH -> { peppolInvoices.add(inv); emailInvoices.add(inv); }
                default   -> emailInvoices.add(inv);  // EMAIL
            }
        }

        // ── PEPPOL channel ──
        var peppolResults = new ArrayList<Map<String, Object>>();
        for (CanonicalInvoice inv : peppolInvoices) {
            try {
                PeppolDeliveryRecord record = peppolService.deliver(organizationId, inv);
                peppolResults.add(Map.of(
                        "invoiceNumber", inv.invoiceNumber(),
                        "channel", "PEPPOL",
                        "status", record.getStatus().name(),
                        "endpoint", record.getDeliveredToEndpoint()
                ));
            } catch (Exception e) {
                log.error("PEPPOL dispatch failed for invoice {}: {}", inv.invoiceNumber(), e.getMessage());
                peppolResults.add(Map.of(
                        "invoiceNumber", inv.invoiceNumber(),
                        "channel", "PEPPOL",
                        "status", "FAILED",
                        "error", e.getMessage()
                ));
            }
        }

        // ── EMAIL channel ──
        UUID emailCampaignId = null;
        int emailCount = 0;
        if (!emailInvoices.isEmpty()) {
            var campaignRequest = orchestrator.fromCanonicalInvoices(
                    campaignName, subject, templateName, templateVars, organizationId, emailInvoices);
            var campaign = orchestrator.createCampaign(campaignRequest);
            orchestrator.dispatchCampaign(campaign.getId());
            emailCampaignId = campaign.getId();
            emailCount = campaign.getTotalRecipients();
        }

        var summary = new LinkedHashMap<String, Object>();
        summary.put("totalInvoices", invoices.size());
        summary.put("peppolDispatched", peppolInvoices.size());
        summary.put("emailDispatched", emailCount);
        if (emailCampaignId != null) summary.put("emailCampaignId", emailCampaignId);
        if (!peppolResults.isEmpty()) summary.put("peppolResults", peppolResults);
        summary.put("message", peppolInvoices.size() + " via PEPPOL BIS 3.0, "
                + emailCount + " via email PDF");

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(summary);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/erp/adapters
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "List registered ERP adapters",
            description = "Returns all ERP adapters currently active and configured.")
    @ApiResponse(responseCode = "200", description = "List of active ERP adapters",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                            {"registeredAdapters":["SAGE_INTACCT","QUICKBOOKS_ONLINE","DYNAMICS_365","GENERIC_API"],"count":4}
                            """)))
    @GetMapping(value = "/adapters", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> listAdapters() {
        Set<ErpSource> sources = adapterRegistry.registeredSources();
        return ResponseEntity.ok(Map.of("registeredAdapters", sources, "count", sources.size()));
    }

    // ═══════════════════════════════════════════════════════════════
    //  GET /api/v1/erp/health/{erpSource}
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Check ERP adapter health",
            description = "Verifies connectivity to the specified ERP system.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ERP is reachable"),
            @ApiResponse(responseCode = "503", description = "ERP is unreachable")
    })
    @GetMapping(value = "/health/{erpSource}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> checkHealth(
            @PathVariable ErpSource erpSource,
            @RequestParam(defaultValue = "default") String tenantId) {

        ErpInvoicePort adapter = adapterRegistry.getAdapter(erpSource);
        boolean healthy = adapter.healthCheck(tenantId);

        var body = Map.<String, Object>of("erpSource", erpSource, "tenantId", tenantId, "healthy", healthy);
        return healthy
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}

package com.esolutions.massmailer.customer.controller;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.service.CustomerContactService;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService.ImportResult;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService.PreviewResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.esolutions.massmailer.model.DeliveryMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer contact registry — manage the persistent list of invoice recipients
 * per organization. Customers are auto-registered on first invoice dispatch,
 * but can also be pre-registered here before any campaign is run.
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/customers")
@Tag(name = "Customer Registry")
public class CustomerContactController {

    private final CustomerContactService service;
    private final CustomerCsvImportService csvImport;
    private final ObjectMapper objectMapper;

    public CustomerContactController(CustomerContactService service,
                                     CustomerCsvImportService csvImport,
                                     ObjectMapper objectMapper) {
        this.service = service;
        this.csvImport = csvImport;
        this.objectMapper = objectMapper;
    }

    public record RegisterCustomerRequest(
            @NotBlank @Email @Schema(example = "alice@acmecorp.co.zw") String email,
            @Schema(example = "Alice Moyo") String name,
            @Schema(example = "+263 77 123 4567") String phone,
            @Schema(description = "Legal company name (registered entity)",
                    example = "G Tide Mobile Phone Zimbabwe Private Limited") String companyName,
            @Schema(description = "Trading-as name (distinct from legal name)",
                    example = "G-Tel Private Limited") String tradingName,
            @Schema(example = "SAGE_INTACCT") String erpSource,
            @Schema(description = "ERP-native customer key (e.g. Exor TenantCode, Sage CUSTOMERID)",
                    example = "10006502") String erpCustomerId,
            @Schema(description = "Delivery mode override; null = inherit from org") DeliveryMode deliveryMode,
            @Schema(description = "Zimbabwe VAT number", example = "220132956") String vatNumber,
            @Schema(description = "Zimbabwe TIN number (fallback if no VAT)", example = "1234567890") String tinNumber,
            @Schema(description = "ZIMRA Business Partner Number — preferred buyer key on fiscal invoices",
                    example = "2000480465") String bpn,
            @Schema(description = "PEPPOL participant ID — auto-derived from VAT/TIN if not supplied")
            String peppolParticipantId,
            @Schema(example = "100 Nelson Mandela Avenue") String addressLine1,
            @Schema(example = "") String addressLine2,
            @Schema(example = "Harare") String city,
            @Schema(example = "Zimbabwe") String country
    ) {}

    public record CustomerResponse(
            UUID id,
            UUID organizationId,
            String email,
            String name,
            String phone,
            String companyName,
            String tradingName,
            String erpCustomerId,
            String erpSource,
            DeliveryMode deliveryMode,
            String peppolParticipantId,
            String vatNumber,
            String tinNumber,
            String bpn,
            String addressLine1,
            String addressLine2,
            String city,
            String country,
            boolean unsubscribed,
            long totalInvoicesSent,
            long totalDeliveryFailures,
            Instant lastInvoiceSentAt,
            Instant createdAt
    ) {}

    @Operation(summary = "Pre-register a customer contact",
            description = """
                    Registers a customer in the registry before any invoice is sent. \
                    This is optional — customers are also auto-registered on first dispatch. \
                    Use this to pre-populate the registry from your CRM or ERP customer master.""")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> register(
            @PathVariable UUID orgId,
            @RequestBody RegisterCustomerRequest req) {
        var contact = service.upsertFull(orgId, req.email(), req.name(),
                req.companyName(), req.tradingName(),
                req.erpSource(), req.erpCustomerId(), req.phone(),
                req.deliveryMode(),
                req.vatNumber(), req.tinNumber(), req.bpn(),
                req.peppolParticipantId(),
                req.addressLine1(), req.addressLine2(), req.city(), req.country());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(contact));
    }

    public record UpdateCustomerRequest(
            @Email String email,
            String name,
            String phone,
            String companyName,
            String tradingName,
            String erpSource,
            String erpCustomerId,
            DeliveryMode deliveryMode,
            String vatNumber,
            String tinNumber,
            String bpn,
            String peppolParticipantId,
            String addressLine1,
            String addressLine2,
            String city,
            String country,
            Boolean unsubscribed
    ) {}

    @Operation(summary = "Update an existing customer by id",
            description = "Partial update: only non-null fields are applied. Blank strings clear the field.")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> update(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestBody UpdateCustomerRequest req) {
        var contact = service.updateById(orgId, id,
                req.email(), req.name(), req.phone(),
                req.companyName(), req.tradingName(),
                req.erpSource(), req.erpCustomerId(),
                req.deliveryMode(),
                req.vatNumber(), req.tinNumber(), req.bpn(),
                req.peppolParticipantId(),
                req.addressLine1(), req.addressLine2(), req.city(), req.country(),
                req.unsubscribed());
        return ResponseEntity.ok(toResponse(contact));
    }

    @Operation(summary = "List all customers for an organization")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CustomerResponse>> list(@PathVariable UUID orgId) {
        return ResponseEntity.ok(
                service.listByOrg(orgId).stream().map(this::toResponse).toList()
        );
    }

    @Operation(summary = "Look up a customer by email")
    @GetMapping(value = "/by-email", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> getByEmail(
            @PathVariable UUID orgId,
            @RequestParam String email) {
        var contact = service.getByEmail(orgId, email);
        return contact != null
                ? ResponseEntity.ok(toResponse(contact))
                : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Bulk import customers from CSV",
            description = """
                    Upload a CSV file to register or update customers in bulk.

                    **Required column**: `email`.
                    **Optional columns**: `name`, `phone`, `companyName`, `tradingName`, `vatNumber`, `tinNumber`, \
                    `bpn`, `addressLine1`, `addressLine2`, `city`, `country`, `deliveryMode` \
                    (EMAIL/AS4/BOTH), `erpSource`, `erpCustomerId`, `peppolParticipantId`.

                    Header row is required; column order is free. Quoted fields and escaped quotes are supported. \
                    **Column 1 of every row (including the header) is treated as a row label and is not imported** — \
                    place an ID/sequence column there if you like, but the importer always ignores it. \
                    Rows that fail validation are skipped and reported in the response — the import does not abort.""")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImportResult> importCsv(
            @PathVariable UUID orgId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "mapping", required = false) String mappingJson) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        Map<String, String> mapping = null;
        if (mappingJson != null && !mappingJson.isBlank()) {
            mapping = objectMapper.readValue(mappingJson, new TypeReference<Map<String, String>>() {});
        }
        try (var in = file.getInputStream()) {
            return ResponseEntity.ok(csvImport.importCsv(orgId, in, mapping));
        }
    }

    @Operation(summary = "Preview a CSV before importing",
            description = """
                    Parses the header row and the first 5 data rows of an uploaded CSV and returns:
                    - `columns`: the column headers (column 1 stripped)
                    - `sampleRows`: up to 5 data rows for the mapping UI to display
                    - `suggestedMapping`: best-guess mapping of target field → source column, \
                    based on header-name heuristics (e.g. `LesseeName` → `companyName`).

                    Use this to drive a field-mapping wizard, then POST `/import` with the file \
                    plus a JSON `mapping` part to perform the actual import.""")
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult> previewCsv(
            @PathVariable UUID orgId,
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try (var in = file.getInputStream()) {
            return ResponseEntity.ok(csvImport.preview(in, 5));
        }
    }

    @Operation(summary = "Look up a customer by BPN, VAT, or TIN",
            description = """
                    Searches the registry by BPN first (preferred — ZIMRA Business Partner Number), \
                    then VAT, then TIN, scoped to the organization. At least one identifier must be supplied. \
                    Returns 404 if no match.""")
    @GetMapping(value = "/by-tax-id", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> getByTaxId(
            @PathVariable UUID orgId,
            @RequestParam(required = false) String bpn,
            @RequestParam(required = false) String vatNumber,
            @RequestParam(required = false) String tinNumber) {
        boolean allBlank = (bpn == null || bpn.isBlank())
                && (vatNumber == null || vatNumber.isBlank())
                && (tinNumber == null || tinNumber.isBlank());
        if (allBlank) return ResponseEntity.badRequest().build();
        var contact = service.getByTaxId(orgId, bpn, vatNumber, tinNumber);
        return contact != null
                ? ResponseEntity.ok(toResponse(contact))
                : ResponseEntity.notFound().build();
    }

    private CustomerResponse toResponse(CustomerContact c) {
        return new CustomerResponse(
                c.getId(), c.getOrganizationId(), c.getEmail(), c.getName(),
                c.getPhone(),
                c.getCompanyName(), c.getTradingName(),
                c.getErpCustomerId(), c.getErpSource(),
                c.getDeliveryMode(), c.getPeppolParticipantId(),
                c.getVatNumber(), c.getTinNumber(), c.getBpn(),
                c.getAddressLine1(), c.getAddressLine2(), c.getCity(), c.getCountry(),
                c.isUnsubscribed(), c.getTotalInvoicesSent(), c.getTotalDeliveryFailures(),
                c.getLastInvoiceSentAt(), c.getCreatedAt()
        );
    }
}

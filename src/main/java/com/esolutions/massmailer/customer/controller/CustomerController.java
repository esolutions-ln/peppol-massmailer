package com.esolutions.massmailer.customer.controller;

import com.esolutions.massmailer.customer.model.Contact;
import com.esolutions.massmailer.customer.model.Customer;
import com.esolutions.massmailer.customer.repository.CustomerRepository;
import com.esolutions.massmailer.customer.service.ContactService;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService.ImportResult;
import com.esolutions.massmailer.customer.service.CustomerCsvImportService.PreviewResult;
import com.esolutions.massmailer.customer.service.CustomerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.esolutions.massmailer.model.DeliveryMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/customers")
@Tag(name = "Customer Registry")
public class CustomerController {

    private final CustomerService customerService;
    private final ContactService contactService;
    private final CustomerRepository customerRepo;
    private final CustomerCsvImportService csvImport;
    private final ObjectMapper objectMapper;

    public CustomerController(CustomerService customerService,
                              ContactService contactService,
                              CustomerRepository customerRepo,
                              CustomerCsvImportService csvImport,
                              ObjectMapper objectMapper) {
        this.customerService = customerService;
        this.contactService = contactService;
        this.customerRepo = customerRepo;
        this.csvImport = csvImport;
        this.objectMapper = objectMapper;
    }

    public record ContactResponse(
            UUID id,
            String email,
            String name,
            String phone
    ) {}

    public record CustomerResponse(
            UUID id,
            UUID organizationId,
            String erpCustomerId,
            String companyName,
            String tradingName,
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
            Instant createdAt,
            List<ContactResponse> contacts
    ) {}

    public record RegisterCustomerRequest(
            @NotBlank @Email @Schema(example = "alice@acmecorp.co.zw") String email,
            @Schema(example = "Alice Moyo") String name,
            @Schema(example = "+263 77 123 4567") String phone,
            @Schema(description = "Legal company name (registered entity)",
                    example = "G Tide Mobile Phone Zimbabwe Private Limited") String companyName,
            @Schema(description = "Trading-as name (distinct from legal name)",
                    example = "G-Tel Private Limited") String tradingName,
            @Schema(example = "SAGE_INTACCT") String erpSource,
            @Schema(description = "ERP-native customer key (unique system-wide)",
                    example = "10006502") @NotBlank String erpCustomerId,
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

    @Operation(summary = "Register a customer with contacts")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> register(
            @PathVariable UUID orgId,
            @RequestBody RegisterCustomerRequest req) {
        var result = customerService.upsertByErpCustomerId(orgId, req.erpCustomerId(),
                req.companyName(), req.tradingName(), req.erpSource(),
                req.deliveryMode(),
                req.vatNumber(), req.tinNumber(), req.bpn(),
                req.peppolParticipantId(),
                req.addressLine1(), req.addressLine2(), req.city(), req.country());
        var customer = result.customer();
        if (req.email() != null && !req.email().isBlank()) {
            contactService.upsert(customer.getId(), req.email(), req.name(), req.phone());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(customer));
    }

    public record AddContactRequest(
            @NotBlank @Email @Schema(example = "bob@acmecorp.co.zw") String email,
            @Schema(example = "Bob Moyo") String name,
            @Schema(example = "+263 77 987 6543") String phone
    ) {}

    @Operation(summary = "Add a new contact to an existing customer")
    @PostMapping(value = "/{id}/contacts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> addContact(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestBody @Valid AddContactRequest req) {
        var customer = customerRepo.findById(id)
                .filter(c -> c.getOrganizationId().equals(orgId))
                .orElse(null);
        if (customer == null) return ResponseEntity.notFound().build();
        contactService.upsert(customer.getId(), req.email(), req.name(), req.phone());
        customer = customerRepo.findById(id).orElse(null);
        return customer != null
                ? ResponseEntity.ok(toResponse(customer))
                : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Update a customer by id")
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> update(
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestBody UpdateCustomerRequest req) {
        var customer = customerService.updateById(orgId, id,
                req.companyName(), req.tradingName(),
                req.erpSource(), req.erpCustomerId(),
                req.deliveryMode(),
                req.vatNumber(), req.tinNumber(), req.bpn(),
                req.peppolParticipantId(),
                req.addressLine1(), req.addressLine2(), req.city(), req.country(),
                req.unsubscribed());
        if (req.email() != null && !req.email().isBlank()) {
            contactService.upsert(customer.getId(), req.email(), req.name(), req.phone());
        }
        return ResponseEntity.ok(toResponse(customer));
    }

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "companyName", "tradingName", "erpCustomerId", "city", "country",
            "vatNumber", "tinNumber", "bpn", "deliveryMode", "erpSource",
            "totalInvoicesSent", "lastInvoiceSentAt", "createdAt"
    );

    @Operation(summary = "List customers for an organization with pagination, sorting, and search")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Page<CustomerResponse>> list(
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "companyName") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "") String search) {
        String sortField = ALLOWED_SORT_FIELDS.contains(sort) ? sort : "companyName";
        Sort sortObj = Sort.by(Sort.Direction.fromString(dir), sortField);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), sortObj);
        return ResponseEntity.ok(
                customerService.listByOrg(orgId, search, pageable).map(this::toResponse)
        );
    }

    @Operation(summary = "Look up a customer by contact email")
    @GetMapping(value = "/by-email", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomerResponse> getByEmail(
            @PathVariable UUID orgId,
            @RequestParam String email) {
        var contact = contactService.findByEmail(email);
        if (contact.isEmpty()) return ResponseEntity.notFound().build();
        var customer = customerRepo.findById(contact.get().getCustomerId()).orElse(null);
        if (customer == null || !customer.getOrganizationId().equals(orgId))
            return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toResponse(customer));
    }

    @Operation(summary = "Bulk import customers from CSV")
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

    @Operation(summary = "Preview a CSV before importing")
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

    @Operation(summary = "Look up a customer by BPN, VAT, or TIN")
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
        var customer = customerService.getByTaxId(orgId, bpn, vatNumber, tinNumber);
        return customer != null
                ? ResponseEntity.ok(toResponse(customer))
                : ResponseEntity.notFound().build();
    }

    private CustomerResponse toResponse(Customer c) {
        var contacts = contactService.findByCustomerId(c.getId()).stream()
                .map(this::toContactResponse)
                .toList();
        return new CustomerResponse(
                c.getId(), c.getOrganizationId(),
                c.getErpCustomerId(), c.getCompanyName(), c.getTradingName(),
                c.getErpSource(), c.getDeliveryMode(), c.getPeppolParticipantId(),
                c.getVatNumber(), c.getTinNumber(), c.getBpn(),
                c.getAddressLine1(), c.getAddressLine2(), c.getCity(), c.getCountry(),
                c.isUnsubscribed(), c.getTotalInvoicesSent(), c.getTotalDeliveryFailures(),
                c.getLastInvoiceSentAt(), c.getCreatedAt(),
                contacts
        );
    }

    private ContactResponse toContactResponse(Contact c) {
        return new ContactResponse(c.getId(), c.getEmail(), c.getName(), c.getPhone());
    }
}

package com.esolutions.massmailer.customer.controller;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.service.CustomerContactService;
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

import java.time.Instant;
import java.util.List;
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

    public CustomerContactController(CustomerContactService service) {
        this.service = service;
    }

    public record RegisterCustomerRequest(
            @NotBlank @Email @Schema(example = "alice@acmecorp.co.zw") String email,
            @Schema(example = "Alice Moyo") String name,
            @Schema(example = "Acme Corporation") String companyName,
            @Schema(example = "SAGE_INTACCT") String erpSource,
            @Schema(description = "Delivery mode override; null = inherit from org") DeliveryMode deliveryMode,
            @Schema(description = "Zimbabwe VAT number", example = "12345678") String vatNumber,
            @Schema(description = "Zimbabwe TIN number (fallback if no VAT)", example = "1234567890") String tinNumber,
            @Schema(description = "PEPPOL participant ID — auto-derived from VAT/TIN if not supplied")
            String peppolParticipantId
    ) {}

    public record CustomerResponse(
            UUID id,
            UUID organizationId,
            String email,
            String name,
            String companyName,
            String erpCustomerId,
            String erpSource,
            DeliveryMode deliveryMode,
            String peppolParticipantId,
            String vatNumber,
            String tinNumber,
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
        var contact = service.upsert(orgId, req.email(), req.name(), req.companyName(),
                req.erpSource(), req.deliveryMode(), req.vatNumber(), req.tinNumber(),
                req.peppolParticipantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(contact));
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

    private CustomerResponse toResponse(CustomerContact c) {
        return new CustomerResponse(
                c.getId(), c.getOrganizationId(), c.getEmail(), c.getName(),
                c.getCompanyName(), c.getErpCustomerId(), c.getErpSource(),
                c.getDeliveryMode(), c.getPeppolParticipantId(),
                c.getVatNumber(), c.getTinNumber(),
                c.isUnsubscribed(), c.getTotalInvoicesSent(), c.getTotalDeliveryFailures(),
                c.getLastInvoiceSentAt(), c.getCreatedAt()
        );
    }
}

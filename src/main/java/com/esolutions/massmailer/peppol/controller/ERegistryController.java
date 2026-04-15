package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointRole;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointStatus;
import com.esolutions.massmailer.peppol.model.PeppolParticipantLink;
import com.esolutions.massmailer.peppol.model.PeppolParticipantLink.DeliveryChannel;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import com.esolutions.massmailer.peppol.repository.PeppolParticipantLinkRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * eRegistry — PEPPOL Access Point registration and participant routing configuration.
 *
 * <h3>What is the eRegistry?</h3>
 * <p>The eRegistry is the local equivalent of the PEPPOL SMP (Service Metadata Publisher).
 * It stores:</p>
 * <ul>
 *   <li><b>Access Points</b> — the AP endpoints for senders (C2) and receivers (C3)</li>
 *   <li><b>Participant Links</b> — maps a customer contact to their PEPPOL participant ID
 *       and preferred delivery channel (PEPPOL or EMAIL)</li>
 * </ul>
 *
 * <h3>Setup Flow</h3>
 * <ol>
 *   <li>Register your own AP gateway: {@code POST /api/v1/eregistry/access-points} (role=GATEWAY)</li>
 *   <li>Register each buyer's AP: {@code POST /api/v1/eregistry/access-points} (role=RECEIVER)</li>
 *   <li>Link a customer to their AP: {@code POST /api/v1/eregistry/participant-links}</li>
 *   <li>Dispatch invoices — the router automatically uses PEPPOL for linked customers</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/eregistry")
@Tag(name = "eRegistry — PEPPOL Access Points")
public class ERegistryController {

    private final AccessPointRepository apRepo;
    private final PeppolParticipantLinkRepository linkRepo;
    private final PeppolDeliveryRecordRepository deliveryRepo;
    private final CustomerContactRepository customerRepo;

    public ERegistryController(AccessPointRepository apRepo,
                                PeppolParticipantLinkRepository linkRepo,
                                PeppolDeliveryRecordRepository deliveryRepo,
                                CustomerContactRepository customerRepo) {
        this.apRepo = apRepo;
        this.linkRepo = linkRepo;
        this.deliveryRepo = deliveryRepo;
        this.customerRepo = customerRepo;
    }

    // ── DTOs ──

    public record RegisterAccessPointRequest(
            @Schema(description = "Organization ID (null for external APs)", example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID organizationId,

            @NotBlank @Schema(description = "PEPPOL participant ID — format: {scheme}:{value}", example = "0190:ZW123456789")
            String participantId,

            @NotBlank @Schema(example = "eSolutions (Sender AP)")
            String participantName,

            @NotNull @Schema(description = "SENDER=C2 outbound, RECEIVER=C3 inbound, GATEWAY=both")
            AccessPointRole role,

            @NotBlank @Schema(description = "AS4 or HTTP endpoint URL",
                    example = "https://ap.invoicedirect.biz/peppol/as4/receive")
            String endpointUrl,

            @Schema(description = "Use simplified HTTP POST instead of full AS4. Set true for private networks.",
                    example = "true")
            boolean simplifiedHttpDelivery,

            @Schema(description = "Bearer token for simplified HTTP delivery auth", example = "eyJhbGci...")
            String deliveryAuthToken,

            @Schema(description = "X.509 certificate PEM (required for AS4)")
            String certificate
    ) {}

    public record AccessPointResponse(
            UUID id, UUID organizationId, String participantId, String participantName,
            String role, String endpointUrl, boolean simplifiedHttpDelivery,
            String status, Instant registeredAt
    ) {}

    public record RegisterParticipantLinkRequest(
            @NotNull @Schema(description = "Sending organization ID", example = "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f")
            UUID organizationId,

            @NotBlank @Schema(description = "Customer email address — resolved to customer contact ID",
                    example = "buyer@acmecorp.co.zw")
            String customerEmail,

            @NotBlank @Schema(description = "Buyer's PEPPOL participant ID", example = "0190:ZW987654321")
            String participantId,

            @NotNull @Schema(description = "Receiver's Access Point ID from the eRegistry",
                    example = "b2c3d4e5-f6a7-8901-bcde-f12345678901")
            UUID receiverAccessPointId,

            @Schema(description = "Preferred delivery channel. PEPPOL=BIS 3.0 UBL, EMAIL=PDF email",
                    example = "PEPPOL")
            DeliveryChannel preferredChannel
    ) {}

    public record ParticipantLinkResponse(
            UUID id, UUID organizationId, UUID customerContactId,
            String customerEmail,
            String participantId, UUID receiverAccessPointId,
            String receiverApName,
            String preferredChannel, Instant createdAt
    ) {}

    // ═══════════════════════════════════════════════════════════════
    //  Access Point CRUD
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Register an Access Point in the eRegistry",
            description = """
                    Registers a PEPPOL Access Point. Use this to define:

                    - **Your own AP gateway** (role=`GATEWAY`) — the C2 endpoint this system
                      uses to send documents. Set `endpointUrl` to your AS4/HTTP receive URL.
                    - **A buyer's AP** (role=`RECEIVER`) — the C3 endpoint where invoices
                      are delivered to the buyer's ERP. Obtained from the buyer or their AP provider.
                    - **A supplier AP** (role=`SENDER`) — for registering other senders in the network.

                    After registering a RECEIVER AP, link it to a customer contact using
                    `POST /api/v1/eregistry/participant-links`.
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = {
                    @ExampleObject(name = "Our Gateway AP (C2)", value = """
                            {
                              "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                              "participantId": "9915:esolutions",
                              "participantName": "eSolutions AP Gateway",
                              "role": "GATEWAY",
                              "endpointUrl": "https://ap.invoicedirect.biz/peppol/as4/receive",
                              "simplifiedHttpDelivery": true,
                              "deliveryAuthToken": null
                            }
                            """),
                    @ExampleObject(name = "Buyer's AP (C3)", value = """
                            {
                              "organizationId": null,
                              "participantId": "0190:ZW987654321",
                              "participantName": "Acme Corporation",
                              "role": "RECEIVER",
                              "endpointUrl": "https://erp.acmecorp.co.zw/peppol/receive",
                              "simplifiedHttpDelivery": true,
                              "deliveryAuthToken": "eyJhbGciOiJIUzI1NiJ9..."
                            }
                            """)
            }))
    @ApiResponse(responseCode = "201", description = "Access Point registered")
    @PostMapping(value = "/access-points",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerAccessPoint(@RequestBody RegisterAccessPointRequest req) {
        if (apRepo.existsByParticipantId(req.participantId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    "Participant ID already registered: " + req.participantId());
        }

        var ap = AccessPoint.builder()
                .organizationId(req.organizationId())
                .participantId(req.participantId())
                .participantName(req.participantName())
                .role(req.role())
                .endpointUrl(req.endpointUrl())
                .simplifiedHttpDelivery(req.simplifiedHttpDelivery())
                .deliveryAuthToken(req.deliveryAuthToken())
                .certificate(req.certificate())
                .build();

        apRepo.save(ap);
        return ResponseEntity.status(HttpStatus.CREATED).body(toApResponse(ap));
    }

    @Operation(summary = "List all Access Points")
    @GetMapping(value = "/access-points", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AccessPointResponse>> listAccessPoints(
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) AccessPointRole role) {

        List<AccessPoint> results;
        if (organizationId != null) {
            results = apRepo.findByOrganizationIdAndStatus(organizationId, AccessPointStatus.ACTIVE);
        } else if (role != null) {
            results = apRepo.findByRoleAndStatus(role, AccessPointStatus.ACTIVE);
        } else {
            results = apRepo.findAll();
        }

        return ResponseEntity.ok(results.stream().map(this::toApResponse).toList());
    }

    @Operation(summary = "Get Access Point by participant ID")
    @GetMapping(value = "/access-points/by-participant/{participantId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessPointResponse> getByParticipantId(@PathVariable String participantId) {
        return apRepo.findByParticipantId(participantId)
                .map(ap -> ResponseEntity.ok(toApResponse(ap)))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Suspend or reactivate an Access Point")
    @PatchMapping(value = "/access-points/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessPointResponse> updateStatus(
            @PathVariable UUID id,
            @RequestParam AccessPointStatus status) {
        return apRepo.findById(id).map(ap -> {
            ap.setStatus(status);
            ap.setUpdatedAt(Instant.now());
            apRepo.save(ap);
            return ResponseEntity.ok(toApResponse(ap));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Participant Links
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Link a customer contact to their PEPPOL Access Point",
            description = """
                    Creates a routing entry that maps a customer contact to their PEPPOL
                    participant ID and preferred delivery channel.

                    Once linked, the dispatch router will automatically:
                    - Send via **PEPPOL BIS 3.0** if `preferredChannel=PEPPOL`
                    - Fall back to **PDF email** if `preferredChannel=EMAIL`

                    The `receiverAccessPointId` must reference an existing RECEIVER AP
                    in the eRegistry. Register the buyer's AP first using
                    `POST /api/v1/eregistry/access-points`.
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(value = """
                    {
                      "organizationId": "d4f7a2c1-8b3e-4f5a-9c2d-1a2b3c4d5e6f",
                      "customerContactId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                      "participantId": "0190:ZW987654321",
                      "receiverAccessPointId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
                      "preferredChannel": "PEPPOL"
                    }
                    """)))
    @ApiResponse(responseCode = "201", description = "Participant link created")
    @PostMapping(value = "/participant-links",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerParticipantLink(@RequestBody RegisterParticipantLinkRequest req) {
        // Validate AP exists
        if (!apRepo.existsById(req.receiverAccessPointId())) {
            return ResponseEntity.badRequest().body(
                    "Receiver Access Point not found: " + req.receiverAccessPointId()
                    + ". Register it first via POST /api/v1/eregistry/access-points");
        }

        // Resolve customer email → UUID
        var customer = customerRepo.findByOrganizationIdAndEmail(req.organizationId(), req.customerEmail().trim().toLowerCase())
                .orElse(null);
        if (customer == null) {
            return ResponseEntity.badRequest().body(
                    "Customer not found for email: " + req.customerEmail()
                    + ". Register the customer first via POST /api/v1/organizations/{orgId}/customers");
        }

        var link = PeppolParticipantLink.builder()
                .organizationId(req.organizationId())
                .customerContactId(customer.getId())
                .participantId(req.participantId())
                .receiverAccessPointId(req.receiverAccessPointId())
                .preferredChannel(req.preferredChannel() != null ? req.preferredChannel() : DeliveryChannel.PEPPOL)
                .build();

        linkRepo.save(link);
        return ResponseEntity.status(HttpStatus.CREATED).body(toLinkResponse(link));
    }

    @Operation(summary = "List participant links for an organization, or get one by org + customer")
    @GetMapping(value = "/participant-links", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getParticipantLinks(
            @RequestParam UUID organizationId,
            @RequestParam(required = false) UUID customerContactId) {
        if (customerContactId != null) {
            return linkRepo.findByOrganizationIdAndCustomerContactId(organizationId, customerContactId)
                    .map(link -> ResponseEntity.ok(toLinkResponse(link)))
                    .orElse(ResponseEntity.notFound().build());
        }
        List<ParticipantLinkResponse> links = linkRepo
                .findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                .stream().map(this::toLinkResponse).toList();
        return ResponseEntity.ok(links);
    }

    @Operation(summary = "Delete a participant link",
            description = "Removes the PEPPOL routing entry for a customer. Returns 204 on success, 404 if not found.")
    @ApiResponse(responseCode = "204", description = "Participant link deleted")
    @ApiResponse(responseCode = "404", description = "Participant link not found")
    @DeleteMapping("/participant-links/{id}")
    public ResponseEntity<Void> deleteParticipantLink(@PathVariable UUID id) {
        if (!linkRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        linkRepo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Delivery Audit Log
    // ═══════════════════════════════════════════════════════════════

    @Operation(summary = "Get PEPPOL delivery history for an organization")
    @GetMapping(value = "/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getDeliveries(@RequestParam UUID organizationId) {
        return ResponseEntity.ok(
                deliveryRepo.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
    }

    // ── Mappers ──

    private AccessPointResponse toApResponse(AccessPoint ap) {
        return new AccessPointResponse(ap.getId(), ap.getOrganizationId(), ap.getParticipantId(),
                ap.getParticipantName(), ap.getRole().name(), ap.getEndpointUrl(),
                ap.isSimplifiedHttpDelivery(), ap.getStatus().name(), ap.getRegisteredAt());
    }

    private ParticipantLinkResponse toLinkResponse(PeppolParticipantLink l) {
        String customerEmail = customerRepo.findById(l.getCustomerContactId())
                .map(c -> c.getEmail()).orElse(null);
        String receiverApName = apRepo.findById(l.getReceiverAccessPointId())
                .map(ap -> ap.getParticipantName()).orElse(null);
        return new ParticipantLinkResponse(l.getId(), l.getOrganizationId(), l.getCustomerContactId(),
                customerEmail, l.getParticipantId(), l.getReceiverAccessPointId(),
                receiverApName, l.getPreferredChannel().name(), l.getCreatedAt());
    }
}

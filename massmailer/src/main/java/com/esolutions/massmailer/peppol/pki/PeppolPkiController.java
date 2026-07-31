package com.esolutions.massmailer.peppol.pki;

import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/peppol/certs")
@Tag(name = "Admin PEPPOL PKI")
public class PeppolPkiController {

    private final PeppolCredentialStore credentialStore;
    private final PeppolCertificateRepository certRepo;
    private final OrganizationRepository orgRepo;

    public PeppolPkiController(PeppolCredentialStore credentialStore,
                                PeppolCertificateRepository certRepo,
                                OrganizationRepository orgRepo) {
        this.credentialStore = credentialStore;
        this.certRepo = certRepo;
        this.orgRepo = orgRepo;
    }

    public record UploadRequest(
            @NotBlank String certificatePem,
            @NotBlank String privateKeyPem,
            String description
    ) {}

    public record CertResponse(
            UUID id,
            UUID organizationId,
            String issuerDn,
            String subjectDn,
            String serialNumber,
            Instant validFrom,
            Instant validTo,
            String status,
            Instant createdAt,
            Instant rotatedAt,
            String description
    ) {}

    public record LoadedKeyResponse(
            UUID certId,
            String subjectDn,
            String issuerDn,
            String serialNumber,
            Instant validFrom,
            Instant validTo,
            String status
    ) {}

    @Operation(summary = "Upload PEPPOL certificate and private key",
            description = "Stores the org's PEPPOL AP X.509 certificate and RSA private key. " +
                    "If an active certificate already exists, it is marked ROTATED.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertResponse> upload(
            @PathVariable UUID orgId,
            @RequestBody UploadRequest req) {
        requireOrg(orgId);
        var saved = credentialStore.storeCertificate(
                orgId, req.certificatePem(), req.privateKeyPem(), req.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @Operation(summary = "Rotate PEPPOL certificate",
            description = "Expires the current active certificate and stores a new one.")
    @PostMapping(value = "/rotate", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CertResponse> rotate(
            @PathVariable UUID orgId,
            @RequestBody UploadRequest req) {
        requireOrg(orgId);
        var saved = credentialStore.rotateCertificate(
                orgId, req.certificatePem(), req.privateKeyPem(), req.description());
        return ResponseEntity.ok(toResponse(saved));
    }

    @Operation(summary = "Get active certificate for org",
            description = "Returns the currently active (and valid) PEPPOL certificate details.")
    @GetMapping(value = "/active", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoadedKeyResponse> getActive(@PathVariable UUID orgId) {
        requireOrg(orgId);
        return credentialStore.loadActive(orgId)
                .map(m -> ResponseEntity.ok(toLoadedResponse(m)))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No active PEPPOL certificate for org " + orgId));
    }

    @Operation(summary = "List all certificates for org",
            description = "Returns all certificate records, most recent first.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<CertResponse> listCerts(@PathVariable UUID orgId) {
        requireOrg(orgId);
        return certRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId)
                .stream().map(this::toResponse).toList();
    }

    private void requireOrg(UUID orgId) {
        if (!orgRepo.existsById(orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found: " + orgId);
        }
    }

    private CertResponse toResponse(PeppolCertificate c) {
        return new CertResponse(
                c.getId(), c.getOrganizationId(),
                c.getIssuerDn(), c.getSubjectDn(), c.getSerialNumber(),
                c.getValidFrom(), c.getValidTo(),
                c.getStatus().name(), c.getCreatedAt(), c.getRotatedAt(),
                c.getDescription());
    }

    private LoadedKeyResponse toLoadedResponse(PeppolCredentialStore.LoadedKeyMaterial m) {
        return new LoadedKeyResponse(
                m.entity().getId(),
                m.entity().getSubjectDn(),
                m.entity().getIssuerDn(),
                m.entity().getSerialNumber(),
                m.entity().getValidFrom(),
                m.entity().getValidTo(),
                m.entity().getStatus().name());
    }
}

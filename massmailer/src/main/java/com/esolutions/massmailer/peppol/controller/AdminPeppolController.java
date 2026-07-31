package com.esolutions.massmailer.peppol.controller;

import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.esolutions.massmailer.peppol.model.AccessPoint;
import com.esolutions.massmailer.peppol.model.AccessPoint.AccessPointRole;
import com.esolutions.massmailer.peppol.repository.AccessPointRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/peppol")
@Tag(name = "Admin PEPPOL Onboarding")
public class AdminPeppolController {

    private final OrganizationRepository orgRepo;
    private final AccessPointRepository apRepo;

    public AdminPeppolController(OrganizationRepository orgRepo,
                                  AccessPointRepository apRepo) {
        this.orgRepo = orgRepo;
        this.apRepo = apRepo;
    }

    public record OnboardRequest(
            @NotNull DeliveryMode deliveryMode,
            @NotBlank String participantName,
            @NotBlank String endpointUrl,
            boolean simplifiedHttpDelivery,
            String peppolParticipantId,
            String certificate,
            String deliveryAuthToken
    ) {}

    public record OnboardResponse(
            UUID orgId,
            String peppolParticipantId,
            DeliveryMode deliveryMode,
            AccessPointResponse accessPoint
    ) {}

    public record AccessPointResponse(
            UUID id,
            String participantId,
            String participantName,
            String role,
            String endpointUrl,
            boolean simplifiedHttpDelivery,
            String status,
            Instant registeredAt
    ) {}

    @Operation(summary = "Onboard an organization to PEPPOL",
            description = "Sets the org's PEPPOL participant ID, delivery mode, and registers a GATEWAY Access Point in one call.")
    @PostMapping(value = "/onboard", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OnboardResponse> onboard(@PathVariable UUID orgId, @RequestBody OnboardRequest req) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found: " + orgId));

        if (req.deliveryMode() == DeliveryMode.EMAIL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PEPPOL onboarding requires deliveryMode AS4 or BOTH, not EMAIL");
        }

        if (apRepo.existsByParticipantId(req.peppolParticipantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Participant ID already registered as an Access Point: " + req.peppolParticipantId());
        }

        String participantId = req.peppolParticipantId() != null && !req.peppolParticipantId().isBlank()
                ? req.peppolParticipantId().trim()
                : deriveParticipantIdFromOrg(org);

        if (participantId == null || participantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No PEPPOL participant ID provided and org has no VAT/TIN to derive one. " +
                    "Set a peppolParticipantId explicitly or add VAT/TIN numbers to the org first.");
        }

        org.setPeppolParticipantId(participantId);
        org.setDeliveryMode(req.deliveryMode());
        orgRepo.save(org);

        var ap = AccessPoint.builder()
                .organizationId(orgId)
                .participantId(participantId)
                .participantName(req.participantName())
                .role(AccessPointRole.GATEWAY)
                .endpointUrl(req.endpointUrl())
                .simplifiedHttpDelivery(req.simplifiedHttpDelivery())
                .certificate(req.certificate())
                .deliveryAuthToken(req.deliveryAuthToken())
                .build();
        apRepo.save(ap);

        return ResponseEntity.status(HttpStatus.CREATED).body(new OnboardResponse(
                orgId,
                participantId,
                req.deliveryMode(),
                new AccessPointResponse(
                        ap.getId(),
                        ap.getParticipantId(),
                        ap.getParticipantName(),
                        ap.getRole().name(),
                        ap.getEndpointUrl(),
                        ap.isSimplifiedHttpDelivery(),
                        ap.getStatus().name(),
                        ap.getRegisteredAt()
                )
        ));
    }

    private String deriveParticipantIdFromOrg(Organization org) {
        if (org.getVatNumber() != null && !org.getVatNumber().isBlank()) {
            return "0190:ZW" + org.getVatNumber().trim();
        }
        if (org.getTinNumber() != null && !org.getTinNumber().isBlank()) {
            return "0190:ZW" + org.getTinNumber().trim();
        }
        return null;
    }
}

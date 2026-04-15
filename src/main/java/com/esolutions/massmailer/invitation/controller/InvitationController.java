package com.esolutions.massmailer.invitation.controller;

import com.esolutions.massmailer.invitation.service.InvitationService;
import com.esolutions.massmailer.invitation.service.dto.CompleteRegistrationRequest;
import com.esolutions.massmailer.invitation.service.dto.CompleteRegistrationResponse;
import com.esolutions.massmailer.invitation.service.dto.InvitationResponse;
import com.esolutions.massmailer.invitation.service.dto.SendInvitationRequest;
import com.esolutions.massmailer.invitation.service.dto.TokenValidationResponse;
import com.esolutions.massmailer.security.OrgPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for PEPPOL customer invitation management.
 *
 * <p>Authenticated endpoints (ROLE_ORG via X-API-Key):
 * <ul>
 *   <li>POST  /api/v1/my/invitations         — send invitation</li>
 *   <li>GET   /api/v1/my/invitations         — list invitations</li>
 *   <li>DELETE /api/v1/my/invitations/{id}   — cancel invitation</li>
 * </ul>
 *
 * <p>Public endpoints (no auth required):
 * <ul>
 *   <li>GET  /api/v1/invitations/{token}          — validate token</li>
 *   <li>POST /api/v1/invitations/{token}/complete  — complete registration</li>
 * </ul>
 */
@RestController
@Tag(name = "PEPPOL Invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    // ── Authenticated endpoints ──────────────────────────────────────────────

    @Operation(summary = "Send a PEPPOL invitation to a customer")
    @SecurityRequirement(name = "ApiKeyAuth")
    @PostMapping(value = "/api/v1/my/invitations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvitationResponse> sendInvitation(
            @AuthenticationPrincipal OrgPrincipal principal,
            @RequestBody SendInvitationRequest request) {

        var invitation = invitationService.sendInvitation(
                principal.orgId(), request.customerEmail());

        // Map entity to response DTO (status is always PENDING at creation)
        var response = new InvitationResponse(
                invitation.getId(),
                invitation.getCustomerEmail(),
                invitation.getStatus(),
                invitation.getCreatedAt(),
                invitation.getExpiresAt(),
                invitation.getCompletedAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "List all invitations for the authenticated organisation")
    @SecurityRequirement(name = "ApiKeyAuth")
    @GetMapping(value = "/api/v1/my/invitations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<InvitationResponse>> listInvitations(
            @AuthenticationPrincipal OrgPrincipal principal) {

        List<InvitationResponse> invitations =
                invitationService.listInvitations(principal.orgId());
        return ResponseEntity.ok(invitations);
    }

    @Operation(summary = "Cancel a PENDING invitation")
    @SecurityRequirement(name = "ApiKeyAuth")
    @DeleteMapping("/api/v1/my/invitations/{id}")
    public ResponseEntity<Void> cancelInvitation(
            @AuthenticationPrincipal OrgPrincipal principal,
            @PathVariable UUID id) {

        invitationService.cancelInvitation(principal.orgId(), id);
        return ResponseEntity.noContent().build();
    }

    // ── Public endpoints ─────────────────────────────────────────────────────

    @Operation(summary = "Validate an invitation token (public)")
    @GetMapping(value = "/api/v1/invitations/{token}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenValidationResponse> validateToken(
            @PathVariable String token) {

        TokenValidationResponse response = invitationService.validateToken(token);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Complete PEPPOL self-registration (public)")
    @PostMapping(value = "/api/v1/invitations/{token}/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CompleteRegistrationResponse> completeRegistration(
            @PathVariable String token,
            @RequestBody CompleteRegistrationRequest request) {

        CompleteRegistrationResponse response =
                invitationService.completeRegistration(token, request);
        return ResponseEntity.ok(response);
    }
}

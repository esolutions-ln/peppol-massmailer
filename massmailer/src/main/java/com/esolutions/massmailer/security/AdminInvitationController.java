package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.AdminDtos.*;
import com.esolutions.massmailer.security.service.AdminInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for platform-admin email invitations.
 *
 * <p>Authenticated endpoints (ROLE_ADMIN, under {@code /api/v1/admin/**}):
 * <ul>
 *   <li>POST   /api/v1/admin/invitations        — send invitation</li>
 *   <li>GET    /api/v1/admin/invitations        — list invitations</li>
 *   <li>DELETE /api/v1/admin/invitations/{id}   — cancel invitation</li>
 * </ul>
 *
 * <p>Public endpoints (no auth required — the invitee is not an admin yet, so these
 * deliberately live outside {@code /api/v1/admin/**} and are permitAll'd explicitly
 * in SecurityConfig, mirroring how {@code /api/v1/invitations/**} works for PEPPOL):
 * <ul>
 *   <li>GET  /api/v1/admin-invitations/{token}           — validate token</li>
 *   <li>POST /api/v1/admin-invitations/{token}/complete  — complete registration</li>
 * </ul>
 */
@RestController
@Tag(name = "Admin Users")
public class AdminInvitationController {

    private final AdminInvitationService invitationService;

    public AdminInvitationController(AdminInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    // ── Authenticated (platform admin) endpoints ─────────────────────────────

    @Operation(summary = "Invite a new platform admin by email")
    @SecurityRequirement(name = "ApiKeyAuth")
    @PostMapping(value = "/api/v1/admin/invitations",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminInvitationDto> sendInvitation(
            Authentication authentication,
            @RequestBody SendAdminInvitationRequest request) {

        AdminInvitationDto invitation = invitationService.sendInvitation(
                request.email(), request.displayName(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(invitation);
    }

    @Operation(summary = "List all platform-admin invitations")
    @SecurityRequirement(name = "ApiKeyAuth")
    @GetMapping(value = "/api/v1/admin/invitations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdminInvitationDto>> listInvitations() {
        return ResponseEntity.ok(invitationService.listInvitations());
    }

    @Operation(summary = "Cancel a PENDING admin invitation")
    @SecurityRequirement(name = "ApiKeyAuth")
    @DeleteMapping("/api/v1/admin/invitations/{id}")
    public ResponseEntity<Void> cancelInvitation(@PathVariable UUID id) {
        invitationService.cancelInvitation(id);
        return ResponseEntity.noContent().build();
    }

    // ── Public endpoints ─────────────────────────────────────────────────────

    @Operation(summary = "Validate an admin invitation token (public)")
    @GetMapping(value = "/api/v1/admin-invitations/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminInvitationTokenValidationResponse> validateToken(@PathVariable String token) {
        return ResponseEntity.ok(invitationService.validateToken(token));
    }

    @Operation(summary = "Complete platform-admin self-registration (public)")
    @PostMapping(value = "/api/v1/admin-invitations/{token}/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CompleteAdminInvitationResponse> completeRegistration(
            @PathVariable String token,
            @RequestBody CompleteAdminInvitationRequest request) {

        var created = invitationService.completeRegistration(token, request.username(), request.password());
        return ResponseEntity.ok(new CompleteAdminInvitationResponse(created.username()));
    }
}

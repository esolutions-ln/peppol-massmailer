package com.esolutions.massmailer.organization.controller;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.model.OrgMemberRole;
import com.esolutions.massmailer.organization.service.OrgMemberService;
import com.esolutions.massmailer.security.OrgPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/my/members")
@Tag(name = "Organisation Members",
        description = "Manage platform users for the authenticated organisation (ORG_ADMIN only).")
public class OrgMemberController {

    private final OrgMemberService service;

    public OrgMemberController(OrgMemberService service) {
        this.service = service;
    }

    public record CreateMemberRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            String displayName,
            OrgMemberRole role
    ) {}

    public record UpdateRoleRequest(@NotBlank String role) {}

    public record SetActiveRequest(boolean active) {}

    public record ResetPasswordRequest(@NotBlank String password) {}

    public record MemberResponse(
            UUID id,
            UUID organizationId,
            String email,
            String displayName,
            String role,
            boolean active,
            Instant createdAt,
            Instant lastLoginAt
    ) {
        static MemberResponse of(OrgMember m) {
            return new MemberResponse(m.getId(), m.getOrganizationId(), m.getEmail(),
                    m.getDisplayName(), m.getRole().name(), m.isActive(),
                    m.getCreatedAt(), m.getLastLoginAt());
        }
    }

    @Operation(summary = "List members of the authenticated organisation")
    @GetMapping
    public ResponseEntity<List<MemberResponse>> list(@AuthenticationPrincipal OrgPrincipal principal) {
        requireOrg(principal);
        return ResponseEntity.ok(service.list(principal.orgId()).stream()
                .map(MemberResponse::of).toList());
    }

    @Operation(summary = "Create a new member with email + temporary password")
    @PostMapping
    public ResponseEntity<MemberResponse> create(@AuthenticationPrincipal OrgPrincipal principal,
                                                 @RequestBody @Valid CreateMemberRequest req) {
        requireOrg(principal);
        var m = service.create(principal.orgId(), req.email(), req.password(),
                req.displayName(), req.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.of(m));
    }

    @Operation(summary = "Update a member's role")
    @PutMapping("/{memberId}/role")
    public ResponseEntity<MemberResponse> updateRole(@AuthenticationPrincipal OrgPrincipal principal,
                                                     @PathVariable UUID memberId,
                                                     @RequestBody @Valid UpdateRoleRequest req) {
        requireOrg(principal);
        OrgMemberRole role;
        try {
            role = OrgMemberRole.valueOf(req.role());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role must be ORG_ADMIN or ORG_VIEWER");
        }
        return ResponseEntity.ok(MemberResponse.of(
                service.updateRole(principal.orgId(), memberId, role)));
    }

    @Operation(summary = "Activate or deactivate a member")
    @PutMapping("/{memberId}/active")
    public ResponseEntity<MemberResponse> setActive(@AuthenticationPrincipal OrgPrincipal principal,
                                                    @PathVariable UUID memberId,
                                                    @RequestBody SetActiveRequest req) {
        requireOrg(principal);
        return ResponseEntity.ok(MemberResponse.of(
                service.setActive(principal.orgId(), memberId, req.active())));
    }

    @Operation(summary = "Reset a member's password (admin override)")
    @PutMapping("/{memberId}/password")
    public ResponseEntity<Void> resetPassword(@AuthenticationPrincipal OrgPrincipal principal,
                                              @PathVariable UUID memberId,
                                              @RequestBody @Valid ResetPasswordRequest req) {
        requireOrg(principal);
        service.resetPassword(principal.orgId(), memberId, req.password());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete a member and revoke all their sessions")
    @DeleteMapping("/{memberId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal OrgPrincipal principal,
                                       @PathVariable UUID memberId) {
        requireOrg(principal);
        if (principal.member() != null && principal.member().getId().equals(memberId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You cannot delete your own account");
        }
        service.delete(principal.orgId(), memberId);
        return ResponseEntity.noContent().build();
    }

    private static void requireOrg(OrgPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
    }
}

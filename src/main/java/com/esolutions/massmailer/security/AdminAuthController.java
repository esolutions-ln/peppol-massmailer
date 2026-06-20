package com.esolutions.massmailer.security;

import com.esolutions.massmailer.organization.repository.OrgMemberRepository;
import com.esolutions.massmailer.organization.service.OrgAuthService;
import com.esolutions.massmailer.security.AdminDtos.AdminLoginRequest;
import com.esolutions.massmailer.security.AdminDtos.AdminLoginResponse;
import com.esolutions.massmailer.security.service.AdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Admin authentication endpoints.
 * Delegates credential verification and token management to {@link AdminAuthService}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final OrgAuthService orgAuthService;
    private final OrgMemberRepository memberRepo;
    private final DefaultOrgAdminProperties orgAdminProps;

    public AdminAuthController(AdminAuthService adminAuthService,
                               OrgAuthService orgAuthService,
                               OrgMemberRepository memberRepo,
                               DefaultOrgAdminProperties orgAdminProps) {
        this.adminAuthService = adminAuthService;
        this.orgAuthService = orgAuthService;
        this.memberRepo = memberRepo;
        this.orgAdminProps = orgAdminProps;
    }

    @Operation(summary = "Admin login — returns session token and display name on success")
    @PostMapping("/login")
    public ResponseEntity<AdminLoginResponse> login(@RequestBody AdminLoginRequest req) {
        AdminLoginResponse response = adminAuthService.login(req.username(), req.password());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Admin logout — invalidates the current session token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("X-API-Key") String token) {
        adminAuthService.logout(token);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get an org-level session token for a given organisation (admin)",
            description = "Allows a global admin to access a specific organisation without needing the org slug.")
    @PostMapping("/orgs/{orgId}/login")
    public ResponseEntity<OrgAuthService.LoginResponse> orgLogin(@PathVariable UUID orgId) {
        String email = orgAdminProps.getEmail();
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No default org admin configured");
        }
        var member = memberRepo.findByOrganizationIdAndEmail(orgId, email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No default admin member found for this organisation. " +
                        "The DefaultOrgAdminSeeder may not have run for this org yet."));
        return ResponseEntity.ok(orgAuthService.createTokenForMember(member));
    }
}

package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.AdminDtos.*;
import com.esolutions.massmailer.security.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Self-service profile and password management for the currently authenticated
 * platform admin. All routes fall under {@code /api/v1/admin/**}, so they inherit
 * {@code hasRole("ADMIN")} from {@code SecurityConfig} — no separate rule needed.
 */
@RestController
@RequestMapping("/api/v1/admin/me")
@Tag(name = "Admin Users")
public class AdminProfileController {

    private final AdminUserService adminUserService;

    public AdminProfileController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Operation(summary = "Get the authenticated admin's own profile")
    @SecurityRequirement(name = "ApiKeyAuth")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminUserDto> getProfile(Authentication authentication) {
        return ResponseEntity.ok(adminUserService.getByUsername(authentication.getName()));
    }

    @Operation(summary = "Update the authenticated admin's own display name and/or email")
    @SecurityRequirement(name = "ApiKeyAuth")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminUserDto> updateProfile(
            Authentication authentication,
            @RequestBody UpdateAdminProfileRequest request) {
        var updated = adminUserService.updateProfile(
                authentication.getName(), request.displayName(), request.email());
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Change the authenticated admin's own password",
            description = "Requires the current password. Invalidates every active session " +
                    "for this account, including the one making this request.")
    @SecurityRequirement(name = "ApiKeyAuth")
    @PutMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @RequestBody ChangeAdminPasswordRequest request) {
        adminUserService.changePassword(
                authentication.getName(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}

package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.AdminDtos.*;
import com.esolutions.massmailer.security.service.AdminPasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * "Forgot password" self-service reset for platform admins. All routes here are public
 * (a locked-out admin is, by definition, not authenticated) — deliberately kept on a
 * separate path from {@code /api/v1/admin/**} so they aren't swept into that prefix's
 * {@code hasRole("ADMIN")} rule, mirroring how {@code /api/v1/admin-invitations/**} works.
 */
@RestController
@RequestMapping("/api/v1/admin-password-reset")
@Tag(name = "Admin Users")
public class AdminPasswordResetController {

    private final AdminPasswordResetService resetService;

    public AdminPasswordResetController(AdminPasswordResetService resetService) {
        this.resetService = resetService;
    }

    @Operation(summary = "Request a password reset link (public)",
            description = "Always returns 202 regardless of whether the username exists, is " +
                    "active, or has an email on file — response does not reveal account existence.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> requestReset(@RequestBody RequestAdminPasswordResetRequest request) {
        resetService.requestReset(request.username());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Validate a password reset token (public)")
    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminPasswordResetTokenValidationResponse> validateToken(@PathVariable String token) {
        return ResponseEntity.ok(resetService.validateToken(token));
    }

    @Operation(summary = "Complete a password reset with a new password (public)")
    @PostMapping(value = "/{token}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> completeReset(
            @PathVariable String token,
            @RequestBody CompleteAdminPasswordResetRequest request) {
        resetService.completeReset(token, request.newPassword());
        return ResponseEntity.noContent().build();
    }
}

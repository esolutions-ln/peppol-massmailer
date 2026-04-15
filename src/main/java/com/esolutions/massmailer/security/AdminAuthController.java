package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.AdminDtos.AdminLoginRequest;
import com.esolutions.massmailer.security.AdminDtos.AdminLoginResponse;
import com.esolutions.massmailer.security.service.AdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin authentication endpoints.
 * Delegates credential verification and token management to {@link AdminAuthService}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
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
}

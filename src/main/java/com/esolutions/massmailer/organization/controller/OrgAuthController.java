package com.esolutions.massmailer.organization.controller;

import com.esolutions.massmailer.organization.service.OrgAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
@Tag(name = "Organisation Auth",
        description = "Email + password login for organisation members (employees of an org).")
public class OrgAuthController {

    private final OrgAuthService orgAuthService;

    public OrgAuthController(OrgAuthService orgAuthService) {
        this.orgAuthService = orgAuthService;
    }

    public record OrgLoginRequest(
            @NotBlank String slug,
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    @Operation(summary = "Org member login — exchanges slug+email+password for a session token")
    @PostMapping("/login")
    public ResponseEntity<OrgAuthService.LoginResponse> login(@RequestBody @Valid OrgLoginRequest req) {
        return ResponseEntity.ok(orgAuthService.login(req.slug(), req.email(), req.password()));
    }

    @Operation(summary = "Org member logout — invalidates the current session token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("X-API-Key") String token) {
        orgAuthService.logout(token);
        return ResponseEntity.noContent().build();
    }
}

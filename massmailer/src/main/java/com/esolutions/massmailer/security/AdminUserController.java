package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.AdminDtos.AdminUserDto;
import com.esolutions.massmailer.security.AdminDtos.CreateAdminUserRequest;
import com.esolutions.massmailer.security.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin user management endpoints.
 * All routes require ROLE_ADMIN (enforced by SecurityConfig).
 *
 * <p>Implements Requirements 4.1, 4.4, 5.1, 5.3, 6.1, 6.4, 7.1, 7.3
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "Admin Users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Operation(summary = "List all admin users — never includes password hashes or session tokens")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdminUserDto>> listUsers() {
        return ResponseEntity.ok(adminUserService.listUsers());
    }

    @Operation(summary = "Create a new admin user — password must be at least 8 characters")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminUserDto> createUser(@RequestBody CreateAdminUserRequest req) {
        AdminUserDto created = adminUserService.createUser(req.username(), req.password(), req.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Deactivate an admin user and invalidate all their session tokens")
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateUser(@PathVariable UUID id) {
        adminUserService.deactivateUser(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivate a previously deactivated admin user")
    @PatchMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivateUser(@PathVariable UUID id) {
        adminUserService.reactivateUser(id);
        return ResponseEntity.noContent().build();
    }
}

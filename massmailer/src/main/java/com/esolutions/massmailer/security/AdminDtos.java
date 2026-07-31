package com.esolutions.massmailer.security;

import java.time.Instant;
import java.util.UUID;

public class AdminDtos {

    public record AdminLoginRequest(String username, String password) {}

    public record AdminLoginResponse(String token, String name) {}

    public record CreateAdminUserRequest(String username, String password, String displayName) {}

    public record AdminUserDto(UUID id, String username, String displayName, String role,
                               boolean active, Instant createdAt) {}
}

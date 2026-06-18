package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.security.AdminDtos.AdminLoginResponse;
import com.esolutions.massmailer.security.AdminProperties;
import com.esolutions.massmailer.security.AdminTokens;
import com.esolutions.massmailer.security.model.AdminSessionToken;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;
    private final AdminSessionTokenRepository adminSessionTokenRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;

    public AdminAuthService(AdminUserRepository adminUserRepository,
                            AdminSessionTokenRepository adminSessionTokenRepository,
                            BCryptPasswordEncoder passwordEncoder,
                            AdminProperties adminProperties) {
        this.adminUserRepository = adminUserRepository;
        this.adminSessionTokenRepository = adminSessionTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminProperties = adminProperties;
    }

    @Transactional
    public AdminLoginResponse login(String username, String password) {
        AdminUser user = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid username or password"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid username or password");
        }

        String rawToken = AdminTokens.generateRawToken();
        Instant expiresAt = Instant.now().plus(adminProperties.getTokenExpiryHours(), ChronoUnit.HOURS);

        AdminSessionToken sessionToken = AdminSessionToken.builder()
                .token(AdminTokens.hashToken(rawToken)) // only the hash is persisted
                .adminUser(user)
                .expiresAt(expiresAt)
                .build();

        adminSessionTokenRepository.save(sessionToken);

        // Return the raw token to the client — it is never stored server-side.
        return new AdminLoginResponse(rawToken, user.getDisplayName());
    }

    @Transactional
    public void logout(String rawToken) {
        adminSessionTokenRepository.deleteByToken(AdminTokens.hashToken(rawToken));
    }
}

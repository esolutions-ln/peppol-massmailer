package com.esolutions.massmailer.security.service;

import com.esolutions.massmailer.security.AdminProperties;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the first admin user from environment variables when the admin_users table is empty.
 * If ADMIN_USERNAME or ADMIN_PASSWORD is missing, the application refuses to start.
 *
 * Requirements: 1.4, 1.5
 */
@Component
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final AdminUserRepository adminUserRepository;
    private final AdminProperties adminProperties;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminBootstrapService(AdminUserRepository adminUserRepository,
                                 AdminProperties adminProperties,
                                 BCryptPasswordEncoder passwordEncoder) {
        this.adminUserRepository = adminUserRepository;
        this.adminProperties = adminProperties;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedBootstrapAdmin() {
        if (adminUserRepository.count() != 0) {
            return;
        }

        String username = adminProperties.getUsername();
        String password = adminProperties.getPassword();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.error("Bootstrap admin credentials are missing. " +
                      "Set ADMIN_USERNAME and ADMIN_PASSWORD environment variables.");
            throw new IllegalStateException(
                "Cannot start: ADMIN_USERNAME and ADMIN_PASSWORD must be set when no admin users exist.");
        }

        String passwordHash = passwordEncoder.encode(password);

        AdminUser bootstrapAdmin = AdminUser.builder()
                .username(username)
                .passwordHash(passwordHash)
                .displayName(username)
                .build();

        adminUserRepository.save(bootstrapAdmin);
        log.info("Bootstrap admin user '{}' created successfully.", username);
    }
}

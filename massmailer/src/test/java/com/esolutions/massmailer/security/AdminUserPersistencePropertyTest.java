package com.esolutions.massmailer.security;

import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Feature: admin-user-management, Property 1: Admin user persistence round-trip

/**
 * Property-based tests for {@link AdminUser} persistence.
 *
 * <p><b>Property 1: Admin user persistence round-trip</b>
 *
 * <p>For any valid {@link AdminUser} saved to the database, reloading it by ID should
 * return an entity with identical username, display name, role, active status, and a
 * non-null created-at timestamp.
 *
 * <p><b>Validates: Requirements 1.1</b>
 */
@JqwikSpringSupport
@SpringBootTest
@Transactional
class AdminUserPersistencePropertyTest {

    @Autowired
    private AdminUserRepository adminUserRepository;

    /**
     * <b>Property 1: Admin user persistence round-trip</b>
     *
     * <p>For any valid {@link AdminUser} saved to the database, reloading it by ID must
     * return an entity with identical username, display name, role, active status, and a
     * non-null created-at timestamp.
     *
     * <p><b>Validates: Requirements 1.1</b>
     */
    @Property(tries = 20)
    void adminUserPersistenceRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String displayName) {

        // Append a UUID suffix to guarantee uniqueness across tries
        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash("$2a$12$hashedpassword")
                .displayName(displayName)
                .build();

        AdminUser saved = adminUserRepository.save(user);
        adminUserRepository.flush();

        AdminUser reloaded = adminUserRepository.findById(saved.getId())
                .orElseThrow(() -> new AssertionError("AdminUser not found after save: " + saved.getId()));

        assertThat(reloaded.getUsername())
                .as("Reloaded username must match saved username")
                .isEqualTo(uniqueUsername);

        assertThat(reloaded.getDisplayName())
                .as("Reloaded displayName must match saved displayName")
                .isEqualTo(displayName);

        assertThat(reloaded.getRole())
                .as("Reloaded role must be ADMIN (default)")
                .isEqualTo("ADMIN");

        assertThat(reloaded.isActive())
                .as("Reloaded active status must be true (default)")
                .isTrue();

        assertThat(reloaded.getCreatedAt())
                .as("Reloaded createdAt must be non-null")
                .isNotNull();
    }

    // Feature: admin-user-management, Property 2: Passwords are stored as bcrypt hashes

    /**
     * <b>Property 2: Passwords are stored as bcrypt hashes</b>
     *
     * <p>For any admin user created with a plaintext password (≥ 8 chars), the stored
     * {@code passwordHash} field must not equal the plaintext password, and
     * {@code BCryptPasswordEncoder.matches(plaintext, hash)} must return {@code true}.
     *
     * <p><b>Validates: Requirements 1.2</b>
     */
    @Property(tries = 20)
    void passwordsAreStoredAsBcryptHashes(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String plainPassword) {

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
        String hash = encoder.encode(plainPassword);

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName("Test User")
                .build();

        AdminUser saved = adminUserRepository.save(user);
        adminUserRepository.flush();

        AdminUser reloaded = adminUserRepository.findById(saved.getId())
                .orElseThrow(() -> new AssertionError("AdminUser not found after save: " + saved.getId()));

        String storedHash = reloaded.getPasswordHash();

        assertThat(storedHash)
                .as("Stored hash must not equal the plaintext password")
                .isNotEqualTo(plainPassword);

        assertThat(encoder.matches(plainPassword, storedHash))
                .as("BCryptPasswordEncoder.matches() must return true for the original plaintext against the stored hash")
                .isTrue();
    }
}

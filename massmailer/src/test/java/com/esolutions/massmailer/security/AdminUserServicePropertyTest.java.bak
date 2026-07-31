package com.esolutions.massmailer.security;

// Feature: admin-user-management, Property 9: Deactivation invalidates all tokens
// Feature: admin-user-management, Property 10: Created admin user is active and persisted
// Feature: admin-user-management, Property 11: Short passwords are rejected
// Feature: admin-user-management, Property 14: Deactivate/reactivate round-trip
// Feature: admin-user-management, Property 15: Minimum active administrator invariant

import com.esolutions.massmailer.security.AdminDtos.AdminUserDto;
import com.esolutions.massmailer.security.model.AdminSessionToken;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import com.esolutions.massmailer.security.service.AdminUserService;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link AdminUserService}.
 *
 * <p><b>Property 9: Deactivation invalidates all tokens</b> — Validates: Requirements 3.3, 3.4, 6.1
 * <p><b>Property 10: Created admin user is active and persisted</b> — Validates: Requirements 4.1
 * <p><b>Property 11: Short passwords are rejected</b> — Validates: Requirements 4.3
 * <p><b>Property 14: Deactivate/reactivate round-trip</b> — Validates: Requirements 6.1, 7.1
 * <p><b>Property 15: Minimum active administrator invariant</b> — Validates: Requirements 8.1, 8.2, 8.3
 */
@JqwikSpringSupport
@SpringBootTest
@Transactional
class AdminUserServicePropertyTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminSessionTokenRepository adminSessionTokenRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    // -------------------------------------------------------------------------
    // Property 9: Deactivation invalidates all tokens
    // -------------------------------------------------------------------------

    /**
     * <b>Property 9: Deactivation invalidates all tokens</b>
     *
     * <p>For any admin user with one or more active session tokens, deactivating that user
     * must result in all of their tokens being deleted from the database.
     *
     * <p><b>Validates: Requirements 3.3, 3.4, 6.1</b>
     */
    @Property(tries = 10)
    void deactivationInvalidatesAllTokens(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName) {

        // Create a second admin so deactivation is allowed (min-active-admin guard)
        String guardUsername = "guard" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AdminUser guardUser = adminUserRepository.save(AdminUser.builder()
                .username(guardUsername)
                .passwordHash(passwordEncoder.encode("guardpass1"))
                .displayName("Guard Admin")
                .build());

        // Create the user under test
        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AdminUser user = adminUserRepository.save(AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode("password1"))
                .displayName("Token Test User")
                .build());
        adminUserRepository.flush();

        // Persist a session token for the user
        AdminSessionToken token = adminSessionTokenRepository.save(AdminSessionToken.builder()
                .token(UUID.randomUUID().toString())
                .adminUser(user)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
        adminSessionTokenRepository.flush();

        // Verify token exists before deactivation
        assertThat(adminSessionTokenRepository.findById(token.getId())).isPresent();

        // Deactivate the user
        adminUserService.deactivateUser(user.getId());

        // All tokens for this user must be deleted
        assertThat(adminSessionTokenRepository.findById(token.getId()))
                .as("Session token must be deleted after user deactivation")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Property 10: Created admin user is active and persisted
    // -------------------------------------------------------------------------

    /**
     * <b>Property 10: Created admin user is active and persisted</b>
     *
     * <p>For any valid create-user request (unique username, password ≥ 8 chars, non-blank
     * display name), the resulting {@link AdminUserDto} must have {@code active = true}
     * and all supplied fields stored correctly.
     *
     * <p><b>Validates: Requirements 4.1</b>
     */
    @Property(tries = 10)
    void createdAdminUserIsActiveAndPersisted(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String password,
            @ForAll @AlphaChars @StringLength(min = 3, max = 30) String displayName) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        AdminUserDto dto = adminUserService.createUser(uniqueUsername, password, displayName);

        assertThat(dto.active())
                .as("Newly created admin user must be active")
                .isTrue();

        assertThat(dto.username())
                .as("Returned DTO username must match the requested username")
                .isEqualTo(uniqueUsername);

        assertThat(dto.displayName())
                .as("Returned DTO displayName must match the requested displayName")
                .isEqualTo(displayName);

        assertThat(dto.id())
                .as("Returned DTO must have a non-null id")
                .isNotNull();

        assertThat(dto.createdAt())
                .as("Returned DTO must have a non-null createdAt")
                .isNotNull();

        // Verify the user is actually persisted in the DB
        assertThat(adminUserRepository.findById(dto.id()))
                .as("Created user must be findable in the repository by id")
                .isPresent()
                .get()
                .satisfies(u -> {
                    assertThat(u.isActive()).isTrue();
                    assertThat(u.getUsername()).isEqualTo(uniqueUsername);
                    assertThat(u.getDisplayName()).isEqualTo(displayName);
                });
    }

    // -------------------------------------------------------------------------
    // Property 11: Short passwords are rejected
    // -------------------------------------------------------------------------

    /**
     * <b>Property 11: Short passwords are rejected</b>
     *
     * <p>For any password string with length &lt; 8, a create-user request must throw a
     * {@link ResponseStatusException} with HTTP 400 and no user must be created.
     *
     * <p><b>Validates: Requirements 4.3</b>
     */
    @Property(tries = 10)
    void shortPasswordsAreRejected(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 1, max = 7) String shortPassword) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long countBefore = adminUserRepository.count();

        assertThatThrownBy(() -> adminUserService.createUser(uniqueUsername, shortPassword, "Display Name"))
                .as("Password shorter than 8 chars must throw ResponseStatusException with 400")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value())
                            .as("Status code must be 400 BAD_REQUEST")
                            .isEqualTo(400);
                });

        assertThat(adminUserRepository.count())
                .as("No new user must be persisted when password is too short")
                .isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // Property 14: Deactivate/reactivate round-trip
    // -------------------------------------------------------------------------

    /**
     * <b>Property 14: Deactivate/reactivate round-trip</b>
     *
     * <p>For any admin user (when at least one other active admin exists), deactivating
     * then reactivating that user must result in {@code active = true} with all other
     * fields unchanged.
     *
     * <p><b>Validates: Requirements 6.1, 7.1</b>
     */
    @Property(tries = 10)
    void deactivateReactivateRoundTrip(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName) {

        // Create a second admin so deactivation is allowed
        String guardUsername = "guard" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        adminUserRepository.save(AdminUser.builder()
                .username(guardUsername)
                .passwordHash(passwordEncoder.encode("guardpass1"))
                .displayName("Guard Admin")
                .build());

        // Create the user under test
        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AdminUser user = adminUserRepository.saveAndFlush(AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode("password1"))
                .displayName("Round Trip User")
                .build());

        String originalUsername = user.getUsername();
        String originalDisplayName = user.getDisplayName();
        String originalRole = user.getRole();

        // Deactivate
        adminUserService.deactivateUser(user.getId());

        AdminUser afterDeactivate = adminUserRepository.findById(user.getId())
                .orElseThrow(() -> new AssertionError("User not found after deactivation"));
        assertThat(afterDeactivate.isActive())
                .as("User must be inactive after deactivation")
                .isFalse();

        // Reactivate
        adminUserService.reactivateUser(user.getId());

        AdminUser afterReactivate = adminUserRepository.findById(user.getId())
                .orElseThrow(() -> new AssertionError("User not found after reactivation"));

        assertThat(afterReactivate.isActive())
                .as("User must be active again after reactivation")
                .isTrue();

        assertThat(afterReactivate.getUsername())
                .as("Username must be unchanged after round-trip")
                .isEqualTo(originalUsername);

        assertThat(afterReactivate.getDisplayName())
                .as("DisplayName must be unchanged after round-trip")
                .isEqualTo(originalDisplayName);

        assertThat(afterReactivate.getRole())
                .as("Role must be unchanged after round-trip")
                .isEqualTo(originalRole);
    }

    // -------------------------------------------------------------------------
    // Property 15: Minimum active administrator invariant
    // -------------------------------------------------------------------------

    /**
     * <b>Property 15: Minimum active administrator invariant</b>
     *
     * <p>When only one active admin exists, attempting to deactivate that admin must
     * throw a {@link ResponseStatusException} with HTTP 409, leaving the user still active.
     *
     * <p><b>Validates: Requirements 8.1, 8.2, 8.3</b>
     */
    @Property(tries = 10)
    void minimumActiveAdminInvariant(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName) {

        // Create a single admin user (the only active admin in this transaction)
        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        AdminUser onlyAdmin = adminUserRepository.saveAndFlush(AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode("password1"))
                .displayName("Only Admin")
                .build());

        // Deactivate all other pre-existing active admins so this is truly the last one
        List<AdminUser> others = adminUserRepository.findAll().stream()
                .filter(u -> !u.getId().equals(onlyAdmin.getId()) && u.isActive())
                .toList();
        others.forEach(u -> {
            u.setActive(false);
            adminUserRepository.save(u);
        });
        adminUserRepository.flush();

        assertThat(adminUserRepository.countByActiveTrue())
                .as("There must be exactly 1 active admin before the test")
                .isEqualTo(1L);

        // Attempt to deactivate the only active admin — must be rejected with 409
        assertThatThrownBy(() -> adminUserService.deactivateUser(onlyAdmin.getId()))
                .as("Deactivating the last active admin must throw ResponseStatusException with 409")
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode().value())
                            .as("Status code must be 409 CONFLICT")
                            .isEqualTo(409);
                });

        // The user must still be active
        AdminUser stillActive = adminUserRepository.findById(onlyAdmin.getId())
                .orElseThrow(() -> new AssertionError("User not found"));
        assertThat(stillActive.isActive())
                .as("The only admin must remain active after a rejected deactivation")
                .isTrue();
    }
}

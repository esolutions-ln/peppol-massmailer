package com.esolutions.massmailer.security;

// Feature: admin-user-management, Property 6: Valid session token grants ROLE_ADMIN
// Feature: admin-user-management, Property 7: Expired or invalid token is rejected
// Feature: admin-user-management, Property 8: Issued tokens have correct expiry

import com.esolutions.massmailer.security.model.AdminSessionToken;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import com.esolutions.massmailer.security.service.AdminAuthService;
import com.esolutions.massmailer.security.AdminDtos.AdminLoginResponse;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for session token authentication via {@code ApiKeyAuthFilter}.
 *
 * <p><b>Property 6: Valid session token grants ROLE_ADMIN</b> — Validates: Requirements 2.5
 * <p><b>Property 7: Expired or invalid token is rejected</b> — Validates: Requirements 2.6, 3.2
 * <p><b>Property 8: Issued tokens have correct expiry</b> — Validates: Requirements 3.1
 *
 * <p>Note: {@code @Transactional} is intentionally omitted — with {@code RANDOM_PORT} the HTTP
 * request runs in a separate thread/transaction, so test data must be committed before the
 * request is made. Cleanup is performed manually after each property try.
 */
@JqwikSpringSupport
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSessionTokenPropertyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminSessionTokenRepository adminSessionTokenRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private AdminAuthService adminAuthService;

    @Autowired
    private AdminProperties adminProperties;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Saves a user, runs the action, then cleans up tokens and the user. */
    private void withUser(AdminUser user, Runnable action) {
        AdminUser saved = adminUserRepository.saveAndFlush(user);
        try {
            action.run();
        } finally {
            adminSessionTokenRepository.deleteByAdminUser(saved);
            adminUserRepository.delete(saved);
            adminUserRepository.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Property 6: Valid session token grants ROLE_ADMIN
    // -------------------------------------------------------------------------

    /**
     * <b>Property 6: Valid session token grants ROLE_ADMIN</b>
     *
     * <p>For any non-expired {@link AdminSessionToken} in the database, presenting it as
     * {@code X-API-Key} on a request to an admin-protected endpoint should result in HTTP 200,
     * meaning the request was authenticated with {@code ROLE_ADMIN}.
     *
     * <p><b>Validates: Requirements 2.5</b>
     */
    @Property(tries = 10)
    void validSessionTokenGrantsRoleAdmin(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 3, max = 30) String displayName) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = passwordEncoder.encode("password123");

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName(displayName)
                .active(true)
                .build();

        withUser(user, () -> {
            // Create a valid (non-expired) session token directly in the DB
            String tokenValue = UUID.randomUUID().toString();
            AdminSessionToken sessionToken = AdminSessionToken.builder()
                    .token(tokenValue)
                    .adminUser(adminUserRepository.findByUsername(uniqueUsername).orElseThrow())
                    .expiresAt(Instant.now().plus(8, ChronoUnit.HOURS))
                    .build();
            adminSessionTokenRepository.saveAndFlush(sessionToken);

            // Make a GET request to an admin-protected endpoint with the token
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", tokenValue);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl() + "/api/v1/admin/users",
                    HttpMethod.GET,
                    request,
                    String.class);

            assertThat(response.getStatusCode())
                    .as("Valid session token must grant ROLE_ADMIN and return HTTP 200")
                    .isEqualTo(HttpStatus.OK);
        });
    }

    // -------------------------------------------------------------------------
    // Property 7: Expired or invalid token is rejected
    // -------------------------------------------------------------------------

    /**
     * <b>Property 7: Expired or invalid token is rejected</b>
     *
     * <p>Tests two cases:
     * <ul>
     *   <li>(a) Expired token: {@code expiresAt} is in the past → expect 401 or 403</li>
     *   <li>(b) Random/unknown token: a UUID not present in the DB → expect 401 or 403</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 2.6, 3.2</b>
     */
    @Property(tries = 10)
    void expiredOrInvalidTokenIsRejected(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 3, max = 30) String displayName) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = passwordEncoder.encode("password123");

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName(displayName)
                .active(true)
                .build();

        withUser(user, () -> {
            RestTemplate restTemplate = new RestTemplate();

            // Case (a): Expired token — expiresAt is 1 second in the past
            String expiredTokenValue = UUID.randomUUID().toString();
            AdminSessionToken expiredToken = AdminSessionToken.builder()
                    .token(expiredTokenValue)
                    .adminUser(adminUserRepository.findByUsername(uniqueUsername).orElseThrow())
                    .expiresAt(Instant.now().minusSeconds(1))
                    .build();
            adminSessionTokenRepository.saveAndFlush(expiredToken);

            HttpHeaders expiredHeaders = new HttpHeaders();
            expiredHeaders.set("X-API-Key", expiredTokenValue);
            HttpEntity<Void> expiredRequest = new HttpEntity<>(expiredHeaders);

            assertThatThrownBy(() ->
                    restTemplate.exchange(
                            baseUrl() + "/api/v1/admin/users",
                            HttpMethod.GET,
                            expiredRequest,
                            String.class))
                    .isInstanceOf(HttpClientErrorException.class)
                    .satisfies(ex -> {
                        HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                        assertThat(httpEx.getStatusCode().value())
                                .as("Expired token must be rejected with 401 or 403")
                                .isIn(401, 403);
                    });

            // Case (b): Random/unknown token — not present in the DB at all
            String randomToken = UUID.randomUUID().toString();
            HttpHeaders randomHeaders = new HttpHeaders();
            randomHeaders.set("X-API-Key", randomToken);
            HttpEntity<Void> randomRequest = new HttpEntity<>(randomHeaders);

            assertThatThrownBy(() ->
                    restTemplate.exchange(
                            baseUrl() + "/api/v1/admin/users",
                            HttpMethod.GET,
                            randomRequest,
                            String.class))
                    .isInstanceOf(HttpClientErrorException.class)
                    .satisfies(ex -> {
                        HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                        assertThat(httpEx.getStatusCode().value())
                                .as("Unknown/random token must be rejected with 401 or 403")
                                .isIn(401, 403);
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Property 8: Issued tokens have correct expiry
    // -------------------------------------------------------------------------

    /**
     * <b>Property 8: Issued tokens have correct expiry</b>
     *
     * <p>For any newly issued {@link AdminSessionToken} via {@code adminAuthService.login()},
     * its {@code expiresAt} should be approximately {@code now + configuredExpiryHours}
     * (within a 5-second tolerance).
     *
     * <p><b>Validates: Requirements 3.1</b>
     */
    @Property(tries = 10)
    void issuedTokensHaveCorrectExpiry(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String plainPassword,
            @ForAll @AlphaChars @StringLength(min = 3, max = 30) String displayName) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = passwordEncoder.encode(plainPassword);

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName(displayName)
                .active(true)
                .build();

        withUser(user, () -> {
            Instant before = Instant.now();

            // Call login to issue a token
            AdminLoginResponse loginResponse = adminAuthService.login(uniqueUsername, plainPassword);

            Instant after = Instant.now();

            assertThat(loginResponse.token())
                    .as("Login must return a non-blank token")
                    .isNotBlank();

            // Look up the persisted token to check its expiresAt
            AdminSessionToken persistedToken = adminSessionTokenRepository
                    .findByTokenAndExpiresAtAfter(loginResponse.token(), Instant.now().minusSeconds(1))
                    .orElseThrow(() -> new AssertionError("Issued token not found in repository"));

            int configuredHours = adminProperties.getTokenExpiryHours();
            Instant expectedExpiryLow = before.plus(configuredHours, ChronoUnit.HOURS).minusSeconds(5);
            Instant expectedExpiryHigh = after.plus(configuredHours, ChronoUnit.HOURS).plusSeconds(5);

            assertThat(persistedToken.getExpiresAt())
                    .as("Token expiresAt must be approximately now + %d hours (within 5s tolerance)", configuredHours)
                    .isAfter(expectedExpiryLow)
                    .isBefore(expectedExpiryHigh);
        });
    }
}

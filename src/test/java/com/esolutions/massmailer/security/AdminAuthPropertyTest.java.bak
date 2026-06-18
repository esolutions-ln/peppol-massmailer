package com.esolutions.massmailer.security;

// Feature: admin-user-management, Property 3: Valid login returns token and name
// Feature: admin-user-management, Property 4: Invalid credentials return 401
// Feature: admin-user-management, Property 5: Login response never exposes password hash

import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for admin authentication via {@code POST /api/v1/admin/login}.
 *
 * <p><b>Property 3: Valid login returns token and name</b> — Validates: Requirements 2.1
 * <p><b>Property 4: Invalid credentials return 401</b> — Validates: Requirements 2.2
 * <p><b>Property 5: Login response never exposes password hash</b> — Validates: Requirements 2.4
 *
 * <p>Note: {@code @Transactional} is intentionally omitted — with {@code RANDOM_PORT} the HTTP
 * request runs in a separate thread/transaction, so test data must be committed before the
 * request is made. Cleanup is performed manually after each property try.
 */
@JqwikSpringSupport
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAuthPropertyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminSessionTokenRepository adminSessionTokenRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Saves a user, runs the action, then deletes the user (and its tokens) to keep the DB clean. */
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
    // Property 3: Valid login returns token and name
    // -------------------------------------------------------------------------

    /**
     * <b>Property 3: Valid login returns token and name</b>
     *
     * <p>For any active {@link AdminUser} with a known password, POST to
     * {@code /api/v1/admin/login} with correct credentials must return HTTP 200
     * with a non-blank {@code token} and the user's {@code displayName} as {@code name}.
     *
     * <p><b>Validates: Requirements 2.1</b>
     */
    @Property(tries = 10)
    void validLoginReturnsTokenAndName(
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
            RestTemplate restTemplate = new RestTemplate();
            Map<String, String> request = Map.of("username", uniqueUsername, "password", plainPassword);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/admin/login", request, Map.class);

            assertThat(response.getStatusCode())
                    .as("Valid credentials must return HTTP 200")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody()).isNotNull();

            Object token = response.getBody().get("token");
            assertThat(token)
                    .as("Response must contain a non-blank token")
                    .isNotNull()
                    .isInstanceOf(String.class);
            assertThat((String) token)
                    .as("Token must not be blank")
                    .isNotBlank();

            Object name = response.getBody().get("name");
            assertThat(name)
                    .as("Response must contain the user's display name as 'name'")
                    .isNotNull()
                    .isEqualTo(displayName);
        });
    }

    // -------------------------------------------------------------------------
    // Property 4: Invalid credentials return 401
    // -------------------------------------------------------------------------

    /**
     * <b>Property 4: Invalid credentials return 401</b>
     *
     * <p>For any login request where the username does not match an active user or
     * the password is incorrect, the response must be HTTP 401 with no token in the body.
     *
     * <p>Tests both: (a) wrong password for an existing user, and
     * (b) a non-existent username.
     *
     * <p><b>Validates: Requirements 2.2</b>
     */
    @Property(tries = 10)
    void invalidCredentialsReturn401(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String correctPassword,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String wrongPassword) {

        Assume.that(!correctPassword.equals(wrongPassword));

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = passwordEncoder.encode(correctPassword);

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName("Test User")
                .active(true)
                .build();

        withUser(user, () -> {
            RestTemplate restTemplate = new RestTemplate();

            // Case (a): correct username, wrong password — expect 401
            Map<String, String> wrongPassRequest = Map.of("username", uniqueUsername, "password", wrongPassword);
            assertThatThrownBy(() ->
                    restTemplate.postForEntity(baseUrl() + "/api/v1/admin/login", wrongPassRequest, Map.class))
                    .isInstanceOf(HttpClientErrorException.class)
                    .satisfies(ex -> {
                        HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                        assertThat(httpEx.getStatusCode())
                                .as("Wrong password must return HTTP 401")
                                .isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(httpEx.getResponseBodyAsString())
                                .as("401 response body must not contain a token field")
                                .doesNotContain("\"token\"");
                    });

            // Case (b): non-existent username — expect 401
            String nonExistentUsername = "nouser" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            Map<String, String> noUserRequest = Map.of("username", nonExistentUsername, "password", correctPassword);
            assertThatThrownBy(() ->
                    restTemplate.postForEntity(baseUrl() + "/api/v1/admin/login", noUserRequest, Map.class))
                    .isInstanceOf(HttpClientErrorException.class)
                    .satisfies(ex -> {
                        HttpClientErrorException httpEx = (HttpClientErrorException) ex;
                        assertThat(httpEx.getStatusCode())
                                .as("Non-existent username must return HTTP 401")
                                .isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(httpEx.getResponseBodyAsString())
                                .as("401 response body must not contain a token field")
                                .doesNotContain("\"token\"");
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Property 5: Login response never exposes password hash
    // -------------------------------------------------------------------------

    /**
     * <b>Property 5: Login response never exposes password hash</b>
     *
     * <p>For any login attempt (successful or not), the response body must not
     * contain the stored bcrypt hash string.
     *
     * <p><b>Validates: Requirements 2.4</b>
     */
    @Property(tries = 10)
    void loginResponseNeverExposesPasswordHash(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName,
            @ForAll @AlphaChars @StringLength(min = 8, max = 30) String plainPassword) {

        String uniqueUsername = baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = passwordEncoder.encode(plainPassword);

        AdminUser user = AdminUser.builder()
                .username(uniqueUsername)
                .passwordHash(hash)
                .displayName("Hash Test User")
                .active(true)
                .build();

        withUser(user, () -> {
            RestTemplate restTemplate = new RestTemplate();

            // Case (a): successful login — response must not contain the hash
            Map<String, String> correctRequest = Map.of("username", uniqueUsername, "password", plainPassword);
            ResponseEntity<String> successResponse = restTemplate.postForEntity(
                    baseUrl() + "/api/v1/admin/login", correctRequest, String.class);

            assertThat(successResponse.getBody())
                    .as("Successful login response body must not contain the bcrypt hash")
                    .doesNotContain(hash);

            // Case (b): failed login — response must not contain the hash either
            Map<String, String> wrongRequest = Map.of("username", uniqueUsername, "password", plainPassword + "WRONG");
            try {
                restTemplate.postForEntity(baseUrl() + "/api/v1/admin/login", wrongRequest, String.class);
            } catch (HttpClientErrorException ex) {
                assertThat(ex.getResponseBodyAsString())
                        .as("Failed login response body must not contain the bcrypt hash")
                        .doesNotContain(hash);
            }
        });
    }
}

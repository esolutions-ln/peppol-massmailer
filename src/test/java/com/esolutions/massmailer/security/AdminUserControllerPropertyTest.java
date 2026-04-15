package com.esolutions.massmailer.security;

// Feature: admin-user-management, Property 12: Admin-only endpoints reject non-admin callers
// Feature: admin-user-management, Property 13: User list contains all users and no sensitive fields

import com.esolutions.massmailer.security.model.AdminSessionToken;
import com.esolutions.massmailer.security.model.AdminUser;
import com.esolutions.massmailer.security.repository.AdminSessionTokenRepository;
import com.esolutions.massmailer.security.repository.AdminUserRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for {@link AdminUserController}.
 *
 * <p><b>Property 12: Admin-only endpoints reject non-admin callers</b>
 * — Validates: Requirements 4.4, 5.3, 6.4, 7.3
 *
 * <p><b>Property 13: User list contains all users and no sensitive fields</b>
 * — Validates: Requirements 5.1, 5.2
 *
 * <p>Note: {@code @Transactional} is intentionally omitted — with {@code RANDOM_PORT} the HTTP
 * request runs in a separate thread/transaction, so test data must be committed before the
 * request is made. Cleanup is performed manually after each property try.
 */
@JqwikSpringSupport
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserControllerPropertyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private AdminSessionTokenRepository adminSessionTokenRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/admin/users";
    }

    /** Creates a RestTemplate that supports PATCH via Apache HttpComponents. */
    private RestTemplate patchCapableRestTemplate() {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    /**
     * Saves an admin user and a valid session token, runs the action, then cleans up.
     * Returns the token string so the action can use it as X-API-Key.
     */
    private void withAdminSession(AdminUser user, java.util.function.Consumer<String> action) {
        AdminUser saved = adminUserRepository.saveAndFlush(user);
        String tokenValue = UUID.randomUUID().toString();
        AdminSessionToken sessionToken = adminSessionTokenRepository.saveAndFlush(
                AdminSessionToken.builder()
                        .token(tokenValue)
                        .adminUser(saved)
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build());
        try {
            action.accept(tokenValue);
        } finally {
            adminSessionTokenRepository.deleteByAdminUser(saved);
            adminUserRepository.delete(saved);
            adminUserRepository.flush();
        }
    }

    /**
     * Saves a list of admin users, runs the action, then cleans up all of them.
     */
    private void withUsers(List<AdminUser> users, java.util.function.Consumer<List<AdminUser>> action) {
        List<AdminUser> saved = new ArrayList<>();
        for (AdminUser u : users) {
            saved.add(adminUserRepository.saveAndFlush(u));
        }
        try {
            action.accept(saved);
        } finally {
            for (AdminUser u : saved) {
                adminSessionTokenRepository.deleteByAdminUser(u);
                adminUserRepository.delete(u);
            }
            adminUserRepository.flush();
        }
    }

    // -------------------------------------------------------------------------
    // Property 12: Admin-only endpoints reject non-admin callers
    // -------------------------------------------------------------------------

    /**
     * <b>Property 12: Admin-only endpoints reject non-admin callers</b>
     *
     * <p>For each admin endpoint (GET /users, POST /users, PATCH /users/{id}/deactivate,
     * PATCH /users/{id}/reactivate), requests made without a valid admin session token
     * must return HTTP 401 or 403.
     *
     * <p><b>Validates: Requirements 4.4, 5.3, 6.4, 7.3</b>
     */
    @Property(tries = 3)
    void adminOnlyEndpointsRejectNonAdminCallers(
            @ForAll @AlphaChars @StringLength(min = 3, max = 20) String baseName) {

        RestTemplate restTemplate = new RestTemplate();
        RestTemplate patchRestTemplate = patchCapableRestTemplate();
        UUID randomId = UUID.randomUUID();

        // GET /api/v1/admin/users — no auth header
        assertThatThrownBy(() ->
                restTemplate.getForEntity(baseUrl(), String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    int status = ((HttpClientErrorException) ex).getStatusCode().value();
                    assertThat(status)
                            .as("GET /users without auth must return 401 or 403")
                            .isIn(401, 403);
                });

        // POST /api/v1/admin/users — no auth header
        Map<String, String> createBody = Map.of(
                "username", baseName + UUID.randomUUID().toString().replace("-", "").substring(0, 6),
                "password", "password123",
                "displayName", "Test User");
        HttpEntity<Map<String, String>> postEntity = new HttpEntity<>(createBody);
        assertThatThrownBy(() ->
                restTemplate.postForEntity(baseUrl(), postEntity, String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    int status = ((HttpClientErrorException) ex).getStatusCode().value();
                    assertThat(status)
                            .as("POST /users without auth must return 401 or 403")
                            .isIn(401, 403);
                });

        // PATCH /api/v1/admin/users/{id}/deactivate — no auth header
        assertThatThrownBy(() -> {
            HttpEntity<Void> patchEntity = new HttpEntity<>((HttpHeaders) null);
            patchRestTemplate.exchange(
                    baseUrl() + "/" + randomId + "/deactivate",
                    HttpMethod.PATCH, patchEntity, String.class);
        })
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    int status = ((HttpClientErrorException) ex).getStatusCode().value();
                    assertThat(status)
                            .as("PATCH /users/{id}/deactivate without auth must return 401 or 403")
                            .isIn(401, 403);
                });

        // PATCH /api/v1/admin/users/{id}/reactivate — no auth header
        assertThatThrownBy(() -> {
            HttpEntity<Void> patchEntity = new HttpEntity<>((HttpHeaders) null);
            patchRestTemplate.exchange(
                    baseUrl() + "/" + randomId + "/reactivate",
                    HttpMethod.PATCH, patchEntity, String.class);
        })
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> {
                    int status = ((HttpClientErrorException) ex).getStatusCode().value();
                    assertThat(status)
                            .as("PATCH /users/{id}/reactivate without auth must return 401 or 403")
                            .isIn(401, 403);
                });
    }

    // -------------------------------------------------------------------------
    // Property 13: User list contains all users and no sensitive fields
    // -------------------------------------------------------------------------

    /**
     * <b>Property 13: User list contains all users and no sensitive fields</b>
     *
     * <p>For any set of admin users in the database, GET /api/v1/admin/users must:
     * <ul>
     *   <li>Return an entry for every created user (by username)</li>
     *   <li>Not include {@code passwordHash} in any entry</li>
     *   <li>Include the required fields: id, username, displayName, role, active, createdAt</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 5.1, 5.2</b>
     */
    @Property(tries = 3)
    void userListContainsAllUsersAndNoSensitiveFields(
            @ForAll @AlphaChars @StringLength(min = 3, max = 15) String baseName,
            @ForAll @IntRange(min = 2, max = 3) int userCount) {

        // Build N users with unique UUID-suffixed usernames
        List<AdminUser> usersToCreate = new ArrayList<>();
        List<String> expectedUsernames = new ArrayList<>();
        for (int i = 0; i < userCount; i++) {
            String username = baseName + i + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            expectedUsernames.add(username);
            usersToCreate.add(AdminUser.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode("password123"))
                    .displayName("Display " + i)
                    .build());
        }

        withUsers(usersToCreate, savedUsers -> {
            // Create a valid admin session token using the first saved user
            AdminUser tokenOwner = savedUsers.get(0);
            String tokenValue = UUID.randomUUID().toString();
            AdminSessionToken sessionToken = adminSessionTokenRepository.saveAndFlush(
                    AdminSessionToken.builder()
                            .token(tokenValue)
                            .adminUser(tokenOwner)
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build());

            try {
                RestTemplate restTemplate = new RestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-API-Key", tokenValue);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<List> response = restTemplate.exchange(
                        baseUrl(), HttpMethod.GET, entity, List.class);

                assertThat(response.getStatusCode())
                        .as("GET /users with valid admin token must return HTTP 200")
                        .isEqualTo(HttpStatus.OK);

                List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
                assertThat(body).isNotNull();

                // Collect all usernames from the response
                List<String> returnedUsernames = body.stream()
                        .map(entry -> (String) entry.get("username"))
                        .toList();

                // Every created user must appear in the response
                for (String expectedUsername : expectedUsernames) {
                    assertThat(returnedUsernames)
                            .as("Response must contain username: " + expectedUsername)
                            .contains(expectedUsername);
                }

                // No entry must contain passwordHash
                for (Map<String, Object> entry : body) {
                    assertThat(entry)
                            .as("Response entry must not contain 'passwordHash' field")
                            .doesNotContainKey("passwordHash");

                    // Required fields must be present
                    assertThat(entry)
                            .as("Response entry must contain 'id'")
                            .containsKey("id");
                    assertThat(entry)
                            .as("Response entry must contain 'username'")
                            .containsKey("username");
                    assertThat(entry)
                            .as("Response entry must contain 'displayName'")
                            .containsKey("displayName");
                    assertThat(entry)
                            .as("Response entry must contain 'role'")
                            .containsKey("role");
                    assertThat(entry)
                            .as("Response entry must contain 'active'")
                            .containsKey("active");
                    assertThat(entry)
                            .as("Response entry must contain 'createdAt'")
                            .containsKey("createdAt");
                }
            } finally {
                adminSessionTokenRepository.deleteByAdminUser(tokenOwner);
            }
        });
    }
}

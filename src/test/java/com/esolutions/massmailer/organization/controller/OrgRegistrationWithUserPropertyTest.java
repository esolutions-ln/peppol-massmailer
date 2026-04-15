package com.esolutions.massmailer.organization.controller;

// Property 20: Organisation registration persists the user contact block
// Property 21: Registered org is retrievable by slug with user block intact
// Property 22: Missing user block fields are rejected with HTTP 400

import com.esolutions.massmailer.organization.repository.OrgUserRepository;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.spring.JqwikSpringSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration property tests for organisation self-registration with the OrgUser contact block.
 *
 * <p><b>Property 20:</b> A valid registration request persists both the Organisation and
 * the OrgUser, and the response contains the user block with matching fields.
 *
 * <p><b>Property 21:</b> After registration, GET /by-slug/{slug} returns the org summary
 * including the user block with the same data.
 *
 * <p><b>Property 22:</b> Requests missing required user fields (firstName, lastName, emailAddress)
 * are rejected with HTTP 400 before any persistence occurs.
 */
@JqwikSpringSupport
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrgRegistrationWithUserPropertyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OrganizationRepository orgRepo;

    @Autowired
    private OrgUserRepository orgUserRepo;

    private String orgsUrl() {
        return "http://localhost:" + port + "/api/v1/organizations";
    }

    // ── Property 20: Registration persists user block ────────────────────────

    /**
     * <b>Property 20:</b> For any valid registration payload, the 201 response must contain
     * a {@code user} object whose fields match the submitted values, and the OrgUser must
     * be persisted in the database linked to the created organisation.
     */
    @Property(tries = 5)
    void registrationPersistsUserBlock(
            @ForAll @AlphaChars @StringLength(min = 3, max = 10) String baseName) {

        String slug = "prop20-" + baseName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        String firstName = "Alice";
        String lastName = "Smith";
        String jobTitle = "Finance Manager";
        String email = "alice." + slug + "@example.com";

        Map<String, Object> body = buildPayload(slug, firstName, lastName, jobTitle, email, baseName);
        RestTemplate rest = new RestTemplate();
        HttpEntity<Map<String, Object>> req = jsonEntity(body);

        ResponseEntity<Map> response = rest.postForEntity(orgsUrl(), req, Map.class);

        try {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<?, ?> respBody = response.getBody();
            assertThat(respBody).isNotNull();

            // apiKey must be present and 32 hex chars
            String apiKey = (String) respBody.get("apiKey");
            assertThat(apiKey).matches("[0-9a-f]{32}");

            // user block must be present
            Map<?, ?> userBlock = (Map<?, ?>) respBody.get("user");
            assertThat(userBlock).as("response must contain 'user' block").isNotNull();
            assertThat(userBlock.get("firstName")).isEqualTo(firstName);
            assertThat(userBlock.get("lastName")).isEqualTo(lastName);
            assertThat(userBlock.get("jobTitle")).isEqualTo(jobTitle);
            assertThat(userBlock.get("emailAddress")).isEqualTo(email);
            assertThat(userBlock.get("id")).isNotNull();

            // OrgUser must be persisted in DB
            UUID orgId = UUID.fromString((String) respBody.get("id"));
            var dbUser = orgUserRepo.findByOrganizationId(orgId);
            assertThat(dbUser).as("OrgUser must be persisted in DB").isPresent();
            assertThat(dbUser.get().getFirstName()).isEqualTo(firstName);
            assertThat(dbUser.get().getLastName()).isEqualTo(lastName);
            assertThat(dbUser.get().getEmail()).isEqualTo(email);

        } finally {
            cleanup(slug);
        }
    }

    // ── Property 21: GET by-slug returns user block ──────────────────────────

    /**
     * <b>Property 21:</b> After a successful registration, GET /api/v1/organizations/by-slug/{slug}
     * must return the org summary including the user block with the same data that was submitted.
     */
    @Property(tries = 5)
    void getBySlugReturnsUserBlock(
            @ForAll @AlphaChars @StringLength(min = 3, max = 10) String baseName) {

        String slug = "prop21-" + baseName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        String firstName = "Bob";
        String lastName = "Jones";
        String email = "bob." + slug + "@example.com";

        Map<String, Object> body = buildPayload(slug, firstName, lastName, "Director", email, baseName);
        RestTemplate rest = new RestTemplate();

        // Register
        rest.postForEntity(orgsUrl(), jsonEntity(body), Map.class);

        try {
            // Fetch by slug
            ResponseEntity<Map> getResp = rest.getForEntity(orgsUrl() + "/by-slug/" + slug, Map.class);

            assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<?, ?> respBody = getResp.getBody();
            assertThat(respBody).isNotNull();

            Map<?, ?> userBlock = (Map<?, ?>) respBody.get("user");
            assertThat(userBlock).as("GET by-slug response must contain 'user' block").isNotNull();
            assertThat(userBlock.get("firstName")).isEqualTo(firstName);
            assertThat(userBlock.get("lastName")).isEqualTo(lastName);
            assertThat(userBlock.get("emailAddress")).isEqualTo(email);

        } finally {
            cleanup(slug);
        }
    }

    // ── Property 22: Missing required user fields → HTTP 400 ─────────────────

    /**
     * <b>Property 22:</b> A registration request with a missing or null {@code user.firstName},
     * {@code user.lastName}, or {@code user.emailAddress} must be rejected with HTTP 400,
     * and no Organisation or OrgUser must be persisted.
     */
    @Property(tries = 3)
    void missingUserFieldsRejectedWith400(
            @ForAll @AlphaChars @StringLength(min = 3, max = 10) String baseName) {

        RestTemplate rest = new RestTemplate();

        // Case 1: user block entirely absent — now valid (user is optional)
        String slug1 = "prop22a-" + baseName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> noUser = Map.of(
                "name", "Test Org",
                "slug", slug1,
                "senderEmail", "noreply@test.com",
                "senderDisplayName", "Test"
        );
        try {
            ResponseEntity<Map> resp1 = rest.postForEntity(orgsUrl(), jsonEntity(noUser), Map.class);
            assertThat(resp1.getStatusCode().value())
                    .as("absent user block is optional — must return 201").isEqualTo(201);
        } finally {
            cleanup(slug1);
        }

        // Case 2: user block present but firstName is blank
        String slug2 = "prop22b-" + baseName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> blankFirst = buildPayload(slug2, "", "Doe", "CFO", "x@x.com", baseName);
        assertThatThrownBy(() -> rest.postForEntity(orgsUrl(), jsonEntity(blankFirst), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode().value())
                        .as("blank firstName must return 400").isEqualTo(400));
        assertThat(orgRepo.findBySlug(slug2)).as("no org persisted on 400").isEmpty();

        // Case 3: user block present but emailAddress is invalid
        String slug3 = "prop22c-" + baseName.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> badEmail = buildPayload(slug3, "Jane", "Doe", "CFO", "not-an-email", baseName);
        assertThatThrownBy(() -> rest.postForEntity(orgsUrl(), jsonEntity(badEmail), Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode().value())
                        .as("invalid email must return 400").isEqualTo(400));
        assertThat(orgRepo.findBySlug(slug3)).as("no org persisted on 400").isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildPayload(String slug, String firstName, String lastName,
                                              String jobTitle, String email, String baseName) {
        return Map.of(
                "user", Map.of(
                        "firstName", firstName,
                        "lastName", lastName,
                        "jobTitle", jobTitle,
                        "emailAddress", email
                ),
                "name", "Org " + baseName,
                "slug", slug,
                "senderEmail", "noreply@" + slug + ".com",
                "senderDisplayName", "Sender " + baseName,
                "vatNumber", "ZW" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        );
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** Removes test data created during a property try. */
    private void cleanup(String slug) {
        orgRepo.findBySlug(slug).ifPresent(org -> {
            orgUserRepo.findByOrganizationId(org.getId()).ifPresent(orgUserRepo::delete);
            orgRepo.delete(org);
            orgRepo.flush();
        });
    }
}

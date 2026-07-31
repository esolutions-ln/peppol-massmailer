package com.esolutions.massmailer.organization;

import com.esolutions.massmailer.organization.model.OrgMember;
import com.esolutions.massmailer.organization.model.OrgMemberRole;
import com.esolutions.massmailer.organization.repository.OrgMemberRepository;
import com.esolutions.massmailer.organization.service.OrgAuthService;
import com.esolutions.massmailer.organization.service.OrgMemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrgMemberIntegrationTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrgMemberService memberService;
    @Autowired OrgAuthService authService;
    @Autowired OrgMemberRepository memberRepo;

    MockMvc mockMvc;
    static UUID orgId;
    static String orgApiKey;
    static String orgSlug = "members-test-org";
    static String adminToken;
    static String viewerToken;
    static UUID adminMemberId;
    static UUID viewerMemberId;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mail.host", () -> "localhost");
        r.add("spring.mail.port", () -> 3025);
        r.add("spring.mail.username", () -> "");
        r.add("spring.mail.password", () -> "");
        r.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.enabled", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        r.add("massmailer.from-address", () -> "noreply@test.com");
        r.add("massmailer.from-name", () -> "Test Mailer");
    }

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters(wac.getBean(org.springframework.security.web.FilterChainProxy.class))
                .build();
    }

    @Test @Order(1)
    void registerOrgAndSeedAdminMember() throws Exception {
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Members Test Org",
                                  "slug": "%s",
                                  "senderEmail": "noreply@members-test.co.zw",
                                  "senderDisplayName": "Members Test",
                                  "deliveryMode": "EMAIL"
                                }
                                """.formatted(orgSlug)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        orgId = UUID.fromString(node.get("id").asText());
        orgApiKey = node.get("apiKey").asText();

        // Seed an ORG_ADMIN member via the service (bootstrap path — UI/admin flow would
        // call this on first login, or org register would create a default admin).
        var admin = memberService.create(orgId, "owner@members-test.co.zw",
                "ownerpass123", "Owner Admin", OrgMemberRole.ORG_ADMIN);
        adminMemberId = admin.getId();
    }

    @Test @Order(2)
    void adminMemberCanLogIn() throws Exception {
        var body = mockMvc.perform(post("/api/v1/org/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "slug": "%s",
                                  "email": "owner@members-test.co.zw",
                                  "password": "ownerpass123" }
                                """.formatted(orgSlug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.orgId").value(orgId.toString()))
                .andReturn().getResponse().getContentAsString();
        adminToken = objectMapper.readTree(body).get("token").asText();
        assertThat(adminToken).hasSizeGreaterThan(40);
    }

    @Test @Order(3)
    void adminCanCreateViewerMember() throws Exception {
        var body = mockMvc.perform(post("/api/v1/my/members")
                        .header("X-API-Key", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "viewer@members-test.co.zw",
                                  "password": "viewerpass123",
                                  "displayName": "Read Only",
                                  "role": "ORG_VIEWER" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ORG_VIEWER"))
                .andExpect(jsonPath("$.email").value("viewer@members-test.co.zw"))
                .andReturn().getResponse().getContentAsString();
        viewerMemberId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test @Order(4)
    void viewerCanLogIn() throws Exception {
        var body = mockMvc.perform(post("/api/v1/org/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "slug": "%s",
                                  "email": "viewer@members-test.co.zw",
                                  "password": "viewerpass123" }
                                """.formatted(orgSlug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_VIEWER"))
                .andReturn().getResponse().getContentAsString();
        viewerToken = objectMapper.readTree(body).get("token").asText();
    }

    @Test @Order(5)
    void viewerCanListInvoices() throws Exception {
        mockMvc.perform(get("/api/v1/my/invoices").header("X-API-Key", viewerToken))
                .andExpect(status().isOk());
    }

    @Test @Order(6)
    void viewerCannotManageMembers() throws Exception {
        mockMvc.perform(get("/api/v1/my/members").header("X-API-Key", viewerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/my/members")
                        .header("X-API-Key", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "nope@members-test.co.zw",
                                  "password": "nopenopenope",
                                  "role": "ORG_VIEWER" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test @Order(7)
    void viewerCannotSendInvoices() throws Exception {
        // Mail send routes are full-ORG only — viewers must not be able to dispatch.
        mockMvc.perform(post("/api/v1/mail/invoice")
                        .header("X-API-Key", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "to": "x@y.com", "subject": "x", "templateName": "invoice",
                                  "invoiceNumber": "TEST-1" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test @Order(8)
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/org/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "slug": "%s",
                                  "email": "owner@members-test.co.zw",
                                  "password": "wrong" }
                                """.formatted(orgSlug)))
                .andExpect(status().isUnauthorized());
    }

    @Test @Order(9)
    void deactivatingViewerRevokesTheirSessions() throws Exception {
        mockMvc.perform(put("/api/v1/my/members/" + viewerMemberId + "/active")
                        .header("X-API-Key", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Existing viewer token must now fail (sessions purged on deactivate)
        mockMvc.perform(get("/api/v1/my/invoices").header("X-API-Key", viewerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test @Order(10)
    void cannotCreateDuplicateEmailWithinOrg() throws Exception {
        mockMvc.perform(post("/api/v1/my/members")
                        .header("X-API-Key", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "owner@members-test.co.zw",
                                  "password": "anotherone",
                                  "role": "ORG_ADMIN" }
                                """))
                .andExpect(status().isConflict());
    }

    @Test @Order(11)
    void adminCannotDeleteThemself() throws Exception {
        mockMvc.perform(delete("/api/v1/my/members/" + adminMemberId)
                        .header("X-API-Key", adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(12)
    void legacyApiKeyStillGrantsFullOrgAccess() throws Exception {
        // The original org integration credential (X-API-Key = org.apiKey) must still work.
        mockMvc.perform(get("/api/v1/my/members").header("X-API-Key", orgApiKey))
                .andExpect(status().isOk());
    }

    @Test @Order(13)
    void registrationWithPasswordCreatesOrgAdminAndStillIssuesApiKey() throws Exception {
        // Register a brand-new org that includes a password on the user block.
        // We expect: (1) the org row to be persisted with a generated apiKey,
        // and (2) a sign-in via /api/v1/org/login to succeed with ORG_ADMIN role.
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Bootstrap Co",
                                  "slug": "bootstrap-co",
                                  "senderEmail": "noreply@bootstrap.co.zw",
                                  "senderDisplayName": "Bootstrap",
                                  "deliveryMode": "EMAIL",
                                  "user": {
                                    "firstName": "Boot",
                                    "lastName": "Strapper",
                                    "jobTitle": "Founder",
                                    "emailAddress": "founder@bootstrap.co.zw",
                                    "password": "founderpass123"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var newOrgApiKey = objectMapper.readTree(body).get("apiKey").asText();
        assertThat(newOrgApiKey).hasSizeGreaterThanOrEqualTo(32);

        // The auto-bootstrapped ORG_ADMIN member can immediately log in.
        var loginBody = mockMvc.perform(post("/api/v1/org/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "slug": "bootstrap-co",
                                  "email": "founder@bootstrap.co.zw",
                                  "password": "founderpass123" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.displayName").value("Boot Strapper"))
                .andReturn().getResponse().getContentAsString();

        String memberToken = objectMapper.readTree(loginBody).get("token").asText();

        // Both credentials work — member can manage members, API key still grants ORG.
        mockMvc.perform(get("/api/v1/my/members").header("X-API-Key", memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/my/members").header("X-API-Key", newOrgApiKey))
                .andExpect(status().isOk());
    }

    @Test @Order(14)
    void registrationWithoutPasswordDoesNotCreateMember() throws Exception {
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "NoPwd Co",
                                  "slug": "nopwd-co",
                                  "senderEmail": "noreply@nopwd.co.zw",
                                  "senderDisplayName": "NoPwd",
                                  "deliveryMode": "EMAIL",
                                  "user": {
                                    "firstName": "No",
                                    "lastName": "Password",
                                    "emailAddress": "no@nopwd.co.zw"
                                  }
                                }
                                """))
                .andExpect(status().isCreated());

        // No password was supplied — login must fail (no member exists yet).
        mockMvc.perform(post("/api/v1/org/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "slug": "nopwd-co", "email": "no@nopwd.co.zw", "password": "anything" }
                                """))
                .andExpect(status().isUnauthorized());
    }
}

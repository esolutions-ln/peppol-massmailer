package com.esolutions.massmailer;

import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Seeds and validates the First Mutual Properties Ltd test organisation.
 *
 * Organisation details:
 *   Name       : First Mutual Properties Ltd
 *   TIN        : 2000161225
 *   VAT Number : 220045798
 *   Device ID  : 29558
 *   Mode       : EMAIL (batch invoice emails to customers)
 *   PEPPOL ID  : 0190:ZW220045798  (VAT-based, as they have a VAT number)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstMutualPropertiesSeedTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository orgRepo;

    private MockMvc mockMvc;

    private static String orgId;
    private static String apiKey;

    @BeforeEach
    void setup() {
        mockMvc = webAppContextSetup(wac).build();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mail.host", () -> "localhost");
        r.add("spring.mail.port", () -> "3025");
        r.add("spring.mail.username", () -> "");
        r.add("spring.mail.password", () -> "");
        r.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.enabled", () -> "false");
        r.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        r.add("massmailer.from-address", () -> "noreply@firstmutual.co.zw");
        r.add("massmailer.from-name", () -> "First Mutual Properties");
        r.add("massmailer.rate-limit", () -> "5");
        r.add("massmailer.batch-size", () -> "50");
    }

    // ─────────────────────────────────────────────────────────────
    //  1. Register the organisation
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Register First Mutual Properties Ltd")
    void registerOrganisation() throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "First Mutual Properties Ltd",
                                  "slug": "first-mutual-properties",
                                  "senderEmail": "noreply@firstmutual.co.zw",
                                  "senderDisplayName": "First Mutual Properties",
                                  "accountsEmail": "accounts@firstmutual.co.zw",
                                  "companyAddress": "First Mutual Centre, 100 Borrowdale Road, Harare, Zimbabwe",
                                  "primaryErpSource": "GENERIC_API",
                                  "erpTenantId": "first-mutual-properties",
                                  "deliveryMode": "EMAIL",
                                  "vatNumber": "220045798",
                                  "tinNumber": "2000161225"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("First Mutual Properties Ltd"))
                .andExpect(jsonPath("$.slug").value("first-mutual-properties"))
                .andExpect(jsonPath("$.deliveryMode").value("EMAIL"))
                .andExpect(jsonPath("$.vatNumber").value("220045798"))
                .andExpect(jsonPath("$.tinNumber").value("2000161225"))
                // VAT takes priority → participant ID uses VAT
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW220045798"))
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        orgId  = node.get("id").asText();
        apiKey = node.get("apiKey").asText();

        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║       FIRST MUTUAL PROPERTIES — CREDENTIALS          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf ("║  Org ID  : %-42s ║%n", orgId);
        System.out.printf ("║  API Key : %-42s ║%n", apiKey);
        System.out.printf ("║  PEPPOL  : %-42s ║%n", "0190:ZW220045798");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");
    }

    // ─────────────────────────────────────────────────────────────
    //  2. Verify persisted correctly in the database
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Verify organisation persisted with correct delivery config")
    void verifyPersistedOrg() {
        assertThat(orgId).isNotNull();

        Organization org = orgRepo.findBySlug("first-mutual-properties").orElseThrow();

        assertThat(org.getName()).isEqualTo("First Mutual Properties Ltd");
        assertThat(org.getDeliveryMode()).isEqualTo(DeliveryMode.EMAIL);
        assertThat(org.getVatNumber()).isEqualTo("220045798");
        assertThat(org.getTinNumber()).isEqualTo("2000161225");
        assertThat(org.getPeppolParticipantId()).isEqualTo("0190:ZW220045798");
        assertThat(org.getSenderEmail()).isEqualTo("noreply@firstmutual.co.zw");
        assertThat(org.getApiKey()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────
    //  3. Register the PEPPOL Gateway Access Point (Device ID: 29558)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Register PEPPOL Gateway Access Point for First Mutual (Device ID: 29558)")
    void registerGatewayAccessPoint() throws Exception {
        assertThat(orgId).isNotNull();

        mockMvc.perform(post("/api/v1/eregistry/access-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                  "participantId": "0190:ZW220045798",
                                  "participantName": "First Mutual Properties Gateway (Device 29558)",
                                  "role": "GATEWAY",
                                  "endpointUrl": "https://gateway.firstmutual.co.zw/peppol/as4",
                                  "simplifiedHttpDelivery": true,
                                  "organizationId": "%s"
                                }
                                """, orgId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantId").value("0190:ZW220045798"))
                .andExpect(jsonPath("$.role").value("GATEWAY"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ─────────────────────────────────────────────────────────────
    //  4. Fetch org by slug — simulates frontend login flow
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET by slug — simulates frontend login lookup")
    void fetchBySlug() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/by-slug/first-mutual-properties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("First Mutual Properties Ltd"))
                .andExpect(jsonPath("$.deliveryMode").value("EMAIL"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW220045798"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ─────────────────────────────────────────────────────────────
    //  5. Pre-register a sample customer (tenant in a property)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Pre-register a sample tenant customer for batch invoice testing")
    void registerSampleCustomer() throws Exception {
        assertThat(orgId).isNotNull();

        mockMvc.perform(post("/api/v1/organizations/" + orgId + "/customers")
                        .header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "tenant@acmecorp.co.zw",
                                  "name": "Acme Corporation",
                                  "companyName": "Acme Corporation (Pvt) Ltd",
                                  "erpSource": "GENERIC_API",
                                  "deliveryMode": "EMAIL",
                                  "vatNumber": "100012345"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("tenant@acmecorp.co.zw"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW100012345"))
                .andExpect(jsonPath("$.deliveryMode").value("EMAIL"));
    }

    // ─────────────────────────────────────────────────────────────
    //  6. List customers — verify the tenant appears
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("List customers — tenant appears in registry")
    void listCustomers() throws Exception {
        assertThat(orgId).isNotNull();

        mockMvc.perform(get("/api/v1/organizations/" + orgId + "/customers")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("tenant@acmecorp.co.zw"))
                .andExpect(jsonPath("$[0].companyName").value("Acme Corporation (Pvt) Ltd"));
    }
}

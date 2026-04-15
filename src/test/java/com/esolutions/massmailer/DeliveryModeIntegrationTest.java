package com.esolutions.massmailer;

import com.esolutions.massmailer.customer.model.CustomerContact;
import com.esolutions.massmailer.customer.repository.CustomerContactRepository;
import com.esolutions.massmailer.model.DeliveryMode;
import com.esolutions.massmailer.organization.model.Organization;
import com.esolutions.massmailer.organization.repository.OrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Integration tests for delivery mode configuration:
 *
 * 1. Register org with EMAIL / AS4 / BOTH delivery mode
 * 2. Verify PEPPOL participant ID is derived from VAT / TIN
 * 3. Register customer with delivery mode override
 * 4. Verify effective mode resolution (customer override → org default)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeliveryModeIntegrationTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository orgRepo;
    @Autowired CustomerContactRepository customerRepo;

    private MockMvc mockMvc;

    // Shared state across ordered tests
    private static UUID emailOrgId;
    private static UUID as4OrgId;
    private static UUID bothOrgId;
    private static String emailOrgApiKey;

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
        r.add("massmailer.from-address", () -> "noreply@test.com");
        r.add("massmailer.from-name", () -> "Test Mailer");
        r.add("massmailer.rate-limit", () -> "5");
        r.add("massmailer.batch-size", () -> "10");
    }

    // ─────────────────────────────────────────────────────────────
    //  1. Register org with EMAIL delivery mode (default)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Register org with EMAIL delivery mode — no VAT/TIN required")
    void registerEmailOrg() throws Exception {
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Email Corp",
                                  "slug": "email-corp-test",
                                  "senderEmail": "noreply@emailcorp.co.zw",
                                  "senderDisplayName": "Email Corp Accounts",
                                  "deliveryMode": "EMAIL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryMode").value("EMAIL"))
                .andExpect(jsonPath("$.peppolParticipantId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(body);
        emailOrgId = UUID.fromString(node.get("id").asText());
        emailOrgApiKey = node.get("apiKey").asText();

        // Verify persisted correctly
        Organization org = orgRepo.findById(emailOrgId).orElseThrow();
        assertThat(org.getDeliveryMode()).isEqualTo(DeliveryMode.EMAIL);
        assertThat(org.getPeppolParticipantId()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    //  2. Register org with AS4 + VAT number → participant ID derived
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Register org with AS4 + VAT number — participant ID auto-derived as 0190:ZW{vat}")
    void registerAs4OrgWithVat() throws Exception {
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "AS4 Corp",
                                  "slug": "as4-corp-test",
                                  "senderEmail": "noreply@as4corp.co.zw",
                                  "senderDisplayName": "AS4 Corp Accounts",
                                  "deliveryMode": "AS4",
                                  "vatNumber": "12345678"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryMode").value("AS4"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW12345678"))
                .andExpect(jsonPath("$.vatNumber").value("12345678"))
                .andReturn().getResponse().getContentAsString();

        as4OrgId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        Organization org = orgRepo.findById(as4OrgId).orElseThrow();
        assertThat(org.getDeliveryMode()).isEqualTo(DeliveryMode.AS4);
        assertThat(org.getPeppolParticipantId()).isEqualTo("0190:ZW12345678");
        assertThat(org.getVatNumber()).isEqualTo("12345678");
    }

    // ─────────────────────────────────────────────────────────────
    //  3. Register org with BOTH + TIN (no VAT) → TIN used for participant ID
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Register org with BOTH + TIN only — participant ID derived as 0190:ZW{tin}")
    void registerBothOrgWithTin() throws Exception {
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Both Corp",
                                  "slug": "both-corp-test",
                                  "senderEmail": "noreply@bothcorp.co.zw",
                                  "senderDisplayName": "Both Corp Accounts",
                                  "deliveryMode": "BOTH",
                                  "tinNumber": "9876543210"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryMode").value("BOTH"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW9876543210"))
                .andExpect(jsonPath("$.tinNumber").value("9876543210"))
                .andReturn().getResponse().getContentAsString();

        bothOrgId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        Organization org = orgRepo.findById(bothOrgId).orElseThrow();
        assertThat(org.getDeliveryMode()).isEqualTo(DeliveryMode.BOTH);
        assertThat(org.getPeppolParticipantId()).isEqualTo("0190:ZW9876543210");
    }

    // ─────────────────────────────────────────────────────────────
    //  4. VAT takes priority over TIN when both are supplied
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("VAT takes priority over TIN when both supplied")
    void vatTakesPriorityOverTin() throws Exception {
        mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Priority Corp",
                                  "slug": "priority-corp-test",
                                  "senderEmail": "noreply@priority.co.zw",
                                  "senderDisplayName": "Priority Corp",
                                  "deliveryMode": "AS4",
                                  "vatNumber": "11111111",
                                  "tinNumber": "9999999999"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW11111111"));
    }

    // ─────────────────────────────────────────────────────────────
    //  5. Register customer with delivery mode override + VAT
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Register customer with AS4 override + VAT — participant ID derived")
    void registerCustomerWithAs4AndVat() throws Exception {
        assertThat(emailOrgId).isNotNull();

        mockMvc.perform(post("/api/v1/organizations/" + emailOrgId + "/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@acme.co.zw",
                                  "name": "Acme Buyer",
                                  "companyName": "Acme Corp",
                                  "deliveryMode": "AS4",
                                  "vatNumber": "55556666"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryMode").value("AS4"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW55556666"))
                .andExpect(jsonPath("$.vatNumber").value("55556666"));

        CustomerContact c = customerRepo
                .findByOrganizationIdAndEmail(emailOrgId, "buyer@acme.co.zw")
                .orElseThrow();
        assertThat(c.getDeliveryMode()).isEqualTo(DeliveryMode.AS4);
        assertThat(c.getPeppolParticipantId()).isEqualTo("0190:ZW55556666");
    }

    // ─────────────────────────────────────────────────────────────
    //  6. Register customer with TIN only (no VAT)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Register customer with TIN only — participant ID derived as 0190:ZW{tin}")
    void registerCustomerWithTinOnly() throws Exception {
        assertThat(emailOrgId).isNotNull();

        mockMvc.perform(post("/api/v1/organizations/" + emailOrgId + "/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "tin-only@vendor.co.zw",
                                  "name": "TIN Vendor",
                                  "deliveryMode": "AS4",
                                  "tinNumber": "1234567890"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW1234567890"))
                .andExpect(jsonPath("$.tinNumber").value("1234567890"));
    }

    // ─────────────────────────────────────────────────────────────
    //  7. Customer with no delivery mode override inherits org default
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Customer with no deliveryMode inherits org default (null = inherit)")
    void customerInheritsOrgDeliveryMode() throws Exception {
        assertThat(emailOrgId).isNotNull();

        mockMvc.perform(post("/api/v1/organizations/" + emailOrgId + "/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "inherit@vendor.co.zw",
                                  "name": "Inherit Vendor"
                                }
                                """))
                .andExpect(status().isCreated())
                // deliveryMode should be null — inherits from org at dispatch time
                .andExpect(jsonPath("$.deliveryMode").doesNotExist());

        CustomerContact c = customerRepo
                .findByOrganizationIdAndEmail(emailOrgId, "inherit@vendor.co.zw")
                .orElseThrow();
        assertThat(c.getDeliveryMode()).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    //  8. List customers — response includes deliveryMode + participantId
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("GET /customers — response includes deliveryMode and peppolParticipantId")
    void listCustomersIncludesDeliveryFields() throws Exception {
        assertThat(emailOrgId).isNotNull();

        mockMvc.perform(get("/api/v1/organizations/" + emailOrgId + "/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'buyer@acme.co.zw')].deliveryMode")
                        .value(org.hamcrest.Matchers.hasItem("AS4")))
                .andExpect(jsonPath("$[?(@.email == 'buyer@acme.co.zw')].peppolParticipantId")
                        .value(org.hamcrest.Matchers.hasItem("0190:ZW55556666")));
    }

    // ─────────────────────────────────────────────────────────────
    //  9. GET org by slug — response includes deliveryMode fields
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("GET /organizations/by-slug — response includes deliveryMode and peppolParticipantId")
    void getOrgBySlugIncludesDeliveryFields() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/by-slug/as4-corp-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryMode").value("AS4"))
                .andExpect(jsonPath("$.peppolParticipantId").value("0190:ZW12345678"))
                .andExpect(jsonPath("$.vatNumber").value("12345678"));
    }

    // ─────────────────────────────────────────────────────────────
    //  10. Default delivery mode is EMAIL when not specified
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Org registered without deliveryMode defaults to EMAIL")
    void orgDefaultsToEmailWhenNotSpecified() throws Exception {
        var body = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Default Corp",
                                  "slug": "default-corp-test",
                                  "senderEmail": "noreply@default.co.zw",
                                  "senderDisplayName": "Default Corp"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryMode").value("EMAIL"))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        Organization org = orgRepo.findById(id).orElseThrow();
        assertThat(org.getDeliveryMode()).isEqualTo(DeliveryMode.EMAIL);
    }
}

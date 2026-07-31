package com.esolutions.massmailer;

import com.esolutions.massmailer.peppol.model.PeppolDeliveryRecord.DeliveryStatus;
import com.esolutions.massmailer.peppol.repository.PeppolDeliveryRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * End-to-end UAT: ERP prints invoice to PDF → folder watcher / upload endpoint picks it up
 * → PeppolDeliveryService routes it to the buyer's Access Point via simplified HTTP.
 *
 * <h3>Test flow</h3>
 * <ol>
 *   <li>Register an organisation with delivery mode AS4</li>
 *   <li>Register sender gateway AP (C2)</li>
 *   <li>Register mock receiver AP (C3, simplifiedHttpDelivery=true, URL=mock server)</li>
 *   <li>Register buyer customer contact</li>
 *   <li>Link customer to receiver AP via participant link</li>
 *   <li>Arm MockRestServiceServer to expect POST to mock receiver URL</li>
 *   <li>POST /api/v1/erp/dispatch/upload with invoice PDF + metadata</li>
 *   <li>Assert response: peppolDispatched=1, status entry=DELIVERED</li>
 *   <li>Verify mock server received the UBL XML request</li>
 *   <li>Assert PeppolDeliveryRecord persisted with DELIVERED status</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErpPdfDispatchUatTest {

    // ── Shared state across ordered test methods ──────────────────────────────

    private static UUID orgId;
    private static UUID receiverApId;
    private static final String BUYER_EMAIL = "buyer@acmecorp.co.zw";
    private static final String INVOICE_NUMBER = "INV-UAT-0001";

    /** Mock receiver AP URL — MockRestServiceServer will intercept requests to this host */
    private static final String MOCK_RECEIVER_URL = "http://mock-peppol.test/receive";

    /** Minimal valid PDF bytes (header + xref + trailer) */
    private static final byte[] MINIMAL_PDF = (
            "%PDF-1.4\n" +
            "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n" +
            "xref\n0 4\n" +
            "0000000000 65535 f \n" +
            "0000000009 00000 n \n" +
            "0000000058 00000 n \n" +
            "0000000115 00000 n \n" +
            "trailer<</Size 4/Root 1 0 R>>\nstartxref\n190\n%%EOF"
    ).getBytes();

    // ── Spring infrastructure ─────────────────────────────────────────────────

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;
    @Autowired RestTemplate restTemplate;
    @Autowired PeppolDeliveryRecordRepository deliveryRepo;

    private MockMvc mockMvc;
    private MockRestServiceServer mockRestServer;

    @BeforeEach
    void setup() {
        mockMvc = webAppContextSetup(wac).build();
        mockRestServer = MockRestServiceServer.createServer(restTemplate);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
        registry.add("spring.mail.username", () -> "");
        registry.add("spring.mail.password", () -> "");
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enabled", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        registry.add("massmailer.from-address", () -> "noreply@test.com");
        registry.add("massmailer.from-name", () -> "Test Mailer");
        registry.add("massmailer.rate-limit", () -> "5");
        registry.add("massmailer.batch-size", () -> "10");
        registry.add("massmailer.fiscal-validation-enabled", () -> "false");
        registry.add("massmailer.gmail-oauth2.enabled", () -> "false");
    }

    // ── Step 1: Register organisation ────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Step 1 — Register organisation with AS4 delivery mode")
    void registerOrganisation() throws Exception {
        var body = Map.of(
                "name", "UAT Supplier Ltd",
                "slug", "uat-supplier-" + UUID.randomUUID().toString().substring(0, 8),
                "senderEmail", "invoices@uat-supplier.co.zw",
                "senderDisplayName", "UAT Supplier Accounts",
                "vatNumber", "V987654321",
                "deliveryMode", "AS4",
                "user", Map.of(
                        "firstName", "Test",
                        "lastName", "User",
                        "emailAddress", "test@uat-supplier.co.zw"
                )
        );

        var result = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        orgId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
        assertThat(orgId).isNotNull();
    }

    // ── Step 2: Register sender gateway AP (C2) ───────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Step 2 — Register sender gateway AP (C2)")
    void registerSenderGateway() throws Exception {
        var body = Map.of(
                "organizationId", orgId.toString(),
                "participantId", "9915:uat-supplier",
                "participantName", "UAT Supplier Gateway",
                "role", "GATEWAY",
                "endpointUrl", "https://ap.uat-test.internal/as4/receive",
                "simplifiedHttpDelivery", true
        );

        mockMvc.perform(post("/api/v1/eregistry/access-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantId").value("9915:uat-supplier"));
    }

    // ── Step 3: Register mock receiver AP (C3) ───────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Step 3 — Register mock receiver AP (C3) pointing to MockRestServiceServer URL")
    void registerReceiverAccessPoint() throws Exception {
        var body = Map.of(
                "participantId", "0190:ZW987654321",
                "participantName", "Acme Corporation AP",
                "role", "RECEIVER",
                "endpointUrl", MOCK_RECEIVER_URL,
                "simplifiedHttpDelivery", true
        );

        var result = mockMvc.perform(post("/api/v1/eregistry/access-points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpointUrl").value(MOCK_RECEIVER_URL))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        receiverApId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
        assertThat(receiverApId).isNotNull();
    }

    // ── Step 4: Register buyer customer contact ───────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Step 4 — Register buyer customer contact under the org")
    void registerBuyerCustomer() throws Exception {
        var body = Map.of(
                "email", BUYER_EMAIL,
                "name", "Alice Moyo",
                "companyName", "Acme Corporation",
                "vatNumber", "V123456789"
        );

        mockMvc.perform(post("/api/v1/organizations/" + orgId + "/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(BUYER_EMAIL));
    }

    // ── Step 5: Link customer to receiver AP ─────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Step 5 — Link buyer to receiver AP via participant link")
    void createParticipantLink() throws Exception {
        var body = Map.of(
                "organizationId", orgId.toString(),
                "customerEmail", BUYER_EMAIL,
                "participantId", "0190:ZW987654321",
                "receiverAccessPointId", receiverApId.toString(),
                "preferredChannel", "PEPPOL"
        );

        mockMvc.perform(post("/api/v1/eregistry/participant-links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerEmail").value(BUYER_EMAIL))
                .andExpect(jsonPath("$.participantId").value("0190:ZW987654321"));
    }

    // ── Step 6 + 7: Dispatch PDF invoice and verify PEPPOL delivery ───────────

    @Test
    @Order(6)
    @DisplayName("Step 6-10 — Upload invoice PDF, verify PEPPOL dispatch to mock receiver AP")
    void uploadInvoiceAndVerifyPeppolDispatch() throws Exception {

        // ── 6. Arm mock receiver AP to accept the UBL POST ──────────────────
        mockRestServer.expect(requestTo(MOCK_RECEIVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.containsString("application/xml")))
                .andExpect(header("X-Invoice-Number", INVOICE_NUMBER))
                .andRespond(withSuccess("ACK", MediaType.TEXT_PLAIN));

        // ── 7. Build multipart upload request ───────────────────────────────
        var invoiceEntry = Map.ofEntries(
                entry("invoiceNumber", INVOICE_NUMBER),
                entry("recipientEmail", BUYER_EMAIL),
                entry("recipientName", "Alice Moyo"),
                entry("recipientCompany", "Acme Corporation"),
                entry("invoiceDate", "2026-03-01"),
                entry("dueDate", "2026-03-31"),
                entry("totalAmount", 2400.00),
                entry("vatAmount", 360.00),
                entry("currency", "USD"),
                entry("fiscalDeviceSerialNumber", "FD-UAT-001"),
                entry("fiscalDayNumber", "60"),
                entry("globalInvoiceCounter", "10001"),
                entry("verificationCode", "AAAA-BBBB-UAT1")
        );

        var metadata = objectMapper.writeValueAsString(Map.of(
                "campaignName", "UAT March 2026 Invoices",
                "subject", "Your Invoice from UAT Supplier",
                "templateName", "invoice",
                "organizationId", orgId.toString(),
                "invoices", List.of(invoiceEntry)
        ));

        MockMultipartFile metadataPart = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                metadata.getBytes());

        MockMultipartFile pdfPart = new MockMultipartFile(
                INVOICE_NUMBER, INVOICE_NUMBER + ".pdf", "application/pdf",
                MINIMAL_PDF);

        // ── 8. POST dispatch/upload and verify response ──────────────────────
        var result = mockMvc.perform(multipart("/api/v1/erp/dispatch/upload")
                        .file(metadataPart)
                        .file(pdfPart))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.peppolDispatched").value(1))
                .andExpect(jsonPath("$.emailDispatched").value(0))
                .andExpect(jsonPath("$.peppolResults[0].invoiceNumber").value(INVOICE_NUMBER))
                .andExpect(jsonPath("$.peppolResults[0].channel").value("PEPPOL"))
                .andExpect(jsonPath("$.peppolResults[0].status").value("DELIVERED"))
                .andReturn();

        // ── 9. Verify MockRestServiceServer received the UBL XML POST ────────
        mockRestServer.verify();

        // ── 10. Assert PeppolDeliveryRecord persisted with DELIVERED status ──
        var records = deliveryRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        assertThat(records).isNotEmpty();

        var deliveryRecord = records.stream()
                .filter(r -> INVOICE_NUMBER.equals(r.getInvoiceNumber()))
                .findFirst();

        assertThat(deliveryRecord).isPresent();
        assertThat(deliveryRecord.get().getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(deliveryRecord.get().getDeliveredToEndpoint()).isEqualTo(MOCK_RECEIVER_URL);
        assertThat(deliveryRecord.get().getReceiverParticipantId()).isEqualTo("0190:ZW987654321");
    }
}

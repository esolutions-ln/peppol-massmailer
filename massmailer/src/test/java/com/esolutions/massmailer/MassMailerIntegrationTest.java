package com.esolutions.massmailer;

import com.esolutions.massmailer.customer.service.ContactService;
import com.esolutions.massmailer.dto.MailDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MassMailerIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper objectMapper;
    @Autowired ContactService contactService;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        this.mockMvc = webAppContextSetup(wac).build();
        // MockMvc here bypasses Spring Security's filter chain, so the SecurityContext
        // is empty by default. The campaign controller defaults-deny on a null principal,
        // so we install an admin auth context for the lifetime of the test.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "integration-test-admin", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Minimal valid PDF (header + empty body + xref + trailer) for testing */
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

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
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
    }

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/mail/invoice — single invoice email with Base64 PDF attachment")
    void shouldSendSingleInvoiceWithPdfAttachment() throws Exception {
        String pdfBase64 = Base64.getEncoder().encodeToString(MINIMAL_PDF);

        var request = new SingleInvoiceMailRequest(
                "customer@example.com",
                "Acme Corp",
                "Invoice INV-2026-0042",
                "invoice",
                "INV-2026-0042",
                LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 4, 22),
                new BigDecimal("1250.00"),
                new BigDecimal("187.50"),
                "USD",
                "FD-SN-12345",
                "42",
                "0001234",
                "ABCD-EFGH-1234",
                null, // qrCodeUrl
                null, // pdfFilePath
                pdfBase64,
                "INV-2026-0042.pdf",
                Map.of("companyName", "eSolutions", "accountsEmail", "accounts@esolutions.co.zw"),
                null, // customerAccountNumber
                null, // customerTinNumber
                null  // emailTemplateId
        );

        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("delivered"))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-2026-0042"));

        // Verify the email via GreenMail
        MimeMessage[] messages = greenMail.getReceivedMessages();
        assertThat(messages).hasSize(1);

        MimeMessage msg = messages[0];
        assertThat(msg.getSubject()).isEqualTo("Invoice INV-2026-0042");
        assertThat(msg.getFrom()[0].toString()).contains("noreply@test.com");
        assertThat(msg.getHeader("Auto-Submitted")[0]).isEqualTo("auto-generated");
        assertThat(msg.getHeader("X-Invoice-Number")[0]).isEqualTo("INV-2026-0042");

        // Verify multipart: HTML body + PDF attachment
        assertThat(msg.getContent()).isInstanceOf(MimeMultipart.class);
        MimeMultipart multipart = (MimeMultipart) msg.getContent();
        assertThat(multipart.getCount()).isGreaterThanOrEqualTo(2);

        // Find the PDF attachment part
        boolean foundPdf = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if ("application/pdf".equals(part.getContentType())
                    || (part.getFileName() != null && part.getFileName().endsWith(".pdf"))) {
                foundPdf = true;
                assertThat(part.getFileName()).isEqualTo("INV-2026-0042.pdf");
            }
        }
        assertThat(foundPdf).as("Expected PDF attachment in email").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/mail/invoice — single invoice from PDF file on disk")
    void shouldSendInvoiceFromFilePath() throws Exception {
        // Write a test PDF to a temp file
        Path tempPdf = Files.createTempFile("test-invoice-", ".pdf");
        Files.write(tempPdf, MINIMAL_PDF);

        var request = new SingleInvoiceMailRequest(
                "vendor@example.com",
                "Vendor Ltd",
                "Invoice INV-2026-0099",
                "invoice",
                "INV-2026-0099",
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 4, 19),
                new BigDecimal("500.00"),
                new BigDecimal("75.00"),
                "ZWG",
                "FD-SN-99999",
                "10",
                "0005678",
                "WXYZ-5678-ABCD",
                null,
                tempPdf.toAbsolutePath().toString(), // file path
                null, // no Base64
                null, // filename derived from invoice number
                Map.of("companyName", "InvoiceDirect"),
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("delivered"))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-2026-0099"));

        Files.deleteIfExists(tempPdf);
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/v1/campaigns — mass dispatch of invoice PDFs")
    void shouldDispatchInvoiceCampaign() throws Exception {
        String pdfBase64 = Base64.getEncoder().encodeToString(MINIMAL_PDF);

        var request = new CampaignRequest(
                "March 2026 Invoices",
                "Your Invoice from eSolutions",
                "invoice",
                Map.of("companyName", "eSolutions",
                        "accountsEmail", "accounts@esolutions.co.zw",
                        "companyAddress", "123 Samora Machel Ave, Harare"),
                null,
                null,
                List.of(
                        new InvoiceRecipientEntry(
                                "alice@example.com", "Alice Moyo",
                                "INV-2026-0100", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                                new BigDecimal("2400.00"), new BigDecimal("360.00"), "USD",
                                "FD-001", "60", "10001", "AAAA-1111",
                                null, null, pdfBase64, null, null
                        ),
                        new InvoiceRecipientEntry(
                                "bob@example.com", "Bob Chirwa",
                                "INV-2026-0101", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                                new BigDecimal("850.00"), new BigDecimal("127.50"), "USD",
                                "FD-001", "60", "10002", "BBBB-2222",
                                null, null, pdfBase64, null, null
                        ),
                        new InvoiceRecipientEntry(
                                "carol@example.com", "Carol Ndlovu",
                                "INV-2026-0102", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                                new BigDecimal("3100.50"), new BigDecimal("465.08"), "ZWG",
                                "FD-002", "60", "10003", "CCCC-3333",
                                null, null, pdfBase64, null, null
                        )
                )
        );

        var result = mockMvc.perform(post("/api/v1/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientCount").value(3))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String campaignId = objectMapper.readTree(body).get("campaignId").asText();

        // Wait for async dispatch. The await polling lambda runs on a worker thread that
        // does NOT inherit the test's ThreadLocal SecurityContext — install admin auth here too.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "integration-test-admin", null,
                            java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            try {
                String statusBody = mockMvc.perform(get("/api/v1/campaigns/" + campaignId))
                        .andReturn().getResponse().getContentAsString();
                var statusNode = objectMapper.readTree(statusBody);
                assertThat(statusNode.get("status").asText()).isIn("COMPLETED", "PARTIALLY_FAILED");
                assertThat(statusNode.get("sent").asInt()).isEqualTo(3);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    @Order(4)
    @DisplayName("POST /api/v1/mail/invoice — rejects invalid Base64 PDF")
    void shouldRejectInvalidPdf() throws Exception {
        var request = new SingleInvoiceMailRequest(
                "bad@example.com", "Test",
                "Bad Invoice", "invoice",
                "INV-BAD", null, null,
                BigDecimal.TEN, null, "USD",
                null, null, null, null, null,
                null,
                Base64.getEncoder().encodeToString("NOT-A-PDF".getBytes()),
                null,
                null,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PDF")));
    }

    @Test
    @Order(6)
    @DisplayName("F-07 — Sibling contacts of the same customer are CC'd on invoice sends")
    void shouldCcSiblingContactsOnInvoiceSend() throws Exception {
        // Seed two contacts under the same customerId — A is the recipient, B is the sibling.
        java.util.UUID customerId = java.util.UUID.randomUUID();
        contactService.upsert(customerId, "alice.cc@example.com", "Alice CC", null);
        contactService.upsert(customerId, "bob.cc@example.com", "Bob CC", null);

        int receivedBefore = greenMail.getReceivedMessages().length;
        String pdfBase64 = Base64.getEncoder().encodeToString(MINIMAL_PDF);

        var request = new SingleInvoiceMailRequest(
                "alice.cc@example.com",
                "Alice CC",
                "Invoice INV-CC-2026-0001",
                "invoice",
                "INV-CC-2026-0001",
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                new BigDecimal("100.00"),
                new BigDecimal("15.00"),
                "USD",
                "FD-CC-001", "1", "0000001", "CCDD-1111",
                null, null, pdfBase64, "INV-CC-2026-0001.pdf",
                Map.of("companyName", "eSolutions"),
                null, null, null
        );

        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("delivered"));

        // GreenMail delivers one MimeMessage per envelope recipient — To + Cc both receive
        // a copy. We expect two new deliveries: one for Alice (To) and one for Bob (Cc).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages().length - receivedBefore)
                        .as("Expected GreenMail to receive both To and Cc deliveries")
                        .isGreaterThanOrEqualTo(2));

        MimeMessage[] all = greenMail.getReceivedMessages();
        MimeMessage ccMsg = null;
        for (int i = all.length - 1; i >= receivedBefore; i--) {
            if ("Invoice INV-CC-2026-0001".equals(all[i].getSubject())) {
                ccMsg = all[i];
                break;
            }
        }
        assertThat(ccMsg).as("Could not find the CC test message in GreenMail").isNotNull();
        assertThat(ccMsg.getHeader("Cc")).isNotNull();
        assertThat(ccMsg.getHeader("Cc")[0]).contains("bob.cc@example.com");
        assertThat(ccMsg.getHeader("To")[0]).contains("alice.cc@example.com");
    }

    @Test
    @Order(7)
    @DisplayName("F-07 — Single-contact customer produces no Cc header")
    void shouldNotCcWhenCustomerHasSingleContact() throws Exception {
        java.util.UUID customerId = java.util.UUID.randomUUID();
        contactService.upsert(customerId, "solo@example.com", "Solo Buyer", null);

        int receivedBefore = greenMail.getReceivedMessages().length;
        String pdfBase64 = Base64.getEncoder().encodeToString(MINIMAL_PDF);

        var request = new SingleInvoiceMailRequest(
                "solo@example.com", "Solo Buyer",
                "Invoice INV-SOLO-2026-0001", "invoice",
                "INV-SOLO-2026-0001",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("50.00"), new BigDecimal("7.50"), "USD",
                "FD-SOLO-1", "1", "0000002", "SOLO-2222",
                null, null, pdfBase64, "INV-SOLO-2026-0001.pdf",
                Map.of("companyName", "eSolutions"),
                null, null, null
        );

        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages().length).isGreaterThan(receivedBefore));

        MimeMessage[] all = greenMail.getReceivedMessages();
        MimeMessage msg = null;
        for (int i = all.length - 1; i >= receivedBefore; i--) {
            if ("Invoice INV-SOLO-2026-0001".equals(all[i].getSubject())) {
                msg = all[i];
                break;
            }
        }
        assertThat(msg).isNotNull();
        assertThat(msg.getHeader("Cc")).as("Single-contact customer should have no Cc header").isNull();
    }

    @Test
    @Order(5)
    @DisplayName("Validation rejects missing invoice number")
    void shouldRejectMissingInvoiceNumber() throws Exception {
        mockMvc.perform(post("/api/v1/mail/invoice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "to": "test@test.com",
                                    "subject": "Test",
                                    "templateName": "invoice"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}

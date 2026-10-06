package com.esolutions.massmailer.brevo;

import com.esolutions.massmailer.config.MailerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZonedDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test that sends ONE real email through the Brevo API using the
 * same {@link BrevoEmailClient} code path as production. Skipped unless
 * {@code BREVO_LIVE_TEST_TO} is set, so {@code mvn test} never sends mail.
 *
 * <pre>
 * BREVO_API_KEY=xkeysib-...            # or source massmailer/.env
 * BREVO_LIVE_TEST_FROM=help@esolutions.co.zw        # must be a verified Brevo sender
 * BREVO_LIVE_TEST_REPLY_TO=help@esolutions.co.zw    # optional, defaults to FROM
 * BREVO_LIVE_TEST_TO=you@example.com
 * BREVO_LIVE_TEST_TO_NAME="Your Name"               # optional
 * mvn test -Dtest=BrevoLiveSendTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BREVO_LIVE_TEST_TO", matches = ".+")
class BrevoLiveSendTest {

    // Minimal one-page PDF so the attachment path is exercised too.
    private static final String PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
            """;

    @Test
    void sendsOneRealEmailViaBrevo() throws Exception {
        String apiKey = env("BREVO_API_KEY", null);
        String from = env("BREVO_LIVE_TEST_FROM", null);
        assertThat(apiKey).as("BREVO_API_KEY").isNotBlank();
        assertThat(from).as("BREVO_LIVE_TEST_FROM").isNotBlank();
        String replyTo = env("BREVO_LIVE_TEST_REPLY_TO", from);
        String to = env("BREVO_LIVE_TEST_TO", null);
        String toName = env("BREVO_LIVE_TEST_TO_NAME", null);

        var props = new MailerProperties(from, "InvoiceDirect", 0, 0, 0, 0, false, null, 0,
                new MailerProperties.Brevo(true, apiKey, env("BREVO_BASE_URL", null), 30));
        var client = new BrevoEmailClient(props, new ObjectMapper());

        String sentAt = ZonedDateTime.now().toString();
        var request = BrevoEmailClient.SendRequest.builder()
                .sender(from, "InvoiceDirect")
                .to(to, toName)
                .replyTo(replyTo)
                .subject("InvoiceDirect Brevo API test — " + sentAt)
                .html("""
                        <p>Hello%s,</p>
                        <p>This is a test email from InvoiceDirect, sent via the Brevo
                        transactional API (<code>POST /v3/smtp/email</code>).</p>
                        <ul>
                          <li>From: %s</li>
                          <li>Reply-To: %s</li>
                          <li>Sent: %s</li>
                        </ul>
                        <p>A sample PDF is attached. Hit reply to check the Reply-To address.</p>
                        """.formatted(toName != null ? " " + toName : "", from, replyTo, sentAt))
                .attachment(new BrevoEmailClient.Attachment(
                        Base64.getEncoder().encodeToString(PDF.getBytes()), "brevo-test.pdf"))
                .header("X-Invoice-Number", "BREVO-LIVE-TEST")
                .tag("live-test")
                .build();

        String messageId = client.sendTransactional(request);

        System.out.println("Brevo accepted live test email: messageId=" + messageId);
        assertThat(messageId).isNotBlank();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}

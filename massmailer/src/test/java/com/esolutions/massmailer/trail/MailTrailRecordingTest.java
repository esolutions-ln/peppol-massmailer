package com.esolutions.massmailer.trail;

import com.esolutions.massmailer.brevo.BrevoEmailClient;
import com.esolutions.massmailer.brevo.BrevoSenderResolver;
import com.esolutions.massmailer.brevo.BrevoSenderResolver.Sender;
import com.esolutions.massmailer.config.MailerProperties;
import com.esolutions.massmailer.customer.service.ContactService;
import com.esolutions.massmailer.model.DeliveryResult;
import com.esolutions.massmailer.service.SmtpSendService;
import com.esolutions.massmailer.trail.MailSendAttempt.Outcome;
import com.esolutions.massmailer.trail.MailSendAttemptRepository.Filter;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.ConnectException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end over the real repository (H2): SmtpSendService → MailTrailRecorder → mail_send_attempts.
 */
class MailTrailRecordingTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String EGRESS = "203.0.113.7";

    private MailSendAttemptRepository repo;
    private MailTrailRecorder recorder;
    private BrevoSenderResolver senderResolver;
    private ContactService contacts;
    private ObjectProvider<BrevoEmailClient> noBrevo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:trail-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        repo = new MailSendAttemptRepository(new NamedParameterJdbcTemplate(ds));
        repo.afterPropertiesSet();
        repo.afterPropertiesSet(); // schema creation is idempotent

        EgressIpResolver ips = mock(EgressIpResolver.class);
        when(ips.current()).thenReturn(EGRESS);
        when(ips.fresh()).thenReturn(EGRESS);
        when(ips.localAddressFor(anyString(), any())).thenReturn("172.18.0.5");

        recorder = new MailTrailRecorder(repo, ips, new DataSourceTransactionManager(ds), true);

        senderResolver = mock(BrevoSenderResolver.class);
        when(senderResolver.resolve(any(), any()))
                .thenReturn(new Sender("billing@acme.co.zw", "Acme", "billing@acme.co.zw", ORG));
        contacts = mock(ContactService.class);
        when(contacts.findByEmail(anyString())).thenReturn(Optional.empty());
        noBrevo = mock(ObjectProvider.class);
    }

    private SmtpSendService service(JavaMailSenderImpl sender) {
        sender.setHost("smtp.office365.com");
        sender.setPort(587);
        return new SmtpSendService(sender, new MailerProperties(
                "noreply@x.co", "X", 10, 10, 3, 2000, false, "", 0, null),
                new Semaphore(10), senderResolver, noBrevo, contacts, recorder);
    }

    @Test
    void unauthorizedIpIsRecordedWithEgressIpAndSurfacedToCaller() {
        var svc = service(new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage... messages) {
                throw new MailAuthenticationException(
                        new AuthenticationFailedException("525 5.7.1 Unauthorized IP address\n"));
            }
        });

        DeliveryResult result = svc.sendWithFallback("buyer@client.co.zw", "Buyer", "Invoice INV-1",
                "<p>hi</p>", "INV-1", null, null, null,
                SendContext.of(SendContext.Source.SINGLE_API));

        assertThat(result).isInstanceOf(DeliveryResult.Failed.class);
        var failed = (DeliveryResult.Failed) result;
        assertThat(failed.retryable()).isFalse();
        assertThat(failed.errorMessage())
                .contains("525 5.7.1 Unauthorized IP address")
                .contains("sending IP " + EGRESS)
                .contains("trail ");

        var rows = repo.search(new Filter(ORG, null, null, null, null, null, null, null, null, null), 0, 10);
        assertThat(rows).hasSize(1);
        MailSendAttempt a = rows.getFirst();
        assertThat(a.outcome()).isEqualTo(Outcome.IP_DENIED);
        assertThat(a.ipDenied()).isTrue();
        assertThat(a.responseCode()).isEqualTo(525);
        assertThat(a.enhancedStatus()).isEqualTo("5.7.1");
        assertThat(a.egressIp()).isEqualTo(EGRESS);
        assertThat(a.localIp()).isEqualTo("172.18.0.5");
        assertThat(a.mailHost()).isEqualTo("smtp.office365.com");
        assertThat(a.mailPort()).isEqualTo(587);
        assertThat(a.transport()).isEqualTo(MailSendAttempt.Transport.SMTP);
        assertThat(a.source()).isEqualTo(SendContext.Source.SINGLE_API);
        assertThat(a.senderEmail()).isEqualTo("billing@acme.co.zw");
        assertThat(a.invoiceNumber()).isEqualTo("INV-1");
        assertThat(failed.errorMessage()).contains(a.id().toString());

        var denied = repo.deniedIpSummary(Instant.now().minusSeconds(60));
        assertThat(denied).hasSize(1);
        assertThat(denied.getFirst().egressIp()).isEqualTo(EGRESS);
        assertThat(denied.getFirst().mailHost()).isEqualTo("smtp.office365.com");
        assertThat(denied.getFirst().deniedAttempts()).isEqualTo(1);
        assertThat(denied.getFirst().organizationsAffected()).isEqualTo(1);
    }

    @Test
    void connectionFailureIsRecordedAsRetryableFailure() {
        var svc = service(new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage... messages) {
                throw new MailSendException("Mail server connection failed",
                        new ConnectException("Connection refused"));
            }
        });

        DeliveryResult result = svc.sendWithFallback("buyer@client.co.zw", "Buyer", "s", "<p/>", "INV-2", null);

        assertThat(result).isInstanceOf(DeliveryResult.Failed.class);
        assertThat(((DeliveryResult.Failed) result).retryable()).isTrue();

        MailSendAttempt a = repo.search(new Filter(null, null, "BUYER@client.co.zw", "INV-2",
                null, null, null, null, null, null), 0, 10).getFirst();
        assertThat(a.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(a.ipDenied()).isFalse();
        assertThat(a.retryable()).isTrue();
        assertThat(a.source()).isEqualTo(SendContext.Source.UNSPECIFIED);
        assertThat(a.organizationId()).isEqualTo(ORG);
        assertThat(repo.deniedIpSummary(null)).isEmpty();
    }

    @Test
    void deliveredSendIsRecordedWithMessageId() {
        var svc = service(new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage... messages) {
                // accepted
            }
        });
        UUID campaign = UUID.randomUUID();
        UUID campaignOrg = UUID.randomUUID();

        DeliveryResult result = svc.sendWithFallback("buyer@client.co.zw", "Buyer", "s", "<p/>", "INV-3",
                null, "ACC-1", null, new SendContext(SendContext.Source.CAMPAIGN, campaignOrg, campaign));

        assertThat(result).isInstanceOf(DeliveryResult.Delivered.class);
        var page = repo.search(new Filter(campaignOrg, campaign, null, null, Outcome.DELIVERED,
                SendContext.Source.CAMPAIGN, false, null, null, null), 0, 10);
        assertThat(page).hasSize(1);
        assertThat(page.getFirst().egressIp()).isEqualTo(EGRESS);
        assertThat(page.getFirst().attemptNumber()).isEqualTo(1);
        assertThat(repo.findById(page.getFirst().id())).isPresent();
        assertThat(repo.count(new Filter(null, null, null, null, null, null, null, null, null, null))).isEqualTo(1);
    }
}

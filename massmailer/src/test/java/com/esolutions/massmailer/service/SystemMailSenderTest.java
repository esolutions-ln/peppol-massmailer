package com.esolutions.massmailer.service;

import com.esolutions.massmailer.brevo.BrevoEmailClient;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemMailSenderTest {

    @Test
    void usesBrevoApiWhenEnabled() throws Exception {
        JavaMailSender smtp = mock(JavaMailSender.class);
        BrevoEmailClient brevo = mock(BrevoEmailClient.class);
        when(brevo.sendTransactional(any())).thenReturn("<msg@brevo>");

        new SystemMailSender(smtp, brevo).sendHtml(
                "no-reply@platform.test", "Platform", "admin@acme.test", "Ada",
                "Subject", "<p>x</p>", Map.of("Auto-Submitted", "auto-generated"));

        var captor = ArgumentCaptor.forClass(BrevoEmailClient.SendRequest.class);
        verify(brevo).sendTransactional(captor.capture());
        var req = captor.getValue();
        assertThat(req.sender().email()).isEqualTo("no-reply@platform.test");
        assertThat(req.to().get(0).email()).isEqualTo("admin@acme.test");
        assertThat(req.htmlContent()).isEqualTo("<p>x</p>");
        assertThat(req.headers()).containsEntry("Auto-Submitted", "auto-generated");
        verifyNoInteractions(smtp);
    }

    @Test
    void fallsBackToSmtpWhenBrevoDisabled() throws Exception {
        JavaMailSender smtp = mock(JavaMailSender.class);
        when(smtp.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        new SystemMailSender(smtp, (BrevoEmailClient) null).sendHtml(
                "no-reply@platform.test", null, "admin@acme.test", null,
                "Subject", "<p>x</p>", null);

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(captor.capture());
        assertThat(captor.getValue().getFrom()[0].toString()).isEqualTo("no-reply@platform.test");
        assertThat(captor.getValue().getSubject()).isEqualTo("Subject");
    }
}

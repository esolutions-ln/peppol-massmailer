package com.esolutions.massmailer.trail;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class SmtpFailureClassifierTest {

    @Test
    void parsesUnauthorizedIpSmtpReply() {
        var e = new MailAuthenticationException(
                new AuthenticationFailedException("525 5.7.1 Unauthorized IP address\n"));
        String text = SmtpFailureClassifier.chainText(e);

        assertThat(SmtpFailureClassifier.isIpDenied(text)).isTrue();
        assertThat(SmtpFailureClassifier.responseCode(text)).isEqualTo(525);
        assertThat(SmtpFailureClassifier.enhancedStatus(text)).isEqualTo("5.7.1");
        assertThat(SmtpFailureClassifier.reportedIp(text)).isNull();
    }

    @Test
    void extractsIpQuotedByProvider() {
        String brevo = "Brevo send failed: Brevo /smtp/email failed: 401 "
                + "{\"code\":\"unauthorized\",\"message\":\"We have detected you are using an unrecognised IP address 41.57.99.12.\"}";

        assertThat(SmtpFailureClassifier.isIpDenied(brevo)).isTrue();
        assertThat(SmtpFailureClassifier.responseCode(brevo)).isEqualTo(401);
        assertThat(SmtpFailureClassifier.reportedIp(brevo)).isEqualTo("41.57.99.12");
    }

    @Test
    void recognisesClientHostBlocked() {
        String text = "554 5.7.1 Service unavailable; Client host [196.4.80.3] blocked using zen.spamhaus.org";
        assertThat(SmtpFailureClassifier.isIpDenied(text)).isTrue();
        assertThat(SmtpFailureClassifier.responseCode(text)).isEqualTo(554);
        assertThat(SmtpFailureClassifier.reportedIp(text)).isEqualTo("196.4.80.3");
    }

    @Test
    void badCredentialsAreNotIpDenials() {
        String text = "535 5.7.8 Username and Password not accepted";
        assertThat(SmtpFailureClassifier.isIpDenied(text)).isFalse();
        assertThat(SmtpFailureClassifier.responseCode(text)).isEqualTo(535);
        assertThat(SmtpFailureClassifier.enhancedStatus(text)).isEqualTo("5.7.8");
    }

    @Test
    void enhancedStatusIsNotMistakenForAnIp() {
        assertThat(SmtpFailureClassifier.reportedIp("451 4.4.2 Timeout waiting for data")).isNull();
    }

    @Test
    void validatesLookupResponses() {
        assertThat(SmtpFailureClassifier.looksLikeIp("102.134.8.1")).isTrue();
        assertThat(SmtpFailureClassifier.looksLikeIp("2c0f:f4c0::1")).isTrue();
        assertThat(SmtpFailureClassifier.looksLikeIp("<html>blocked</html>")).isFalse();
        assertThat(SmtpFailureClassifier.looksLikeIp("")).isFalse();
    }
}

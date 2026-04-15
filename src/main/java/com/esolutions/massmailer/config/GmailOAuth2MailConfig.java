package com.esolutions.massmailer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * Configures JavaMailSender to use Gmail SMTP with OAuth2 (XOAUTH2).
 *
 * The OAuth2 token is NOT fetched at startup — it is injected lazily
 * before each send via GmailOAuth2TokenProvider, so a missing or
 * invalid key file does not prevent the application from starting.
 *
 * This configuration is only active when {@code massmailer.gmail-oauth2.enabled=true}.
 * When disabled (default), Spring Boot's auto-configured JavaMailSender is used instead,
 * which respects the standard {@code spring.mail.*} properties.
 */
@Configuration
@ConditionalOnProperty(name = "massmailer.gmail-oauth2.enabled", havingValue = "true")
public class GmailOAuth2MailConfig {

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String smtpHost;

    @Value("${spring.mail.port:587}")
    private int smtpPort;

    private final GmailOAuth2TokenProvider tokenProvider;

    public GmailOAuth2MailConfig(GmailOAuth2TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Bean
    public JavaMailSender javaMailSender() {
        // Use a subclass that refreshes the OAuth2 token before each send
        var mailSender = new JavaMailSenderImpl() {
            @Override
            public void testConnection() {
                // skip connection test — OAuth2 can't be tested via basic ping
            }

            @Override
            protected void doSend(jakarta.mail.internet.MimeMessage[] mimeMessages,
                                  Object[] originalMessages) {
                // Refresh token before every send batch
                setPassword(tokenProvider.getAccessToken());
                super.doSend(mimeMessages, originalMessages);
            }
        };

        mailSender.setHost(smtpHost);
        mailSender.setPort(smtpPort);
        mailSender.setUsername(senderEmail);
        mailSender.setPassword("placeholder"); // replaced before each send

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
        props.put("mail.debug", "false");

        return mailSender;
    }
}

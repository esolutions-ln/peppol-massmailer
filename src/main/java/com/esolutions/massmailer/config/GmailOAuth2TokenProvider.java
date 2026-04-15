package com.esolutions.massmailer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.auth.oauth2.UserCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Provides a fresh OAuth2 access token for Gmail SMTP XOAUTH2 authentication.
 *
 * Supports two credential modes (in priority order):
 *
 * 1. Web OAuth2 client credentials (google.oauth2.credentials-path + google.oauth2.refresh-token)
 *    — Use this when authenticating as a real user/mailbox via the OAuth consent screen.
 *    — Obtain a refresh token once via OAuth Playground: https://developers.google.com/oauthplayground
 *      (scope: https://mail.google.com/)
 *    — Set GOOGLE_OAUTH2_REFRESH_TOKEN env var with the obtained refresh token.
 *
 * 2. Service account with domain-wide delegation (google.service-account.key-path)
 *    — Use this for Google Workspace with a service account impersonating the sender.
 *
 * 3. Application Default Credentials (fallback — useful in GCP/Cloud Run)
 */
@Component
public class GmailOAuth2TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuth2TokenProvider.class);
    private static final String GMAIL_SCOPE = "https://mail.google.com/";

    @Value("${spring.mail.username}")
    private String impersonatedEmail;

    @Value("${google.service-account.key-path:}")
    private String serviceAccountKeyPath;

    @Value("${google.oauth2.credentials-path:google-oauth-credentials.json}")
    private String oauth2CredentialsPath;

    @Value("${google.oauth2.refresh-token:}")
    private String refreshToken;

    private GoogleCredentials credentials;

    /**
     * Returns a valid OAuth2 access token, refreshing if expired.
     */
    public String getAccessToken() {
        try {
            if (credentials == null) {
                credentials = buildCredentials();
            }
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException e) {
            log.error("Failed to obtain Gmail OAuth2 token for {}: {}", impersonatedEmail, e.getMessage());
            throw new RuntimeException("Gmail OAuth2 token error", e);
        }
    }

    private GoogleCredentials buildCredentials() throws IOException {
        // Mode 1: Web OAuth2 client credentials + refresh token
        if (refreshToken != null && !refreshToken.isBlank()) {
            return buildUserCredentials();
        }

        // Mode 2: Service account with domain-wide delegation
        if (serviceAccountKeyPath != null && !serviceAccountKeyPath.isBlank()) {
            log.info("Loading Gmail service account credentials from: {}", serviceAccountKeyPath);
            try (var stream = new FileInputStream(serviceAccountKeyPath)) {
                return ServiceAccountCredentials
                        .fromStream(stream)
                        .createScoped(List.of(GMAIL_SCOPE))
                        .createDelegated(impersonatedEmail);
            }
        }

        // Mode 3: Application Default Credentials (GCP/Cloud Run)
        log.info("No explicit credentials configured — using Application Default Credentials");
        return GoogleCredentials.getApplicationDefault().createScoped(List.of(GMAIL_SCOPE));
    }

    private UserCredentials buildUserCredentials() throws IOException {
        Path credPath = Path.of(oauth2CredentialsPath);
        if (!Files.exists(credPath)) {
            throw new IOException("OAuth2 credentials file not found: " + oauth2CredentialsPath);
        }

        JsonNode root = new ObjectMapper().readTree(credPath.toFile());
        JsonNode web = root.has("web") ? root.get("web") : root;

        String clientId = web.get("client_id").asText();
        String clientSecret = web.get("client_secret").asText();

        log.info("Building Gmail OAuth2 UserCredentials for: {} (client_id: {})", impersonatedEmail, clientId);

        return UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();
    }
}

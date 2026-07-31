package com.esolutions.massmailer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Provides OAuth2 access tokens for Gmail SMTP (XOAUTH2).
 *
 * <p>This bean is only registered when {@code massmailer.gmail-oauth2.enabled=true}.
 * On startup it validates that the required Google OAuth2 credentials are present —
 * if not, application boot fails fast with a clear error rather than producing a
 * non-functional component that throws at send time.
 *
 * <p>The actual refresh-token → access-token exchange against Google's OAuth2 token
 * endpoint is not yet wired up. Until it is, enabling this feature without a working
 * implementation surfaces an explicit {@link UnsupportedOperationException} at first
 * send so the gap is impossible to miss in production.
 */
@Component
@ConditionalOnProperty(name = "massmailer.gmail-oauth2.enabled", havingValue = "true")
public class GmailOAuth2TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailOAuth2TokenProvider.class);

    private final String refreshToken;
    private final String clientId;
    private final String clientSecret;

    public GmailOAuth2TokenProvider(
            @Value("${google.oauth2.refresh-token:}") String refreshToken,
            @Value("${google.oauth2.client-id:}") String clientId,
            @Value("${google.oauth2.client-secret:}") String clientSecret) {
        this.refreshToken = refreshToken;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @PostConstruct
    void validateConfig() {
        if (refreshToken == null || refreshToken.isBlank()
                || clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Gmail OAuth2 is enabled (massmailer.gmail-oauth2.enabled=true) but credentials are missing. " +
                    "Set google.oauth2.refresh-token, google.oauth2.client-id and google.oauth2.client-secret, " +
                    "or disable the feature.");
        }
        log.info("Gmail OAuth2 token provider initialised (refresh-token redacted)");
    }

    /**
     * Returns a fresh OAuth2 access token. The exchange against Google's OAuth2 token
     * endpoint is intentionally not implemented in this build — callers must wire up
     * a real client (e.g. google-api-client) before relying on this method.
     */
    public String getAccessToken() {
        throw new UnsupportedOperationException(
                "Gmail OAuth2 access-token exchange is not implemented. " +
                "Either disable massmailer.gmail-oauth2.enabled or supply a complete implementation " +
                "that exchanges the refresh token at https://oauth2.googleapis.com/token.");
    }
}

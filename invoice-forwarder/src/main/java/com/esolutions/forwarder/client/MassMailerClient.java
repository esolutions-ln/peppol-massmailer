package com.esolutions.forwarder.client;

import com.esolutions.forwarder.config.ForwarderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thin REST client over the remote Mass Mailer. Handles session token caching,
 * automatic re-login on 401, and the small set of endpoints the forwarder needs.
 */
@Component
public class MassMailerClient {

    private static final Logger log = LoggerFactory.getLogger(MassMailerClient.class);
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final ForwarderProperties props;
    private final RestClient http;
    private final ReentrantLock loginLock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant tokenAcquiredAt = Instant.EPOCH;

    public MassMailerClient(ForwarderProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.baseUrl(props.mailer().url()).build();
    }

    // ── Auth ──

    private String token() {
        String t = cachedToken;
        if (t != null && Duration.between(tokenAcquiredAt, Instant.now()).compareTo(TOKEN_TTL) < 0) {
            return t;
        }
        return refreshToken();
    }

    private String refreshToken() {
        loginLock.lock();
        try {
            // Re-check inside the lock — another thread may have refreshed already.
            if (cachedToken != null
                    && Duration.between(tokenAcquiredAt, Instant.now()).compareTo(TOKEN_TTL) < 0) {
                return cachedToken;
            }
            var resp = http.post()
                    .uri("/api/v1/admin/login")
                    .body(new MailerDtos.LoginRequest(
                            props.mailer().adminUser(),
                            props.mailer().adminPass()))
                    .retrieve()
                    .body(MailerDtos.LoginResponse.class);
            if (resp == null || resp.token() == null) {
                throw new IllegalStateException("Login returned no token");
            }
            cachedToken = resp.token();
            tokenAcquiredAt = Instant.now();
            log.info("Acquired admin session token for {}", props.mailer().adminUser());
            return cachedToken;
        } finally {
            loginLock.unlock();
        }
    }

    private <T> T authed(java.util.function.Function<String, T> call) {
        try {
            return call.apply(token());
        } catch (HttpClientErrorException.Unauthorized e) {
            log.info("Got 401 — refreshing session token and retrying");
            cachedToken = null;
            return call.apply(token());
        }
    }

    // ── Endpoints ──

    public Optional<MailerDtos.CustomerResponse> findCustomerByTaxId(UUID orgId,
                                                                     String bpn,
                                                                     String vatNumber,
                                                                     String tinNumber) {
        return authed(tok -> {
            try {
                var resp = http.get()
                        .uri(uri -> uri.path("/api/v1/organizations/{orgId}/customers/by-tax-id")
                                .queryParamIfPresent("bpn", Optional.ofNullable(blankToNull(bpn)))
                                .queryParamIfPresent("vatNumber", Optional.ofNullable(blankToNull(vatNumber)))
                                .queryParamIfPresent("tinNumber", Optional.ofNullable(blankToNull(tinNumber)))
                                .build(orgId))
                        .header("X-API-Key", tok)
                        .retrieve()
                        .body(MailerDtos.CustomerResponse.class);
                return Optional.ofNullable(resp);
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.<MailerDtos.CustomerResponse>empty();
            }
        });
    }

    public MailerDtos.CustomerResponse registerCustomer(UUID orgId,
                                                        MailerDtos.RegisterCustomerRequest req) {
        return authed(tok -> http.post()
                .uri("/api/v1/organizations/{orgId}/customers", orgId)
                .header("X-API-Key", tok)
                .body(req)
                .retrieve()
                .body(MailerDtos.CustomerResponse.class));
    }

    public MailerDtos.CampaignCreatedResponse createCampaign(MailerDtos.CampaignRequest req) {
        return authed(tok -> http.post()
                .uri("/api/v1/campaigns")
                .header("X-API-Key", tok)
                .body(req)
                .retrieve()
                .body(MailerDtos.CampaignCreatedResponse.class));
    }

    public MailerDtos.CampaignResponse getCampaign(UUID campaignId) {
        return authed(tok -> http.get()
                .uri("/api/v1/campaigns/{id}", campaignId)
                .header("X-API-Key", tok)
                .retrieve()
                .body(MailerDtos.CampaignResponse.class));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // exposed for diagnostics
    public URI baseUri() { return URI.create(props.mailer().url()); }
}

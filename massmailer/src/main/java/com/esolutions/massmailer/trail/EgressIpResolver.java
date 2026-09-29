package com.esolutions.massmailer.trail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Determines the IP address outbound mail leaves from.
 *
 * Behind NAT (Docker, cloud VPC) the socket's local address is a private IP, while the
 * mail provider sees the public egress IP — which is the one that must be allow-listed.
 * The public IP is obtained from a plain-text "what is my IP" endpoint
 * ({@code massmailer.trail.egress-ip-lookup-url}, default https://api.ipify.org) and cached.
 * Set the URL to blank to disable the lookup (e.g. air-gapped hosts, tests).
 */
@Component
public class EgressIpResolver {

    private static final Logger log = LoggerFactory.getLogger(EgressIpResolver.class);

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(3);

    private final String lookupUrl;
    private final Duration cacheTtl;
    private final HttpClient http;

    private volatile Cached cached;
    private final Map<String, String> localIpByHost = new ConcurrentHashMap<>();

    private record Cached(String ip, Instant at) {}

    public EgressIpResolver(
            @Value("${massmailer.trail.egress-ip-lookup-url:https://api.ipify.org}") String lookupUrl,
            @Value("${massmailer.trail.egress-ip-cache-seconds:600}") long cacheSeconds) {
        this.lookupUrl = lookupUrl == null ? "" : lookupUrl.strip();
        this.cacheTtl = Duration.ofSeconds(Math.max(cacheSeconds, 0));
        this.http = HttpClient.newBuilder()
                .connectTimeout(LOOKUP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Warm the cache off the request path so the first send doesn't pay for the lookup. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (lookupUrl.isEmpty()) return;
        Thread.ofVirtual().name("egress-ip-warmup").start(() -> {
            String ip = fresh();
            if (ip != null) log.info("Outbound mail egress IP: {}", ip);
        });
    }

    /** Cached public egress IP; performs a lookup when the cache is empty or stale. May return null. */
    public String current() {
        Cached c = cached;
        if (c != null && c.at().plus(cacheTtl).isAfter(Instant.now())) {
            return c.ip();
        }
        return fresh();
    }

    /**
     * Bypasses the cache. Used when the provider rejects us by IP, so the trail records the
     * address actually in use at that moment (NAT pools can rotate).
     * Failed lookups are cached too, so an unreachable lookup endpoint costs at most one
     * timeout per TTL window. May return null.
     */
    public String fresh() {
        if (lookupUrl.isEmpty()) return null;
        String ip = null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(lookupUrl))
                    .timeout(LOOKUP_TIMEOUT)
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body().strip();
            if (resp.statusCode() / 100 == 2 && SmtpFailureClassifier.looksLikeIp(body)) {
                ip = body;
            } else {
                log.warn("Egress IP lookup at {} returned {} '{}'", lookupUrl, resp.statusCode(),
                        body.length() > 80 ? body.substring(0, 80) : body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Egress IP lookup at {} failed: {}", lookupUrl, e.toString());
        }
        cached = new Cached(ip, Instant.now());
        return ip;
    }

    /**
     * Local interface address the OS would use to reach {@code host}. Uses a connected UDP
     * socket, which selects a route without sending any packets. Cached per host.
     */
    public String localAddressFor(String host, Integer port) {
        if (host == null || host.isBlank()) return null;
        String key = host + ":" + (port == null ? 0 : port);
        String hit = localIpByHost.get(key);
        if (hit != null) return hit;
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName(host), port == null || port <= 0 ? 25 : port));
            InetAddress local = s.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) return null;
            String ip = local.getHostAddress();
            localIpByHost.put(key, ip);
            return ip;
        } catch (Exception e) {
            log.debug("Could not determine local address for {}: {}", key, e.toString());
            return null;
        }
    }
}

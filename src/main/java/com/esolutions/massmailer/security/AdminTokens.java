package com.esolutions.massmailer.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Helper for admin session token cryptography.
 *
 * <p>Tokens are generated as 32 bytes of {@link SecureRandom} output (256 bits of entropy),
 * hex-encoded for transport. Only the SHA-256 hash of a token is persisted — the plaintext
 * lives only in the caller's memory and the response body returned to the client.
 *
 * <p>A database leak therefore exposes hashes, not usable credentials.
 */
public final class AdminTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private AdminTokens() {}

    /** Generates a fresh 256-bit token, hex-encoded — return this to the client and discard. */
    public static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 hash of a raw token, hex-encoded — what gets persisted and looked up. */
    public static String hashToken(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("rawToken must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

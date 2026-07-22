package com.taskforge.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * TokenHashUtil — utility for hashing refresh tokens and API keys.
 *
 * <p><b>Why SHA-256 and not BCrypt?</b>
 * BCrypt is intentionally slow (configurable work factor) to resist brute-force attacks
 * on dictionary-based passwords. Refresh tokens and API keys are high-entropy random
 * values (128+ bits) — there is no dictionary to attack. Using SHA-256 here is both
 * correct and efficient.
 *
 * <p><b>No salt needed:</b>
 * BCrypt adds a random salt because passwords are low-entropy and shared across users.
 * A randomly-generated token is already unique and unpredictable — salting adds no
 * security benefit.
 *
 * <p>This is a stateless utility class — all methods are static, no Spring bean needed.
 */
public final class TokenHashUtil {

    private TokenHashUtil() {
        // Utility class — not instantiable
    }

    /**
     * Computes the SHA-256 hash of a token string and returns it as a lowercase hex string.
     *
     * <p>Usage:
     * <pre>
     * String raw  = jwtService.generateRefreshTokenValue();  // UUID string
     * String hash = TokenHashUtil.sha256(raw);               // stored in DB
     * // return raw to client; hash goes to refresh_tokens table
     * </pre>
     *
     * @param raw the plaintext token value (e.g., a UUID string or "tf_..." API key)
     * @return 64-character lowercase hex string (256 bits)
     * @throws IllegalStateException if SHA-256 is somehow unavailable (never happens on JVM)
     */
    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec — this branch is unreachable in practice
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

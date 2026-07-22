package com.taskforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JwtProperties — type-safe binding of the {@code app.jwt.*} block in {@code application.yml}.
 *
 * <p><b>Why @ConfigurationProperties instead of @Value?</b>
 * {@code @Value("${app.jwt.secret}")} is fine for a single field, but once you have a group
 * of related properties (secret, access expiry, refresh expiry), {@code @ConfigurationProperties}
 * gives you:
 * <ul>
 *   <li>A single, self-documenting class — all JWT config in one place</li>
 *   <li>Type safety — Spring coerces {@code 900000} to {@code long} automatically</li>
 *   <li>IDE auto-complete in application.yml (with spring-boot-configuration-processor)</li>
 *   <li>Easy unit testing — just {@code new JwtProperties()} and set fields directly</li>
 * </ul>
 *
 * <p><b>How it's wired:</b>
 * {@code @EnableConfigurationProperties(JwtProperties.class)} on {@link com.taskforge.TaskForgeApplication}
 * registers this class as a Spring bean and triggers binding from the environment.
 *
 * <p><b>application.yml mapping:</b>
 * <pre>
 * app:
 *   jwt:
 *     secret: ${JWT_SECRET:change-me-in-production-use-a-long-random-string}
 *     access-token-expiry-ms: 900000      # 15 minutes
 *     refresh-token-expiry-ms: 604800000  # 7 days
 * </pre>
 *
 * <p><b>Production secret requirement:</b>
 * The secret key for HS256 (HMAC-SHA256) must be at least 256 bits (32 bytes).
 * Spring Boot's relaxed binding maps {@code access-token-expiry-ms} →
 * {@code accessTokenExpiryMs} automatically (kebab-case to camelCase).
 *
 * <p><b>Phase 2 usage:</b>
 * Injected into {@link com.taskforge.auth.JwtService} via constructor injection.
 * Never inject these values directly into controllers — always go through JwtService.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Data
public class JwtProperties {

    /**
     * HMAC-SHA256 signing secret.
     *
     * <p>Must be at least 256 bits when Base64-decoded (JJWT enforces this at runtime).
     * In production: loaded from {@code JWT_SECRET} environment variable.
     * In development: the placeholder string from application.yml is sufficient for local runs
     * but will cause JJWT to throw a {@code WeakKeyException} if it's too short — a
     * deliberate fail-fast to prevent accidentally deploying a weak key.
     *
     * <p>Generation command (run once, store in secrets manager):
     * <pre>openssl rand -base64 64</pre>
     */
    private String secret;

    /**
     * Lifetime of a JWT access token in milliseconds.
     *
     * <p>Default: 900000 ms = 15 minutes.
     * Short lifetime limits damage if a token is stolen — the attacker's window closes
     * when the token expires (JWTs cannot be individually revoked without a blocklist).
     */
    private long accessTokenExpiryMs;

    /**
     * Lifetime of a refresh token in milliseconds.
     *
     * <p>Default: 604800000 ms = 7 days.
     * Refresh tokens ARE revocable server-side (stored as SHA-256 hashes in
     * the {@code refresh_tokens} table with a {@code revoked_at} column).
     * Each refresh rotates the token: old one is revoked, new one is issued.
     */
    private long refreshTokenExpiryMs;
}

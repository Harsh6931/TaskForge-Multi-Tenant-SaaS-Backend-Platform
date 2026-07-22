package com.taskforge.auth;

import com.taskforge.config.JwtProperties;
import com.taskforge.user.entity.TenantUserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JwtService — the single source of truth for JWT creation and validation.
 *
 * <p><b>Responsibilities:</b>
 * <ol>
 *   <li>Generate signed JWT access tokens containing userId, tenantId, and role claims</li>
 *   <li>Parse and validate incoming access tokens (signature + expiry check)</li>
 *   <li>Extract individual claims (userId, tenantId, role) from a validated Claims object</li>
 *   <li>Generate raw refresh token values (random UUID strings — hashing is the caller's job)</li>
 * </ol>
 *
 * <p><b>What this service does NOT do:</b>
 * <ul>
 *   <li>Store anything in the database — that's {@code AuthService}'s job</li>
 *   <li>Hash refresh tokens — callers use {@link com.taskforge.auth.util.TokenHashUtil}</li>
 *   <li>Handle HTTP — no @Controller, no @RequestMapping here</li>
 * </ul>
 *
 * <p><b>JJWT 0.12.x API notes:</b>
 * This project uses JJWT 0.12.5 which introduced a new, cleaner API:
 * <ul>
 *   <li>{@code Jwts.builder()} — creates a new JWT</li>
 *   <li>{@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token)} — validates + parses</li>
 *   <li>{@code Keys.hmacShaKeyFor(bytes)} — derives a {@link SecretKey} from raw bytes</li>
 * </ul>
 * The old 0.11.x API used {@code Jwts.parserBuilder()} — that's gone in 0.12.x.
 *
 * <p><b>Algorithm: HS256 (HMAC-SHA256)</b>
 * Symmetric — same key signs and verifies. Fine for a single backend service.
 * For microservices where multiple services verify tokens, prefer RS256 (asymmetric).
 *
 * <p><b>Claim names (custom claims in payload):</b>
 * <pre>
 * {
 *   "sub":      "550e8400-...",   ← standard "subject" claim = userId
 *   "tenantId": "660f9511-...",   ← custom claim
 *   "role":     "ADMIN",          ← custom claim (TenantUserRole.name())
 *   "iat":      1700000000,       ← standard "issued at"
 *   "exp":      1700000900        ← standard "expires at" (iat + 15 min)
 * }
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    // Custom claim key constants — declared here to avoid magic strings scattered across the codebase
    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_ROLE      = "role";

    private final JwtProperties jwtProperties;

    // ── Token generation ──────────────────────────────────────────────────────

    /**
     * Generates a signed JWT access token for the given user + tenant context.
     *
     * <p><b>Token lifetime:</b> configured via {@code app.jwt.access-token-expiry-ms}
     * (default 900 000 ms = 15 minutes).
     *
     * <p><b>tenantId/role nullable:</b> On fresh signup the user has no tenant yet.
     * Pass {@code null} for both — the filter will handle a token with no tenantId
     * by setting no tenant context (only /auth/* endpoints are accessible until the
     * user joins or creates a tenant in Phase 3).
     *
     * @param userId   the authenticated user's UUID — stored as the "sub" claim
     * @param tenantId the current workspace UUID — stored as "tenantId" claim; nullable
     * @param role     the user's role in this tenant — stored as "role" claim; nullable
     * @return a compact, URL-safe JWT string ready to send to the client
     */
    public String generateAccessToken(UUID userId, UUID tenantId, TenantUserRole role) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getAccessTokenExpiryMs());

        var builder = Jwts.builder()
                .subject(userId.toString())                     // "sub" claim = userId
                .issuedAt(Date.from(now))                       // "iat"
                .expiration(Date.from(expiry))                  // "exp"
                .signWith(signingKey());                        // HMAC-SHA256

        // Only embed tenantId and role when present (not null for fresh signups)
        if (tenantId != null) {
            builder.claim(CLAIM_TENANT_ID, tenantId.toString());
        }
        if (role != null) {
            builder.claim(CLAIM_ROLE, role.name());
        }

        String token = builder.compact();
        log.debug("Generated access token for userId={} tenantId={} role={} expiresAt={}",
                userId, tenantId, role, expiry);
        return token;
    }

    /**
     * Generates a raw refresh token value — a random UUID string.
     *
     * <p><b>Important:</b> This is the raw plaintext value. The caller (AuthService)
     * is responsible for:
     * <ol>
     *   <li>Returning this raw value to the client (sent once, never stored as-is)</li>
     *   <li>Storing the SHA-256 hash of this value in the {@code refresh_tokens} table</li>
     * </ol>
     *
     * <p>Why UUID? A UUID is 128 bits of (pseudo-)random data — sufficient entropy to make
     * brute-forcing the hash computationally infeasible. No need for a longer value.
     *
     * @return a raw refresh token string like {@code "550e8400-e29b-41d4-a716-446655440000"}
     */
    public String generateRefreshTokenValue() {
        return UUID.randomUUID().toString();
    }

    // ── Token parsing + validation ────────────────────────────────────────────

    /**
     * Parses and validates a JWT access token string.
     *
     * <p>JJWT automatically validates:
     * <ul>
     *   <li>Signature integrity — throws {@link io.jsonwebtoken.security.SignatureException} if tampered</li>
     *   <li>Token expiry — throws {@link ExpiredJwtException} if past {@code exp}</li>
     *   <li>Token format — throws {@link io.jsonwebtoken.MalformedJwtException} if malformed</li>
     * </ul>
     *
     * <p>All JJWT exceptions are subclasses of {@link JwtException}, so callers can catch
     * either the specific type or the parent for uniform error handling.
     *
     * @param token the raw JWT string (without "Bearer " prefix — strip that before calling)
     * @return the validated {@link Claims} payload — safe to read after this returns
     * @throws ExpiredJwtException if the token's {@code exp} is in the past
     * @throws JwtException        if the token is invalid for any other reason (signature, format, etc.)
     */
    public Claims parseAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // ── Claim extractors ─────────────────────────────────────────────────────

    /**
     * Extracts the userId from a validated Claims object.
     *
     * <p>The userId is stored in the standard {@code sub} (subject) claim as a UUID string.
     *
     * @param claims from {@link #parseAccessToken(String)}
     * @return the authenticated user's UUID
     */
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Extracts the tenantId from a validated Claims object.
     *
     * <p>Returns {@code null} if the token has no {@code tenantId} claim — this happens
     * for freshly-signed-up users who haven't joined a tenant yet.
     *
     * @param claims from {@link #parseAccessToken(String)}
     * @return the current workspace UUID, or {@code null} if no tenant context in the token
     */
    public UUID extractTenantId(Claims claims) {
        String tenantIdStr = claims.get(CLAIM_TENANT_ID, String.class);
        return tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;
    }

    /**
     * Extracts the user's RBAC role from a validated Claims object.
     *
     * <p>Returns {@code null} if the token has no {@code role} claim (fresh signup, no tenant).
     *
     * @param claims from {@link #parseAccessToken(String)}
     * @return the user's {@link TenantUserRole} in the current tenant, or {@code null}
     */
    public TenantUserRole extractRole(Claims claims) {
        String roleStr = claims.get(CLAIM_ROLE, String.class);
        return roleStr != null ? TenantUserRole.valueOf(roleStr) : null;
    }

    /**
     * Checks whether a validated Claims object represents an expired token.
     *
     * <p>Note: {@link #parseAccessToken(String)} already throws {@link ExpiredJwtException}
     * for expired tokens — you normally don't need to call this separately.
     * This helper is useful in tests and in the refresh flow where you might want to
     * explicitly inspect expiry without relying on exception control flow.
     *
     * @param claims from {@link #parseAccessToken(String)}
     * @return {@code true} if the token is expired, {@code false} if still valid
     */
    public boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }

    // ── Internal key construction ─────────────────────────────────────────────

    /**
     * Derives the HMAC-SHA256 {@link SecretKey} from the configured secret string.
     *
     * <p><b>Called on every token operation</b> — no caching needed (the key derivation
     * is cheap; the SecretKey itself is tiny and immutable). If you prefer, you could
     * cache this as an instance field initialised in a {@code @PostConstruct} method,
     * but the current approach keeps the code simpler and avoids field initialisation order issues.
     *
     * <p><b>Why {@code getBytes(StandardCharsets.UTF_8)} instead of Base64 decode?</b>
     * The secret in {@code application.yml} is a plain string, not Base64-encoded.
     * Using raw UTF-8 bytes is correct here. If you change the config to store a
     * Base64-encoded value, switch to {@code io.jsonwebtoken.io.Decoders.BASE64.decode(secret)}.
     *
     * <p><b>Key length enforcement:</b> {@code Keys.hmacShaKeyFor()} throws
     * {@link io.jsonwebtoken.security.WeakKeyException} if the key is shorter than
     * 256 bits (32 bytes). This is a deliberate fail-fast — a weak key is a security bug.
     * The dev placeholder {@code "change-me-in-production-use-a-long-random-string"}
     * is 52 characters = 416 bits, so it passes this check.
     *
     * @return a {@link SecretKey} ready for use with {@code Jwts.builder().signWith()}
     */
    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}

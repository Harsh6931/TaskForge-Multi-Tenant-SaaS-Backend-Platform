package com.taskforge.auth;

import com.taskforge.config.JwtProperties;
import com.taskforge.user.entity.TenantUserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * JwtServiceTest — pure unit test for JwtService.
 *
 * <p><b>No Spring context needed.</b> JwtService only depends on JwtProperties,
 * which is a plain POJO. We construct both directly — fast, isolated, no @SpringBootTest overhead.
 *
 * <p><b>Test secret:</b> Must be ≥32 bytes for HS256. We use a dedicated test value,
 * never the production secret (which is in env vars anyway).
 */
@DisplayName("JwtService — token generation and validation")
class JwtServiceTest {

    private static final String TEST_SECRET =
            "test-secret-key-must-be-at-least-32-bytes-long-for-hs256"; // 57 chars = 456 bits ✅

    private static final long ACCESS_EXPIRY_MS  = 900_000L;   // 15 min
    private static final long REFRESH_EXPIRY_MS = 604_800_000L; // 7 days

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(TEST_SECRET);
        props.setAccessTokenExpiryMs(ACCESS_EXPIRY_MS);
        props.setRefreshTokenExpiryMs(REFRESH_EXPIRY_MS);

        jwtService = new JwtService(props);
    }

    // ── generateAccessToken ───────────────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken: produces a non-null, non-empty JWT string")
    void generateAccessToken_producesNonEmptyString() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId, tenantId, TenantUserRole.ADMIN);

        assertThat(token)
                .isNotNull()
                .isNotEmpty()
                .contains(".");   // at minimum it has the dot-separated structure
    }

    @Test
    @DisplayName("generateAccessToken: JWT contains exactly 3 Base64 segments (header.payload.signature)")
    void generateAccessToken_hasThreeSegments() {
        String token = jwtService.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), TenantUserRole.MEMBER);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
    }

    @Test
    @DisplayName("generateAccessToken: produces different tokens for different users")
    void generateAccessToken_differentUsers_differentTokens() {
        UUID tenantId = UUID.randomUUID();

        String tokenA = jwtService.generateAccessToken(UUID.randomUUID(), tenantId, TenantUserRole.MEMBER);
        String tokenB = jwtService.generateAccessToken(UUID.randomUUID(), tenantId, TenantUserRole.MEMBER);

        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    @Test
    @DisplayName("generateAccessToken: null tenantId/role allowed (fresh signup — no tenant yet)")
    void generateAccessToken_nullTenantAndRole_doesNotThrow() {
        UUID userId = UUID.randomUUID();

        assertThatCode(() -> jwtService.generateAccessToken(userId, null, null))
                .doesNotThrowAnyException();
    }

    // ── parseAccessToken ──────────────────────────────────────────────────────

    @Test
    @DisplayName("parseAccessToken: valid token returns Claims without throwing")
    void parseAccessToken_validToken_returnsClaims() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token  = jwtService.generateAccessToken(userId, tenantId, TenantUserRole.MANAGER);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(claims).isNotNull();
    }

    @Test
    @DisplayName("parseAccessToken: tampered token (modified payload) throws JwtException")
    void parseAccessToken_tamperedToken_throwsJwtException() {
        String token = jwtService.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), TenantUserRole.ADMIN);

        // Tamper: flip the last character of the signature segment
        String tampered = token.substring(0, token.length() - 1) +
                (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> jwtService.parseAccessToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("parseAccessToken: token signed with a different key throws JwtException")
    void parseAccessToken_wrongKey_throwsJwtException() {
        // Build a token with a completely different secret
        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret("completely-different-secret-key-at-least-32-bytes-long!!");
        otherProps.setAccessTokenExpiryMs(ACCESS_EXPIRY_MS);
        otherProps.setRefreshTokenExpiryMs(REFRESH_EXPIRY_MS);
        JwtService otherService = new JwtService(otherProps);

        String foreignToken = otherService.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), TenantUserRole.VIEWER);

        // Our JwtService (different key) should reject the foreign token
        assertThatThrownBy(() -> jwtService.parseAccessToken(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("parseAccessToken: expired token throws ExpiredJwtException")
    void parseAccessToken_expiredToken_throwsExpiredJwtException() {
        // Build a service with a -1ms expiry (already expired on creation)
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(TEST_SECRET);
        expiredProps.setAccessTokenExpiryMs(-1L);   // expires 1ms before issuedAt
        expiredProps.setRefreshTokenExpiryMs(REFRESH_EXPIRY_MS);
        JwtService expiredService = new JwtService(expiredProps);

        String expiredToken = expiredService.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), TenantUserRole.MEMBER);

        assertThatThrownBy(() -> jwtService.parseAccessToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("parseAccessToken: completely garbage string throws JwtException")
    void parseAccessToken_malformedString_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.parseAccessToken("this.is.garbage"))
                .isInstanceOf(JwtException.class);
    }

    // ── Claim extractors ──────────────────────────────────────────────────────

    @Test
    @DisplayName("extractUserId: returns the exact UUID that was encoded in the token")
    void extractUserId_returnsCorrectUuid() {
        UUID expectedUserId = UUID.randomUUID();

        String token  = jwtService.generateAccessToken(expectedUserId, UUID.randomUUID(), TenantUserRole.ADMIN);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(jwtService.extractUserId(claims)).isEqualTo(expectedUserId);
    }

    @Test
    @DisplayName("extractTenantId: returns the exact UUID that was encoded in the token")
    void extractTenantId_returnsCorrectUuid() {
        UUID expectedTenantId = UUID.randomUUID();

        String token  = jwtService.generateAccessToken(UUID.randomUUID(), expectedTenantId, TenantUserRole.VIEWER);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(jwtService.extractTenantId(claims)).isEqualTo(expectedTenantId);
    }

    @Test
    @DisplayName("extractTenantId: returns null when token has no tenantId claim (fresh signup)")
    void extractTenantId_noTenantClaim_returnsNull() {
        String token  = jwtService.generateAccessToken(UUID.randomUUID(), null, null);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(jwtService.extractTenantId(claims)).isNull();
    }

    @Test
    @DisplayName("extractRole: returns the exact role that was encoded in the token")
    void extractRole_returnsCorrectRole() {
        for (TenantUserRole expectedRole : TenantUserRole.values()) {
            String token  = jwtService.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), expectedRole);
            Claims claims = jwtService.parseAccessToken(token);

            assertThat(jwtService.extractRole(claims))
                    .as("Role %s should round-trip correctly", expectedRole)
                    .isEqualTo(expectedRole);
        }
    }

    @Test
    @DisplayName("extractRole: returns null when token has no role claim (fresh signup)")
    void extractRole_noRoleClaim_returnsNull() {
        String token  = jwtService.generateAccessToken(UUID.randomUUID(), null, null);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(jwtService.extractRole(claims)).isNull();
    }

    // ── isTokenExpired ────────────────────────────────────────────────────────

    @Test
    @DisplayName("isTokenExpired: returns false for a freshly generated token")
    void isTokenExpired_freshToken_returnsFalse() {
        String token  = jwtService.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), TenantUserRole.ADMIN);
        Claims claims = jwtService.parseAccessToken(token);

        assertThat(jwtService.isTokenExpired(claims)).isFalse();
    }

    // ── generateRefreshTokenValue ─────────────────────────────────────────────

    @Test
    @DisplayName("generateRefreshTokenValue: produces a non-null, non-empty string")
    void generateRefreshTokenValue_producesNonEmptyString() {
        String raw = jwtService.generateRefreshTokenValue();

        assertThat(raw).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("generateRefreshTokenValue: each call produces a unique value (no collisions in 1000 runs)")
    void generateRefreshTokenValue_producesUniqueValues() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generated.add(jwtService.generateRefreshTokenValue());
        }

        // All 1000 should be unique
        assertThat(generated).hasSize(1000);
    }

    @Test
    @DisplayName("generateRefreshTokenValue: value is a valid UUID string (parseable)")
    void generateRefreshTokenValue_isValidUuidString() {
        String raw = jwtService.generateRefreshTokenValue();

        assertThatCode(() -> UUID.fromString(raw))
                .doesNotThrowAnyException();
    }
}

package com.taskforge.auth;

import com.taskforge.auth.dto.*;
import com.taskforge.auth.entity.RefreshToken;
import com.taskforge.auth.repository.RefreshTokenRepository;
import com.taskforge.auth.util.TokenHashUtil;
import com.taskforge.common.exception.EmailAlreadyExistsException;
import com.taskforge.common.exception.InvalidCredentialsException;
import com.taskforge.common.exception.InvalidTokenException;
import com.taskforge.common.exception.TenantAccessDeniedException;
import com.taskforge.config.JwtProperties;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.entity.TenantUser;
import com.taskforge.user.entity.TenantUserRole;
import com.taskforge.user.entity.User;
import com.taskforge.user.repository.TenantUserRepository;
import com.taskforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AuthService — core business logic for all authentication and authorization operations.
 *
 * <p><b>Design principle:</b> This service is purely business logic — no HTTP, no
 * Jackson, no servlet types. It can be unit-tested with mocks only (no Spring context).
 * The controller is just a thin translation layer above this service.
 *
 * <p><b>Transactional strategy:</b>
 * The class is annotated {@code @Transactional} so every public method runs in a transaction
 * by default. Methods that only read data are annotated with {@code @Transactional(readOnly=true)}
 * for a performance hint to the JPA provider (Hibernate skips dirty checking on read-only txns).
 *
 * <p><b>Token lifecycle managed here:</b>
 * <ul>
 *   <li>Signup/Login → issue access token + refresh token, persist refresh token hash</li>
 *   <li>Refresh → validate, rotate (revoke old + issue new), re-issue access token</li>
 *   <li>Logout → revoke all active refresh tokens for the user</li>
 *   <li>SwitchTenant → verify membership, issue new tenant-scoped token pair</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthService {

    private final UserRepository          userRepository;
    private final TenantUserRepository    tenantUserRepository;
    private final TenantRepository        tenantRepository;
    private final RefreshTokenRepository  refreshTokenRepository;
    private final JwtService              jwtService;
    private final PasswordEncoder         passwordEncoder;
    private final JwtProperties           jwtProperties;

    // ── Signup ────────────────────────────────────────────────────────────────

    /**
     * Registers a new user and returns a token pair.
     *
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Reject duplicate email (409 Conflict)</li>
     *   <li>BCrypt-encode the raw password and persist the User</li>
     *   <li>Issue access token with no tenant context (fresh user has no workspace yet)</li>
     *   <li>Issue refresh token, persist its hash</li>
     *   <li>Return AuthResponse with empty tenant list</li>
     * </ol>
     *
     * <p>A tenant/workspace is created separately (Phase 3 — POST /tenants). Signup
     * only creates the global user identity; membership is managed via tenant_users.
     *
     * @param request validated signup payload
     * @return token pair + empty tenant list
     * @throws EmailAlreadyExistsException if the email is already registered
     */
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .build();
        user = userRepository.save(user);

        log.info("New user registered: userId={} email={}", user.getId(), user.getEmail());

        String accessToken  = jwtService.generateAccessToken(user.getId(), null, null);
        String rawRefresh   = persistRefreshToken(user);

        return new AuthResponse(accessToken, rawRefresh, List.of());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates a user and returns a token pair scoped to the user's primary tenant.
     *
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Load User by email — returns 401 if not found (prevents enumeration)</li>
     *   <li>BCrypt verify raw password against stored hash — 401 on mismatch</li>
     *   <li>Load all TenantUser memberships → build TenantSummary list</li>
     *   <li>Scope access token to first tenant (if any); null tenant if user has none</li>
     *   <li>Rotate/issue new refresh token pair</li>
     *   <li>Return AuthResponse with tenant list (frontend renders workspace switcher)</li>
     * </ol>
     *
     * @param request validated login credentials
     * @return token pair + list of all tenant memberships
     * @throws InvalidCredentialsException if email not found or password doesn't match
     */
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // Load all tenant memberships for this user (used for workspace switcher in frontend)
        List<TenantUser> memberships = tenantUserRepository.findAllByUserId(user.getId());
        List<TenantSummary> tenantSummaries = memberships.stream()
                .map(this::toTenantSummary)
                .toList();

        // Scope the first access token to the user's first tenant (if they have one)
        UUID   primaryTenantId = memberships.isEmpty() ? null : memberships.get(0).getTenant().getId();
        TenantUserRole primaryRole = memberships.isEmpty() ? null : memberships.get(0).getRole();

        String accessToken = jwtService.generateAccessToken(user.getId(), primaryTenantId, primaryRole);
        String rawRefresh  = persistRefreshToken(user);

        log.info("User logged in: userId={} tenantId={}", user.getId(), primaryTenantId);

        return new AuthResponse(accessToken, rawRefresh, tenantSummaries);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Validates a refresh token and rotates it, returning a new access + refresh token pair.
     *
     * <p><b>Rotation flow:</b>
     * <ol>
     *   <li>SHA-256 hash the incoming raw token</li>
     *   <li>Look up the RefreshToken entity by hash — 401 if not found</li>
     *   <li>Reject if revoked — 401 (possible replay attack signal)</li>
     *   <li>Reject if expired — 401 (user must re-login)</li>
     *   <li>Revoke the old token (set {@code revoked_at = now()})</li>
     *   <li>Issue a new refresh token and persist its hash</li>
     *   <li>Re-issue access token preserving the user's tenant context</li>
     * </ol>
     *
     * <p><b>Why rotate?</b> If a stolen refresh token is used by an attacker, the next
     * time the real user refreshes, they'll find their token already rotated → 401 → signal
     * of compromise. Without rotation, a stolen token works silently for the full 7 days.
     *
     * @param request validated refresh payload containing the raw token
     * @return new access token + new refresh token
     * @throws InvalidTokenException if the token is unknown, revoked, or expired
     */
    public RefreshResponse refresh(RefreshRequest request) {
        String tokenHash = TokenHashUtil.sha256(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        if (stored.isRevoked()) {
            log.warn("Attempted use of revoked refresh token for userId={}", stored.getUser().getId());
            throw new InvalidTokenException("Refresh token has been revoked");
        }

        if (stored.isExpired()) {
            throw new InvalidTokenException("Refresh token has expired");
        }

        // Revoke old token (rotation step 1)
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        User user = stored.getUser();

        // Reload tenant context — pick the user's first active membership
        List<TenantUser> memberships = tenantUserRepository.findAllByUserId(user.getId());
        UUID   tenantId = memberships.isEmpty() ? null : memberships.get(0).getTenant().getId();
        TenantUserRole role = memberships.isEmpty() ? null : memberships.get(0).getRole();

        // Issue new token pair (rotation step 2)
        String newAccessToken = jwtService.generateAccessToken(user.getId(), tenantId, role);
        String newRawRefresh  = persistRefreshToken(user);

        log.debug("Refresh token rotated for userId={}", user.getId());

        return new RefreshResponse(newAccessToken, newRawRefresh);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Revokes all active refresh tokens for the given user (logout-everywhere).
     *
     * <p>A single bulk UPDATE via {@link RefreshTokenRepository#revokeAllActiveByUserId}
     * instead of N individual saves. Access tokens are short-lived (15 min) and
     * cannot be individually revoked without a blocklist — they will naturally expire.
     *
     * @param userId the authenticated user's ID (extracted from JWT by the controller)
     */
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
        log.info("All refresh tokens revoked for userId={}", userId);
    }

    // ── Switch Tenant ─────────────────────────────────────────────────────────

    /**
     * Issues a new token pair scoped to a different tenant workspace.
     *
     * <p>This is the multi-tenant workspace switch — like switching between Slack workspaces.
     * The user's existing access token is for tenant A; calling this with tenant B's ID
     * issues a new access token scoped to tenant B (with the user's role in B).
     *
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Look up the user's TenantUser record for the target tenant</li>
     *   <li>403 if no membership found (user is not in that workspace)</li>
     *   <li>Issue new access token scoped to targetTenantId + role in that tenant</li>
     *   <li>Issue new refresh token (rotate — old one not explicitly revoked here;
     *       the client should stop using it)</li>
     * </ol>
     *
     * @param userId         the authenticated user's ID
     * @param targetTenantId the tenant to switch to
     * @return new token pair scoped to the target tenant + updated tenant list
     * @throws TenantAccessDeniedException if the user is not a member of targetTenantId
     */
    public AuthResponse switchTenant(UUID userId, UUID targetTenantId) {
        TenantUser membership = tenantUserRepository
                .findByTenantIdAndUserId(targetTenantId, userId)
                .orElseThrow(() -> new TenantAccessDeniedException(
                        "User is not a member of the requested tenant"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException());

        String accessToken = jwtService.generateAccessToken(userId, targetTenantId, membership.getRole());
        String rawRefresh  = persistRefreshToken(user);

        // Build full tenant list for the response (frontend workspace switcher)
        List<TenantSummary> tenantSummaries = tenantUserRepository
                .findAllByUserId(userId).stream()
                .map(this::toTenantSummary)
                .toList();

        log.info("Tenant switched: userId={} → tenantId={} role={}",
                userId, targetTenantId, membership.getRole());

        return new AuthResponse(accessToken, rawRefresh, tenantSummaries);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Generates a raw refresh token, hashes it, persists the entity, and returns the raw value.
     *
     * <p>The raw value is returned to the caller (and ultimately to the client).
     * The hash is what lives in the database. The two never coexist after this method returns.
     *
     * @param user the user to issue the token for
     * @return the raw plaintext refresh token to send to the client
     */
    private String persistRefreshToken(User user) {
        String raw  = jwtService.generateRefreshTokenValue();
        String hash = TokenHashUtil.sha256(raw);

        Instant expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpiryMs());

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .build();
        refreshTokenRepository.save(token);

        return raw;
    }

    /**
     * Maps a {@link TenantUser} join entity to the compact {@link TenantSummary} DTO.
     * Eagerly accesses the Tenant name and slug — callers must be inside a transaction.
     */
    private TenantSummary toTenantSummary(TenantUser tu) {
        Tenant tenant = tu.getTenant();
        return new TenantSummary(tenant.getId(), tenant.getName(), tenant.getSlug(), tu.getRole());
    }
}

package com.taskforge.auth.repository;

import com.taskforge.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * RefreshTokenRepository — data access for {@link RefreshToken} entities.
 *
 * <p><b>No RLS on this table:</b>
 * {@code refresh_tokens} is user-scoped, not tenant-scoped, so it has no
 * {@code tenant_id} column and no RLS policy. The {@code TenantConnectionInterceptor}
 * setting {@code app.current_tenant_id} has no effect on queries here.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Primary lookup for token validation — called in {@code AuthService.refresh()}.
     *
     * <p>The flow: client sends raw token → {@code TokenHashUtil.sha256(raw)} → query this method.
     * Returns empty if the hash doesn't exist (unknown token) or the token was already used
     * (rotated tokens are deleted from DB on rotation, OR have revoked_at set).
     *
     * @param tokenHash SHA-256 hex hash of the raw token
     * @return the matching RefreshToken, or empty if not found
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Logout-everywhere — revokes all active refresh tokens for a user in a single UPDATE.
     *
     * <p>Called by {@code AuthService.logout(userId)} to invalidate all sessions across
     * all devices. Uses a bulk JPQL UPDATE (one DB round-trip) instead of
     * {@code findAll → forEach → save} (N round-trips).
     *
     * <p>Only updates tokens where {@code revoked_at IS NULL} — already-revoked tokens
     * are left unchanged (idempotent).
     *
     * @param userId  the user whose tokens to revoke
     * @param revokedAt the timestamp to set (pass {@code Instant.now()} from the caller)
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :revokedAt
             WHERE rt.user.id = :userId
               AND rt.revokedAt IS NULL
            """)
    void revokeAllActiveByUserId(@Param("userId") UUID userId,
                                 @Param("revokedAt") Instant revokedAt);
}

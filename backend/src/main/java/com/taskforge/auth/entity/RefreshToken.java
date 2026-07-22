package com.taskforge.auth.entity;

import com.taskforge.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * RefreshToken — persists the SHA-256 hash of a refresh token issued to a user.
 *
 * <p><b>Why store only the hash?</b>
 * We never store the raw refresh token value. If the database is compromised,
 * the attacker gets only SHA-256 hashes of random UUIDs — computationally
 * infeasible to reverse. The raw value is returned to the client exactly once
 * (at login/refresh time) and is never persisted.
 *
 * <p><b>Why not extend BaseEntity?</b>
 * {@link com.taskforge.common.BaseEntity} adds {@code updated_at} and {@code deleted_at}.
 * The {@code refresh_tokens} table has neither — it only has {@code created_at}
 * and {@code revoked_at}. Instead of fighting Hibernate column validation, we manage
 * the two fields explicitly in this entity (matching V4__create_auth_and_keys.sql exactly).
 *
 * <p><b>Revocation vs. expiry:</b>
 * <ul>
 *   <li>{@code expires_at} — hard time limit; even a valid, non-revoked token is rejected after this</li>
 *   <li>{@code revoked_at} — soft revocation; set on logout or refresh token rotation.
 *       A token with {@code revoked_at != null} is always rejected, regardless of expiry.</li>
 * </ul>
 *
 * <p><b>No tenant_id:</b>
 * Refresh tokens are user-scoped, not tenant-scoped. A single refresh token can be used
 * to switch tenants via {@code /auth/switch-tenant}. RLS is NOT applied to this table.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The user this token belongs to.
     * Loaded lazily — we only need the User object when building AuthResponse after refresh.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * SHA-256 hash of the raw refresh token string.
     * Lookup path: client sends raw token → server hashes it → matches this column.
     *
     * <p>Hash computed via {@link com.taskforge.auth.util.TokenHashUtil#sha256(String)}.
     */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    /**
     * Absolute expiry timestamp — 7 days after issuance by default
     * ({@code app.jwt.refresh-token-expiry-ms}).
     */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * Set once on insert by {@code @Builder.Default} so it's never null.
     * Not managed by JPA auditing (no BaseEntity) — set explicitly here.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Null when the token is active. Set to {@code Instant.now()} on:
     * <ul>
     *   <li>Logout (all tokens for the user are revoked at once)</li>
     *   <li>Token rotation — old token revoked when a new one is issued</li>
     * </ul>
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Convenience method — true if the token has been explicitly revoked.
     * Does NOT check expiry — use both checks when validating.
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    /**
     * Convenience method — true if the token has passed its hard expiry time.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}

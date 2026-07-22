package com.taskforge.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * ApiKey — persists a per-tenant API key as a SHA-256 hash.
 *
 * <p><b>Purpose:</b>
 * API keys allow B2B customers to authenticate programmatically (CI/CD pipelines,
 * integrations, scripts) without user credentials. They are scoped to a tenant
 * and carry the implicit role of the tenant context (treated as ADMIN-level within
 * that tenant for API access — callers should restrict further via endpoint @PreAuthorize).
 *
 * <p><b>Key format:</b>
 * Raw key: {@code tf_<32-hex-chars>}  e.g., {@code tf_550e8400e29b41d4a716446655440000}
 * This prefix ({@code tf_}) makes TaskForge keys instantly recognisable in logs and
 * security scanners (similar to {@code ghp_} for GitHub tokens, {@code sk-} for OpenAI).
 *
 * <p><b>Storage:</b>
 * Only the SHA-256 hash is stored — the raw key is shown to the user exactly once
 * (in the POST /api-keys response) and is never persisted.
 *
 * <p><b>Why not extend BaseEntity?</b>
 * The {@code api_keys} table has no {@code updated_at} or {@code deleted_at} — revocation
 * is signalled by {@code revoked_at}. Extending BaseEntity would cause Hibernate to
 * expect columns that don't exist in the V4 migration.
 *
 * <p><b>RLS:</b>
 * {@code api_keys} is RLS-protected (V7 migration). Queries are filtered to the
 * current tenant session variable — only keys belonging to the active tenant are visible.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The tenant this key belongs to. A key is always scoped to one tenant.
     * RLS will enforce this at the DB layer; this field allows the application
     * layer to do fast cross-tenant ownership checks (e.g., prevent Tenant A
     * from revoking Tenant B's keys).
     */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /**
     * SHA-256 hash of the raw API key string.
     * Lookup path: client sends {@code Authorization: ApiKey tf_...}
     * → server SHA-256 hashes the value → matches this column.
     */
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    /**
     * Human-readable label the tenant admin gave this key (e.g., "CI/CD Pipeline", "Zapier").
     * Helps identify keys in the management UI without exposing the key value.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Updated (best-effort) each time the key is used successfully.
     * Useful for identifying stale or compromised keys.
     * Null until first use.
     */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * Null = active key. Set to now() on revocation.
     * Once revoked, the key is permanently rejected — there is no un-revoke.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Convenience method — true if this key has been revoked. */
    public boolean isRevoked() {
        return revokedAt != null;
    }
}

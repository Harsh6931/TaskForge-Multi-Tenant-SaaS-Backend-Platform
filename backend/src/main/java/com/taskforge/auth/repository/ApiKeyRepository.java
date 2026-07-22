package com.taskforge.auth.repository;

import com.taskforge.auth.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ApiKeyRepository — data access for {@link ApiKey} entities.
 *
 * <p><b>RLS active on this table:</b>
 * {@code api_keys} is RLS-protected (V7 migration). All queries here are automatically
 * filtered to the current tenant session variable, so a tenant can only ever see and
 * manage their own API keys — even if the application layer forgot a WHERE clause.
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Primary lookup for API key authentication — called in {@code ApiKeyAuthenticationFilter}.
     *
     * <p>Flow: {@code Authorization: ApiKey tf_...} → SHA-256 hash → query this method.
     * Returns empty if the key doesn't exist or has been revoked
     * (index on {@code api_keys(key_hash) WHERE revoked_at IS NULL} makes this fast).
     *
     * <p>Note: the partial index in V4 only covers non-revoked rows. A revoked key
     * will still be found here if it matches the hash — callers must check
     * {@link ApiKey#isRevoked()} before granting access.
     *
     * @param keyHash SHA-256 hex hash of the raw API key value
     * @return the matching ApiKey, or empty if not found
     */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * List all non-revoked API keys for a tenant — used by GET /api-keys.
     *
     * <p>RLS ensures this never returns keys from another tenant even without
     * the {@code tenantId} filter, but we include it explicitly for clarity and
     * as a defence-in-depth measure.
     *
     * @param tenantId the tenant whose keys to list
     * @return all active (non-revoked) ApiKey records for this tenant
     */
    List<ApiKey> findAllByTenantIdAndRevokedAtIsNull(UUID tenantId);
}

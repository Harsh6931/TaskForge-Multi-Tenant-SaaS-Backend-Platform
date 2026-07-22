package com.taskforge.auth;

import com.taskforge.auth.dto.ApiKeyCreateRequest;
import com.taskforge.auth.dto.ApiKeyCreateResponse;
import com.taskforge.auth.dto.ApiKeyResponse;
import com.taskforge.auth.entity.ApiKey;
import com.taskforge.auth.repository.ApiKeyRepository;
import com.taskforge.auth.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ApiKeyService — business logic for API key lifecycle management.
 *
 * <p><b>Key format:</b> {@code tf_<32-hex-chars>}
 * The {@code tf_} prefix makes TaskForge keys recognisable in logs, git history
 * scanners (e.g., GitHub secret scanning), and support tickets.
 * The 32-char hex suffix is derived from a random UUID (128 bits of entropy).
 *
 * <p><b>Storage model:</b>
 * Only the SHA-256 hash of the raw key is stored. The raw value is returned once
 * at creation and is never retrievable afterward. This protects keys even if the
 * database is compromised.
 *
 * <p><b>RLS note:</b>
 * {@code api_keys} is RLS-protected. All DB queries in this service are automatically
 * filtered to the current tenant — the tenantId parameter in each method is an
 * application-layer defence-in-depth, not the sole security boundary.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Generates a new API key for the given tenant and returns the raw key ONCE.
     *
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Generate raw key: {@code tf_<UUID-hex-no-dashes>}</li>
     *   <li>SHA-256 hash the raw key</li>
     *   <li>Persist the hash (NOT the raw key) with name and tenantId</li>
     *   <li>Return the raw key in the response — it will never be shown again</li>
     * </ol>
     *
     * @param tenantId the tenant the key belongs to
     * @param request  contains the human-readable name for the key
     * @return the created ApiKey metadata + the raw key value (shown once only)
     */
    public ApiKeyCreateResponse createApiKey(UUID tenantId, ApiKeyCreateRequest request) {
        String rawKey  = generateRawKey();
        String keyHash = TokenHashUtil.sha256(rawKey);

        ApiKey apiKey = ApiKey.builder()
                .tenantId(tenantId)
                .keyHash(keyHash)
                .name(request.name())
                .build();

        apiKey = apiKeyRepository.save(apiKey);

        log.info("API key created: id={} name='{}' tenantId={}", apiKey.getId(), apiKey.getName(), tenantId);

        return new ApiKeyCreateResponse(apiKey.getId(), apiKey.getName(), rawKey);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Lists all active (non-revoked) API keys for the tenant — metadata only.
     *
     * <p>RLS enforces tenant isolation at the DB layer. The tenantId parameter
     * provides an additional application-layer filter.
     *
     * @param tenantId the tenant whose active keys to list
     * @return list of ApiKeyResponse (no key values, no hashes)
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(UUID tenantId) {
        return apiKeyRepository.findAllByTenantIdAndRevokedAtIsNull(tenantId).stream()
                .map(k -> new ApiKeyResponse(k.getId(), k.getName(), k.getCreatedAt(), k.getLastUsedAt()))
                .toList();
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    /**
     * Revokes an API key by setting its {@code revoked_at} timestamp.
     *
     * <p>Revocation is permanent — there is no un-revoke. The key will immediately
     * fail the {@code ApiKeyAuthenticationFilter} lookup after this call.
     *
     * <p>Ownership check: we load by ID + tenantId. If the key belongs to a different
     * tenant (or doesn't exist), we silently do nothing — 204 is still returned.
     * This prevents attackers from enumerating valid key IDs by probing revocation.
     *
     * @param tenantId the requesting tenant (must own the key)
     * @param keyId    the UUID of the key to revoke
     */
    public void revokeApiKey(UUID tenantId, UUID keyId) {
        apiKeyRepository.findById(keyId).ifPresent(key -> {
            // Ownership check — defence-in-depth (RLS also enforces this at DB level)
            if (!key.getTenantId().equals(tenantId)) {
                log.warn("API key revocation denied: keyId={} belongs to tenantId={}, requested by tenantId={}",
                        keyId, key.getTenantId(), tenantId);
                return;
            }
            if (!key.isRevoked()) {
                key.setRevokedAt(Instant.now());
                apiKeyRepository.save(key);
                log.info("API key revoked: id={} name='{}' tenantId={}", keyId, key.getName(), tenantId);
            }
        });
    }

    // ── Used by ApiKeyAuthenticationFilter ───────────────────────────────────

    /**
     * Looks up an API key by its raw value (hashes it first), validates it,
     * and updates {@code last_used_at} on success.
     *
     * <p>Called by {@link ApiKeyAuthenticationFilter} on every request that carries
     * an {@code Authorization: ApiKey <raw>} header.
     *
     * @param rawKey the raw API key from the Authorization header
     * @return the matching ApiKey entity if valid, or empty if unknown/revoked
     */
    public java.util.Optional<ApiKey> validateAndTouch(String rawKey) {
        String hash = TokenHashUtil.sha256(rawKey);
        return apiKeyRepository.findByKeyHash(hash).flatMap(key -> {
            if (key.isRevoked()) {
                log.warn("Rejected revoked API key hash prefix={}...", hash.substring(0, 8));
                return java.util.Optional.empty();
            }
            // Best-effort update of last_used_at — don't let this fail the request
            try {
                key.setLastUsedAt(Instant.now());
                apiKeyRepository.save(key);
            } catch (Exception e) {
                log.warn("Failed to update last_used_at for API key id={}: {}", key.getId(), e.getMessage());
            }
            return java.util.Optional.of(key);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Generates a raw API key in the format {@code tf_<32-hex-chars>}.
     *
     * <p>The hex string is derived from a random UUID with dashes removed —
     * 128 bits of entropy, URL-safe, and recognisable by the {@code tf_} prefix.
     */
    private String generateRawKey() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return "tf_" + hex;
    }
}

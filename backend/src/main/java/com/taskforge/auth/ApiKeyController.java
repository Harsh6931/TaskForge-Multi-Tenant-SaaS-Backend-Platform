package com.taskforge.auth;

import com.taskforge.auth.dto.ApiKeyCreateRequest;
import com.taskforge.auth.dto.ApiKeyCreateResponse;
import com.taskforge.auth.dto.ApiKeyResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ApiKeyController — REST endpoints for API key lifecycle management.
 *
 * <p>API contract:
 * <pre>
 * POST   /api-keys          { name } → { id, name, rawKey }  (rawKey shown once)
 * GET    /api-keys                   → [ { id, name, createdAt, lastUsedAt } ]
 * DELETE /api-keys/{id}              → 204 No Content
 * </pre>
 *
 * <p><b>RBAC:</b> Per {@code docs/RBAC.md}:
 * <ul>
 *   <li>Create/revoke keys → ADMIN only</li>
 *   <li>View keys (masked) → ADMIN + MANAGER</li>
 * </ul>
 *
 * <p><b>tenantId source:</b>
 * For JWT-authenticated requests, {@code @AuthenticationPrincipal} injects the userId
 * and we get tenantId from the JWT claims via {@code SecurityContext}. However, the
 * JWT principal is a UUID userId; the tenantId is in {@link com.taskforge.tenant.TenantContextHolder}.
 *
 * <p>For simplicity we read tenantId from the SecurityContext authentication details
 * (set by JwtAuthenticationFilter as the tenant-scoped JWT). We use a dedicated
 * {@code @AuthenticationPrincipal} approach: the principal is the userId UUID.
 * The tenantId is separately injected from {@link com.taskforge.tenant.TenantContextHolder}.
 */
@RestController
@RequestMapping("/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /**
     * Create a new API key for the current tenant.
     * Raw key is returned exactly once — the client must store it immediately.
     *
     * <p>RBAC: ADMIN only (per RBAC.md — "Create / revoke API keys → ADMIN").
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiKeyCreateResponse createApiKey(
            @Valid @RequestBody ApiKeyCreateRequest request,
            @AuthenticationPrincipal UUID userId) {

        UUID tenantId = com.taskforge.tenant.TenantContextHolder.getTenantId();
        return apiKeyService.createApiKey(tenantId, request);
    }

    /**
     * List all active (non-revoked) API keys for the current tenant.
     * Only metadata is returned — no key values or hashes.
     *
     * <p>RBAC: ADMIN + MANAGER (per RBAC.md — "View API keys (masked) → ADMIN + MANAGER").
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public List<ApiKeyResponse> listApiKeys(@AuthenticationPrincipal UUID userId) {
        UUID tenantId = com.taskforge.tenant.TenantContextHolder.getTenantId();
        return apiKeyService.listApiKeys(tenantId);
    }

    /**
     * Revoke an API key permanently.
     *
     * <p>RBAC: ADMIN only (per RBAC.md — "Create / revoke API keys → ADMIN").
     * Revocation is permanent — there is no un-revoke endpoint.
     */
    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void revokeApiKey(@PathVariable UUID keyId,
                             @AuthenticationPrincipal UUID userId) {
        UUID tenantId = com.taskforge.tenant.TenantContextHolder.getTenantId();
        apiKeyService.revokeApiKey(tenantId, keyId);
    }
}

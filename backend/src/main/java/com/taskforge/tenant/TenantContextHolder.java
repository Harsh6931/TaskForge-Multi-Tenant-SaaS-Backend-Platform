package com.taskforge.tenant;

import java.util.UUID;

/**
 * TenantContextHolder — Thread-local storage for the current request's tenant ID.
 *
 * Why ThreadLocal?
 *   Each HTTP request is handled on a dedicated thread from Spring's thread pool.
 *   ThreadLocal gives each thread its own isolated copy of the variable, so tenant A's
 *   request can never see tenant B's tenant_id — even if both requests are processed
 *   simultaneously on different threads.
 *
 * Lifecycle (managed by TenantFilter):
 *   SET  → TenantFilter.doFilterInternal() — before handing off to the next filter
 *   GET  → TenantConnectionInterceptor / services that need the tenant ID
 *   CLEAR → TenantFilter.doFilterInternal() finally block — CRITICAL to prevent
 *           thread-pool leakage where a reused thread still holds a stale tenant ID.
 */
public final class TenantContextHolder {

    // InheritableThreadLocal so that async tasks spawned from a request thread
    // automatically inherit the tenant context (e.g., @Async methods).
    private static final InheritableThreadLocal<UUID> TENANT_ID_HOLDER =
            new InheritableThreadLocal<>();

    // Utility class — no instantiation
    private TenantContextHolder() {}

    /**
     * Store the current tenant ID for this thread.
     * Called by TenantFilter at the start of every request.
     */
    public static void setTenantId(UUID tenantId) {
        TENANT_ID_HOLDER.set(tenantId);
    }

    /**
     * Retrieve the current tenant ID.
     * Returns null if no tenant context has been set (e.g., public endpoints).
     */
    public static UUID getTenantId() {
        return TENANT_ID_HOLDER.get();
    }

    /**
     * Remove the tenant ID from this thread.
     * MUST be called in the finally block of TenantFilter to prevent thread-pool leakage:
     * if a thread is reused (pool), the old tenant ID would bleed into the next request.
     */
    public static void clear() {
        TENANT_ID_HOLDER.remove();
    }
}

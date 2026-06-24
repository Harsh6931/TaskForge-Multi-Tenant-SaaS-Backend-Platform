package com.taskforge.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * TenantFilter — Extracts the tenant ID from each HTTP request and stores it in
 * TenantContextHolder, making it available to the downstream TenantConnectionInterceptor
 * which injects it into the PostgreSQL session variable {@code app.current_tenant_id}.
 *
 * <p><b>Request flow:</b>
 * <pre>
 *   HTTP Request
 *       │
 *       ▼
 *   TenantFilter (this class)
 *       │  → reads X-Tenant-ID header (Phase 2 will replace this with JWT claim)
 *       │  → calls TenantContextHolder.setTenantId(uuid)
 *       ▼
 *   Downstream filters / DispatcherServlet
 *       │
 *       ▼
 *   TenantConnectionInterceptor
 *       │  → runs SET LOCAL app.current_tenant_id = '<uuid>' on the DB connection
 *       ▼
 *   JPA / Repository
 *       │  → RLS policy reads session variable, filters rows automatically
 * </pre>
 *
 * <p><b>Phase 2 note:</b> The {@code X-Tenant-ID} header is a placeholder for local
 * development and testing. In Phase 2, the JWT authentication filter will run BEFORE
 * this filter and populate the tenant ID from the token's claims instead.
 *
 * <p><b>Thread-pool safety:</b> The {@code finally} block that calls
 * {@code TenantContextHolder.clear()} is CRITICAL. Without it, a pooled thread that
 * handles a second request would still hold the previous request's tenant ID — causing
 * data leakage between tenants.
 *
 * <p>Extends {@link OncePerRequestFilter} to guarantee exactly one execution per
 * request, even with Servlet forward/include dispatching.
 */
@Slf4j
public class TenantFilter extends OncePerRequestFilter {

    /**
     * HTTP header name used during Phase 1 development.
     * Phase 2 will replace header-based extraction with JWT claim extraction.
     */
    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String tenantIdHeader = request.getHeader(TENANT_HEADER);

        try {
            if (tenantIdHeader != null && !tenantIdHeader.isBlank()) {
                UUID tenantId = UUID.fromString(tenantIdHeader);
                TenantContextHolder.setTenantId(tenantId);
                log.debug("Tenant context set: tenantId={}, path={}",
                        tenantId, request.getRequestURI());
            } else {
                // Public endpoints (e.g., /auth/signup, /auth/login, /actuator/health)
                // don't require a tenant context. RLS will block any DB access that
                // tries to read tenant-scoped data without the session variable set.
                log.debug("No X-Tenant-ID header for path={} — tenant context not set",
                        request.getRequestURI());
            }

            filterChain.doFilter(request, response);

        } catch (IllegalArgumentException e) {
            // Malformed UUID in the header — reject immediately
            log.warn("Invalid X-Tenant-ID header value='{}' for path={}",
                    tenantIdHeader, request.getRequestURI());
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid X-Tenant-ID header: must be a valid UUID");

        } finally {
            // ── CRITICAL ─────────────────────────────────────────────────────────
            // Always clear the ThreadLocal, regardless of success or exception.
            // Failure to do this causes the tenant ID to persist on the thread when
            // it is returned to the pool, potentially leaking data to the next request.
            TenantContextHolder.clear();
            log.debug("Tenant context cleared for path={}", request.getRequestURI());
        }
    }
}

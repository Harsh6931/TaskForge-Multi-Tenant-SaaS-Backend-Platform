package com.taskforge.auth;

import com.taskforge.tenant.TenantContextHolder;
import com.taskforge.user.entity.TenantUserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JwtAuthenticationFilter — per-request JWT validation gatekeeper.
 *
 * <p>Sits inside Spring Security's filter chain, running BEFORE
 * {@code UsernamePasswordAuthenticationFilter}. Its job is to:
 * <ol>
 *   <li>Extract the Bearer token from the {@code Authorization} header</li>
 *   <li>Validate the token's signature and expiry via {@link JwtService}</li>
 *   <li>Populate {@code SecurityContextHolder} with the user's identity and role</li>
 *   <li>Set {@code TenantContextHolder} with the tenant ID from the token claims,
 *       so the downstream {@code TenantConnectionInterceptor} can run
 *       {@code SET LOCAL app.current_tenant_id = ?} on the DB connection</li>
 * </ol>
 *
 * <p><b>Interaction with TenantFilter (Phase 1 component):</b>
 * The Phase 1 {@link com.taskforge.tenant.TenantFilter} reads {@code X-Tenant-ID}
 * from the HTTP header. In Phase 2, this filter runs INSIDE Spring Security's chain
 * and sets {@code TenantContextHolder} from the JWT before any business logic runs.
 * The external {@code TenantFilter} (registered via {@code FilterRegistrationBean})
 * still executes but finds the context already set and skips — it only overwrites
 * if the header is present (which authenticated API clients won't send).
 * Both filters clear the holder in their {@code finally} blocks — the second clear
 * on an already-cleared value is a safe no-op.
 *
 * <p><b>Skip logic:</b>
 * If there is no {@code Authorization: Bearer} header, the filter does nothing and
 * passes the request along. Spring Security will then enforce {@code authenticated()}
 * rules on protected endpoints — a 401 will be returned by the
 * {@code AuthenticationEntryPoint} for routes that require auth.
 *
 * <p><b>Error handling:</b>
 * If the token is present but invalid (expired, tampered, malformed), we return a
 * 401 JSON error directly from this filter — before the request reaches any controller.
 * We do NOT call {@code filterChain.doFilter()} in this case.
 *
 * <p><b>Why {@code OncePerRequestFilter}?</b>
 * Guarantees exactly one execution per request, even with Servlet
 * forward/include dispatching — preventing double-authentication.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // ── 1. Skip if no Bearer token present ───────────────────────────────
        // Public endpoints (/auth/signup, /auth/login, /auth/refresh, /actuator/health)
        // will have no Authorization header — let them through. Spring Security's
        // authorizeHttpRequests rules will decide whether the route is accessible without auth.
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            // ── 2. Parse and validate the JWT ─────────────────────────────────
            // JwtService.parseAccessToken() throws JwtException subtypes on:
            //   - Signature mismatch (token tampered)
            //   - Expiry (token past its 'exp' claim)
            //   - Malformed token (not a valid JWT structure)
            Claims claims = jwtService.parseAccessToken(token);

            UUID userId   = jwtService.extractUserId(claims);
            UUID tenantId = jwtService.extractTenantId(claims);  // may be null (fresh signup, no tenant yet)
            TenantUserRole role = jwtService.extractRole(claims); // may be null

            // ── 3. Populate Spring SecurityContext ────────────────────────────
            // The principal is the userId (UUID) — controllers access it via
            // @AuthenticationPrincipal UUID userId.
            // Authorities encode the user's RBAC role as "ROLE_ADMIN" etc., which is what
            // @PreAuthorize("hasRole('ADMIN')") checks against.
            List<SimpleGrantedAuthority> authorities = role != null
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                    : List.of();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // ── 4. Set tenant context for RLS ─────────────────────────────────
            // TenantConnectionInterceptor reads this value before every DB transaction
            // and runs: SET LOCAL app.current_tenant_id = '<tenantId>'
            // If tenantId is null (user not yet in any tenant), no context is set —
            // RLS will block any attempt to read tenant-scoped tables, which is correct.
            if (tenantId != null) {
                TenantContextHolder.setTenantId(tenantId);
                log.debug("JWT auth OK — userId={} tenantId={} role={} path={}",
                        userId, tenantId, role, request.getRequestURI());
            } else {
                log.debug("JWT auth OK — userId={} no tenant (fresh user) path={}",
                        userId, request.getRequestURI());
            }

            filterChain.doFilter(request, response);

        } catch (JwtException ex) {
            // ── Token present but invalid — reject immediately ─────────────────
            // Do NOT call filterChain.doFilter() — stop the request here.
            log.warn("Invalid JWT token for path={} reason={}", request.getRequestURI(), ex.getMessage());
            sendUnauthorizedError(response, "Invalid or expired token");

        } finally {
            // ── 5. Always clear the SecurityContext and tenant context ─────────
            // The SecurityContextHolder is request-scoped via SecurityContextPersistenceFilter,
            // but clearing it explicitly here is good defensive hygiene.
            // TenantContextHolder MUST be cleared to prevent thread-pool leakage —
            // if this thread is reused, the next request must not inherit this tenant ID.
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    /**
     * Writes a minimal JSON 401 response directly to the response stream.
     *
     * <p>We write JSON manually here instead of throwing an exception because we're
     * in a filter, outside Spring MVC's exception handling. The {@link com.taskforge.common.exception.GlobalExceptionHandler}
     * only catches exceptions from controllers — it doesn't intercept filter-level errors.
     *
     * <p>The response format matches our RFC 7807 {@code ProblemDetail} shape used
     * by the {@code GlobalExceptionHandler} so clients get a consistent error structure.
     */
    private void sendUnauthorizedError(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {
                  "type": "about:blank",
                  "title": "Unauthorized",
                  "status": 401,
                  "detail": "%s"
                }
                """.formatted(detail));
    }
}

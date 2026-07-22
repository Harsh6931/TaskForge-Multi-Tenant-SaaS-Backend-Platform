package com.taskforge.auth;

import com.taskforge.auth.entity.ApiKey;
import com.taskforge.tenant.TenantContextHolder;
import com.taskforge.user.entity.TenantUserRole;
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
import java.util.Optional;

/**
 * ApiKeyAuthenticationFilter — authenticates requests that carry an API key
 * instead of a JWT Bearer token.
 *
 * <p><b>Header format:</b> {@code Authorization: ApiKey tf_<32-hex-chars>}
 *
 * <p><b>When this filter runs:</b>
 * Registered BEFORE {@link JwtAuthenticationFilter} in the Security filter chain.
 * This ordering means:
 * <ol>
 *   <li>If the header starts with {@code ApiKey } → this filter handles auth</li>
 *   <li>If the header starts with {@code Bearer } → JwtAuthenticationFilter handles auth</li>
 *   <li>If no Authorization header → both filters pass through; Security rules decide</li>
 * </ol>
 *
 * <p><b>Principal set in SecurityContext:</b>
 * API keys are tenant-scoped, not user-scoped — there is no userId.
 * The principal is set to the tenantId (UUID) string, and the authority is
 * {@code ROLE_API_KEY}. Controllers that need to distinguish API key vs. user
 * auth can check for this role.
 *
 * <p><b>Tenant context for RLS:</b>
 * The tenantId from the ApiKey entity is set in {@link TenantContextHolder},
 * activating RLS exactly as with JWT auth.
 *
 * <p><b>Use cases:</b>
 * B2B integrations, CI/CD pipelines, Zapier/webhook automation — anything
 * that needs programmatic access without a user session.
 */
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_PREFIX = "ApiKey ";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // Only handle "ApiKey ..." headers — leave "Bearer ..." and absent headers alone
        if (authHeader == null || !authHeader.startsWith(API_KEY_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = authHeader.substring(API_KEY_PREFIX.length()).trim();

        try {
            Optional<ApiKey> keyOpt = apiKeyService.validateAndTouch(rawKey);

            if (keyOpt.isEmpty()) {
                log.warn("Invalid or revoked API key for path={}", request.getRequestURI());
                sendUnauthorizedError(response, "Invalid or revoked API key");
                return;
            }

            ApiKey apiKey = keyOpt.get();

            // Set SecurityContext — principal = tenantId string, authority = ROLE_API_KEY
            // API keys authenticate the tenant workspace, not a specific user
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_API_KEY"),
                    // Also grant ROLE_ADMIN so API key requests can call admin-scoped endpoints
                    new SimpleGrantedAuthority("ROLE_" + TenantUserRole.ADMIN.name())
            );

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(apiKey.getTenantId(), null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Set tenant context for RLS
            TenantContextHolder.setTenantId(apiKey.getTenantId());

            log.debug("API key auth OK — tenantId={} keyId={} path={}",
                    apiKey.getTenantId(), apiKey.getId(), request.getRequestURI());

            filterChain.doFilter(request, response);

        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

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

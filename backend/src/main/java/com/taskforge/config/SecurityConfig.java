package com.taskforge.config;

import com.taskforge.auth.ApiKeyAuthenticationFilter;
import com.taskforge.auth.ApiKeyService;
import com.taskforge.auth.JwtAuthenticationFilter;
import com.taskforge.auth.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityConfig — Phase 2: Full JWT authentication and RBAC configuration.
 *
 * <p><b>What changed from Phase 1:</b>
 * The {@code permitAll()} placeholder is replaced with:
 * <ul>
 *   <li>A {@link JwtAuthenticationFilter} registered before Spring Security's default
 *       {@link UsernamePasswordAuthenticationFilter}</li>
 *   <li>Explicit public/protected route rules via {@code authorizeHttpRequests}</li>
 *   <li>A {@link PasswordEncoder} bean (BCrypt) used by {@link com.taskforge.auth.AuthService}</li>
 *   <li>{@link EnableMethodSecurity} — activates {@code @PreAuthorize} on controller and
 *       service methods for fine-grained RBAC (Goal 9)</li>
 * </ul>
 *
 * <p><b>Filter chain order (Phase 2):</b>
 * <pre>
 *   [Servlet filter chain — registered via FilterRegistrationBean]
 *     Order 5  → TenantFilter (Phase 1 — reads X-Tenant-ID header as fallback)
 *     Order 10 → Spring Security FilterChainProxy (this config)
 *                  └─ JwtAuthenticationFilter  ← our filter, runs first inside Security chain
 *                  └─ UsernamePasswordAuthenticationFilter (Spring default — skipped, no form login)
 *                  └─ ExceptionTranslationFilter
 *                  └─ AuthorizationFilter (enforces authorizeHttpRequests rules)
 * </pre>
 *
 * <p><b>Why addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)?</b>
 * Spring Security's filter chain has a fixed internal order. By placing our JWT filter
 * before the default username/password filter, we ensure the SecurityContext is populated
 * with the JWT principal BEFORE Spring's authorization logic runs.
 *
 * <p><b>@EnableMethodSecurity:</b>
 * Enables {@code @PreAuthorize("hasRole('ADMIN')")} on individual controller methods.
 * This is the RBAC mechanism — not all routes are listed in {@code authorizeHttpRequests}
 * (that would be too coarse), but each sensitive endpoint gets its own annotation.
 * Phase 3 will use this on project/task endpoints. Phase 2 uses it on audit log and
 * admin-only endpoints.
 *
 * <p><b>PasswordEncoder:</b>
 * Defined here as a {@code @Bean} so it can be injected into {@link com.taskforge.auth.AuthService}
 * without creating a circular dependency (AuthService → PasswordEncoder → SecurityConfig would
 * be circular if SecurityConfig also depended on AuthService). The encoder is stateless so a
 * single bean is fine.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService    jwtService;
    private final ApiKeyService apiKeyService;

    // ── Security filter chain ─────────────────────────────────────────────────

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless REST API — no CSRF, no session, no form login, no HTTP Basic
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)

            // ── Route access rules ────────────────────────────────────────────
            // Ordered from most-specific to least-specific.
            // Fine-grained RBAC (ADMIN vs MANAGER vs MEMBER etc.) is handled per-endpoint
            // via @PreAuthorize annotations, not here (would be too coarse-grained here).
            .authorizeHttpRequests(auth -> auth
                // Public auth endpoints — no token required
                .requestMatchers(
                    "/auth/signup",
                    "/auth/login",
                    "/auth/refresh"
                ).permitAll()

                // Actuator health check — no token required (used by Docker/load balancer)
                .requestMatchers("/actuator/health").permitAll()

                // Everything else requires a valid JWT
                // The JwtAuthenticationFilter populates the SecurityContext;
                // if the token is missing or invalid, this rule returns 401.
                .anyRequest().authenticated()
            )

            // ── Register our auth filters ─────────────────────────────────────
            // ApiKeyAuthenticationFilter runs first: handles "Authorization: ApiKey ..." headers.
            // JwtAuthenticationFilter runs second:   handles "Authorization: Bearer ..." headers.
            // Each filter short-circuits if its own prefix is not present.
            .addFilterBefore(apiKeyAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter(), ApiKeyAuthenticationFilter.class);

        return http.build();
    }

    // ── Beans ─────────────────────────────────────────────────────────────────

    /**
     * Creates the {@link JwtAuthenticationFilter} bean.
     *
     * <p>We instantiate it explicitly rather than using @Component on the filter
     * to avoid Spring Boot's {@link org.springframework.boot.web.servlet.FilterRegistrationBean}
     * auto-registering it in the Servlet filter chain (which would cause it to run twice —
     * once in the Servlet chain and once inside the Security chain). Explicit instantiation
     * here means Spring Security manages it; Boot does not auto-register it.
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }

    /**
     * Creates the {@link ApiKeyAuthenticationFilter} bean.
     * Same pattern as jwtAuthenticationFilter — explicit instantiation prevents
     * Spring Boot from auto-registering it in the Servlet chain (double execution).
     */
    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter() {
        return new ApiKeyAuthenticationFilter(apiKeyService);
    }

    /**
     * BCrypt password encoder — the industry-standard algorithm for password hashing.
     *
     * <p><b>Why BCrypt?</b>
     * <ul>
     *   <li>Adaptive work factor (rounds) — can be increased as hardware gets faster</li>
     *   <li>Built-in salt — each hash is unique even for identical passwords</li>
     *   <li>Intentionally slow — makes brute-force and rainbow-table attacks expensive</li>
     * </ul>
     *
     * <p>Default strength is 10 rounds (2^10 = 1024 iterations) — adequate for most
     * use cases. Increase to 12 for higher-security production environments.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}


package com.taskforge.config;

import com.taskforge.tenant.TenantFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// this class just tell Spring Boot that TenantFilter exists, where it should run, and when it should run.
/**
 * TenantConfig — Registers the TenantFilter in the Spring Boot filter chain.
 *
 * <p><b>Why a dedicated configuration class?</b>
 * Using {@link FilterRegistrationBean} gives us explicit control over:
 * <ul>
 *   <li><b>Order</b> — the filter must run BEFORE Spring Security's filter chain so the
 *       tenant context is available when security decisions are made. However, in Phase 2
 *       this will be adjusted: the JWT authentication filter will run first (to decode the
 *       token and extract the tenant claim), then TenantFilter will read from the security
 *       context rather than from the raw header.</li>
 *   <li><b>URL patterns</b> — we apply the filter to all URLs ({@code /*}) so no request
 *       can slip through without a tenant context check.</li>
 * </ul>
 *
 * <p><b>Filter execution order:</b>
 * <pre>
 *   Order 1 (HIGHEST_PRECEDENCE + 5)  → TenantFilter (this)
 *   Order 10 (set in application.yml) → Spring Security FilterChainProxy
 *   Order N                           → Other application filters
 * </pre>
 */
@Configuration
public class TenantConfig {

    /**
     * Registers TenantFilter with the highest priority so it runs before
     * Spring Security evaluates the request.
     *
     * <p>Phase 2 change: Once JWT auth is in place, set this order AFTER the
     * JWT filter so that {@code TenantFilter} can read the tenant ID from the
     * {@code SecurityContext} (already populated by JWT parsing) instead of from
     * the raw header.
     */
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilterRegistration() {
        FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TenantFilter());
        registration.addUrlPatterns("/*");
        // Run just before Spring Security (which is at order 10 per application.yml)
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.setName("tenantFilter");
        return registration;
    }
}

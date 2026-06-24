package com.taskforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Minimal Spring Security configuration
 *
 * <p><b>Current state (Phase 1):</b>
 * Spring Security is on the classpath (required by Phase 2), but full JWT
 * authentication is not implemented yet. This configuration temporarily permits
 * all requests so that the application starts and Flyway migrations run cleanly.
 *
 * <p><b>Phase 2 change:</b>
 * Replace {@code .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())} with
 * proper JWT authentication rules and role-based access control.
 *
 * <p>We explicitly disable:
 * <ul>
 *   <li><b>CSRF</b> — REST APIs using stateless JWT don't need CSRF protection.</li>
 *   <li><b>Session creation</b> — JWT is stateless; no server-side HTTP session needed.</li>
 *   <li><b>Form login</b> — Not used; auth is via {@code /auth/login} REST endpoint.</li>
 *   <li><b>HTTP Basic</b> — Not used; auth is via Bearer token in Phase 2.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless REST API with JWT doesn't need it
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless — no HTTP session; every request must carry credentials (JWT in Phase 2)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Disable form-based login (we use REST endpoints)
            .formLogin(AbstractHttpConfigurer::disable)

            // Disable HTTP Basic auth
            .httpBasic(AbstractHttpConfigurer::disable)

            // ── Phase 1: permit all so the app boots without JWT auth ──────────
            // TODO Phase 2: Replace with JWT filter + role-based rules
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());   // temprory allow all request while building

        return http.build();
    }
}

package com.taskforge.auth;

import com.taskforge.auth.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * AuthController — HTTP translation layer for authentication endpoints.
 *
 * <p><b>Design rule — keep this thin:</b>
 * Every method here does exactly three things:
 * <ol>
 *   <li>Extract input from the HTTP request (already validated by {@code @Valid})</li>
 *   <li>Delegate to {@link AuthService}</li>
 *   <li>Return the service result (Spring auto-serialises to JSON)</li>
 * </ol>
 * No business logic lives here. No try/catch here — exceptions bubble up to
 * {@link com.taskforge.common.exception.GlobalExceptionHandler}.
 *
 * <p><b>Base path:</b> {@code /auth}
 *
 * <p><b>Public endpoints (no JWT needed):</b>
 * {@code /auth/signup}, {@code /auth/login}, {@code /auth/refresh}
 * — permitted in {@code SecurityConfig.requestMatchers(...)}.
 *
 * <p><b>Protected endpoints (JWT required):</b>
 * {@code /auth/logout}, {@code /auth/switch-tenant/{tenantId}}
 * — the {@code JwtAuthenticationFilter} (Goal 6) will enforce authentication
 * and populate {@code @AuthenticationPrincipal} with the userId UUID.
 *
 * <p><b>@AuthenticationPrincipal:</b>
 * Spring Security's mechanism to inject the current authenticated principal into a
 * controller parameter. In Goal 6, {@code JwtAuthenticationFilter} sets the principal
 * to the {@code userId} (UUID) extracted from the JWT claims.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Register a new user account.
     *
     * <p>API contract: {@code POST /auth/signup → 201 Created}
     * <pre>
     * Request:  { "email": "...", "password": "...", "fullName": "..." }
     * Response: { "accessToken": "...", "refreshToken": "...", "tenants": [] }
     * </pre>
     *
     * <p>Returns 400 with field error map if validation fails (handled by GlobalExceptionHandler).
     * Returns 409 if the email is already registered.
     */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    /**
     * Authenticate an existing user.
     *
     * <p>API contract: {@code POST /auth/login → 200 OK}
     * <pre>
     * Request:  { "email": "...", "password": "..." }
     * Response: { "accessToken": "...", "refreshToken": "...",
     *             "tenants": [{ "tenantId", "name", "slug", "role" }] }
     * </pre>
     *
     * <p>Returns 401 if credentials are invalid (generic message — no enumeration possible).
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Exchange a valid refresh token for a new access + refresh token pair.
     *
     * <p>API contract: {@code POST /auth/refresh → 200 OK}
     * <pre>
     * Request:  { "refreshToken": "..." }
     * Response: { "accessToken": "...", "refreshToken": "..." }
     * </pre>
     *
     * <p>The old refresh token is revoked immediately on this call (rotation).
     * Returns 401 if the refresh token is unknown, revoked, or expired.
     */
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * Revoke all refresh tokens for the authenticated user (logout-everywhere).
     *
     * <p>API contract: {@code POST /auth/logout → 204 No Content}
     *
     * <p>Requires a valid JWT in {@code Authorization: Bearer <token>}.
     * Access tokens are short-lived (15 min) and will naturally expire —
     * this call only revokes server-side refresh tokens.
     *
     * <p>{@code @AuthenticationPrincipal UUID userId} is injected by Spring Security
     * after the JwtAuthenticationFilter (Goal 6) populates the SecurityContext
     * with a principal of type UUID.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal UUID userId) {
        authService.logout(userId);
    }

    /**
     * Switch the authenticated user's active workspace (tenant).
     *
     * <p>API contract: {@code POST /auth/switch-tenant/{tenantId} → 200 OK}
     * <pre>
     * Response: { "accessToken": "...", "refreshToken": "...",
     *             "tenants": [...] }   ← full updated list
     * </pre>
     *
     * <p>Requires a valid JWT. Returns 403 if the user is not a member of the
     * requested tenant. The new access token is scoped to {@code tenantId} with the
     * user's role in that workspace — all subsequent API calls with this token will
     * have their RLS context set to the new tenant automatically.
     *
     * @param tenantId the UUID of the tenant workspace to switch to
     * @param userId   injected from the JWT by Spring Security
     */
    @PostMapping("/switch-tenant/{tenantId}")
    public AuthResponse switchTenant(@PathVariable UUID tenantId,
                                     @AuthenticationPrincipal UUID userId) {
        return authService.switchTenant(userId, tenantId);
    }
}

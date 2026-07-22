package com.taskforge.auth.dto;

import java.util.List;

/**
 * AuthResponse — response body for signup, login, and switch-tenant endpoints.
 *
 * <p>Per API contract:
 * <pre>
 * POST /auth/signup  → { accessToken, refreshToken, tenants: [] }
 * POST /auth/login   → { accessToken, refreshToken, tenants: [...] }
 * POST /auth/switch-tenant/{id} → { accessToken, refreshToken, tenants: [...] }
 * </pre>
 *
 * <p><b>Security note on tokens in response body:</b>
 * Returning tokens in the JSON body (not as cookies) is a common pattern for
 * mobile and SPA clients that manage their own storage. The frontend stores the
 * access token in memory and the refresh token in HttpOnly cookie or secure storage.
 * This project uses response body for simplicity; in production consider HttpOnly cookies
 * for the refresh token to prevent XSS theft.
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        List<TenantSummary> tenants
) {}

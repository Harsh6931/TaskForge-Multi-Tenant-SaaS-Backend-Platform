package com.taskforge.auth.dto;

/**
 * RefreshResponse — response body for {@code POST /auth/refresh}.
 *
 * <p>Returns both a new access token AND a new refresh token (rotation).
 * The old refresh token is revoked server-side the moment this is called.
 */
public record RefreshResponse(
        String accessToken,
        String refreshToken
) {}

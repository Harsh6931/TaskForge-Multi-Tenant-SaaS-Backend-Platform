package com.taskforge.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RefreshRequest — request body for {@code POST /auth/refresh}.
 */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {}

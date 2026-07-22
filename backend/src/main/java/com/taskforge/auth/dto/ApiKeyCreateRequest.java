package com.taskforge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api-keys}.
 */
public record ApiKeyCreateRequest(
        @NotBlank(message = "Key name is required")
        @Size(max = 255, message = "Key name must be 255 characters or fewer")
        String name
) {}

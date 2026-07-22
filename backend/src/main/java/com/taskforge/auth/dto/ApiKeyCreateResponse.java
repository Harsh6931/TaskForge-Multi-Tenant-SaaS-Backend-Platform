package com.taskforge.auth.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /api-keys} — shown ONCE.
 *
 * <p>The {@code rawKey} is the plaintext API key returned exactly once at creation.
 * It is NEVER stored server-side. The client must copy it immediately.
 * Subsequent GET /api-keys responses use {@link ApiKeyResponse} which shows only
 * the masked key name and metadata.
 */
public record ApiKeyCreateResponse(
        UUID id,
        String name,
        String rawKey  // shown once — never stored, never returned again
) {}

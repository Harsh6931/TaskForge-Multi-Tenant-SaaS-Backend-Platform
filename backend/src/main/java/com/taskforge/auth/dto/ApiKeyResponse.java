package com.taskforge.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code GET /api-keys} — list view (no raw key, no hash).
 *
 * <p>Only metadata is exposed. The key value itself is never returned after creation.
 * {@code lastUsedAt} is useful for identifying stale or suspicious keys.
 */
public record ApiKeyResponse(
        UUID id,
        String name,
        Instant createdAt,
        Instant lastUsedAt  // null until first use
) {}

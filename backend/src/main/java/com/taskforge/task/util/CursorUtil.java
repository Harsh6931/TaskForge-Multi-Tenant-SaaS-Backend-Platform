package com.taskforge.task.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * CursorUtil — encodes and decodes opaque cursor tokens for cursor-based pagination.
 *
 * <p><b>Cursor format (internal):</b>
 * {@code <createdAt_epochMillis>:<taskId_uuid>}
 * Example: {@code 1721234567890:550e8400-e29b-41d4-a716-446655440000}
 *
 * <p><b>Cursor format (wire):</b>
 * URL-safe Base64 of the internal format.
 * Example: {@code MTcyMTIzNDU2Nzg5MDo1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDA=}
 *
 * <p><b>Why Base64?</b>
 * The raw format contains a colon and hyphens which could be misinterpreted in URLs.
 * Base64 URL-encoding produces a token that is safe to pass as a query parameter
 * without percent-encoding.
 *
 * <p><b>Why composite (createdAt, id)?</b>
 * Using only {@code createdAt} is unstable — multiple tasks can have the same timestamp
 * (millisecond resolution). Adding {@code id} makes the cursor globally unique.
 * The keyset query uses {@code (createdAt, id) < (cursorTs, cursorId)} which the
 * {@code idx_tasks_tenant_project} composite index can satisfy efficiently.
 */
public final class CursorUtil {

    private CursorUtil() {} // Utility class — no instantiation

    /**
     * Encodes a {@code (createdAt, id)} pair into a URL-safe Base64 cursor string.
     *
     * @param createdAt the creation timestamp of the last task on the current page
     * @param id        the UUID of the last task on the current page
     * @return opaque cursor string safe for use as a URL query parameter
     */
    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toEpochMilli() + ":" + id.toString();
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor string back into a {@link CursorValues} record.
     *
     * @param cursor the opaque cursor string returned by a previous page response
     * @return the decoded {@code (createdAt, id)} pair
     * @throws IllegalArgumentException if the cursor is malformed or cannot be decoded
     */
    public static CursorValues decode(String cursor) {
        try {
            String raw = new String(
                Base64.getUrlDecoder().decode(cursor),
                StandardCharsets.UTF_8
            );
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor format");
            }
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
            UUID id = UUID.fromString(parts[1]);
            return new CursorValues(createdAt, id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed pagination cursor: " + cursor, e);
        }
    }

    /**
     * Holds the decoded components of a pagination cursor.
     *
     * @param createdAt the timestamp of the last seen task
     * @param id        the UUID of the last seen task
     */
    public record CursorValues(Instant createdAt, UUID id) {}
}

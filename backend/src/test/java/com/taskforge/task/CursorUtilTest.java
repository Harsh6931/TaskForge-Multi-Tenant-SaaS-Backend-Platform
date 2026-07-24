package com.taskforge.task;

import com.taskforge.task.util.CursorUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * CursorUtilTest — pure unit tests for {@link CursorUtil}.
 * No Spring context or mocks needed — purely tests the encoding/decoding logic.
 */
class CursorUtilTest {

    @Test
    @DisplayName("encode/decode round-trip: decoded values match originals")
    void encode_decode_roundTripProducesOriginalValues() {
        Instant createdAt = Instant.parse("2024-07-15T10:30:00Z");
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        String cursor = CursorUtil.encode(createdAt, id);
        CursorUtil.CursorValues decoded = CursorUtil.decode(cursor);

        assertThat(decoded.createdAt()).isEqualTo(createdAt);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("encode: produces URL-safe Base64 (no + or / characters)")
    void encode_producesUrlSafeBase64() {
        String cursor = CursorUtil.encode(Instant.now(), UUID.randomUUID());
        // URL-safe Base64 must not contain '+' or '/'
        assertThat(cursor).doesNotContain("+", "/");
    }

    @Test
    @DisplayName("encode: two different tasks produce different cursors")
    void encode_differentInputsProduceDifferentCursors() {
        Instant now = Instant.now();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        String cursor1 = CursorUtil.encode(now, id1);
        String cursor2 = CursorUtil.encode(now, id2);

        assertThat(cursor1).isNotEqualTo(cursor2);
    }

    @Test
    @DisplayName("decode: invalid cursor throws IllegalArgumentException")
    void decode_invalidCursor_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> CursorUtil.decode("not-a-valid-cursor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed pagination cursor");
    }

    @Test
    @DisplayName("decode: empty string throws IllegalArgumentException")
    void decode_emptyString_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> CursorUtil.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

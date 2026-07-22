package com.taskforge.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * TokenHashUtilTest — unit test for SHA-256 hashing utility.
 */
@DisplayName("TokenHashUtil — SHA-256 hashing")
class TokenHashUtilTest {

    @Test
    @DisplayName("sha256: produces a 64-character lowercase hex string")
    void sha256_produces64CharHex() {
        String hash = TokenHashUtil.sha256(UUID.randomUUID().toString());
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("sha256: same input always produces same hash (deterministic)")
    void sha256_isDeterministic() {
        String input = "tf_550e8400e29b41d4a716446655440000";
        assertThat(TokenHashUtil.sha256(input)).isEqualTo(TokenHashUtil.sha256(input));
    }

    @Test
    @DisplayName("sha256: different inputs produce different hashes (no obvious collisions)")
    void sha256_differentInputsDifferentHashes() {
        String h1 = TokenHashUtil.sha256("tf_abc123");
        String h2 = TokenHashUtil.sha256("tf_abc124");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("sha256: known input matches known SHA-256 output (test vector)")
    void sha256_knownVector() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(TokenHashUtil.sha256("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}

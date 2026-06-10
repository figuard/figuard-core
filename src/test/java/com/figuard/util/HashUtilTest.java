package com.figuard.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashUtilTest {

    @AfterEach
    void resetPepper() {
        HashUtil.setPepper(null); // don't leak peppered state into other tests
    }

    @Test
    void tokenHash_falls_back_to_sha256_when_no_pepper() {
        HashUtil.setPepper(null);
        assertThat(HashUtil.isPepperSet()).isFalse();
        assertThat(HashUtil.tokenHash("st_abc")).isEqualTo(HashUtil.sha256("st_abc"));
    }

    @Test
    void tokenHash_uses_hmac_when_pepper_set() {
        HashUtil.setPepper("super-secret-pepper");
        assertThat(HashUtil.isPepperSet()).isTrue();
        // Peppered hash must differ from the plain SHA-256 — a DB-only breach can't verify it.
        assertThat(HashUtil.tokenHash("st_abc")).isNotEqualTo(HashUtil.sha256("st_abc"));
        assertThat(HashUtil.tokenHash("st_abc"))
            .isEqualTo(HashUtil.hmacSha256("st_abc", "super-secret-pepper"));
    }

    @Test
    void tokenHash_is_consistent_for_same_input() {
        HashUtil.setPepper("pep");
        assertThat(HashUtil.tokenHash("key1")).isEqualTo(HashUtil.tokenHash("key1"));
    }

    @Test
    void different_pepper_yields_different_hash() {
        HashUtil.setPepper("pepper-a");
        String a = HashUtil.tokenHash("key1");
        HashUtil.setPepper("pepper-b");
        String b = HashUtil.tokenHash("key1");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blank_pepper_treated_as_unset() {
        HashUtil.setPepper("   ");
        assertThat(HashUtil.isPepperSet()).isFalse();
        assertThat(HashUtil.tokenHash("x")).isEqualTo(HashUtil.sha256("x"));
    }
}

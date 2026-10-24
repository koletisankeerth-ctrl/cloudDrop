package com.clouddrop.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    void matchesKnownSha256Vector() {
        // standard test vector for SHA-256("abc")
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void isDeterministic_andAlways64HexChars() {
        assertThat(TokenHasher.sha256Hex("x")).isEqualTo(TokenHasher.sha256Hex("x")).hasSize(64);
        assertThat(TokenHasher.sha256Hex("x")).isNotEqualTo(TokenHasher.sha256Hex("y"));
    }
}

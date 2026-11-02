package com.jurislpo.juristrack.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TokensTest {

    private static final byte[] KEY_ONE = "first-secret-first-secret-first-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_TWO = "other-secret-other-secret-other-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void anHmacDependsOnTheSecretAndTheText() {
        String hash = Tokens.hmacSha256(KEY_ONE, "a@b.test:123456");
        assertThat(hash).hasSize(64).isEqualTo(Tokens.hmacSha256(KEY_ONE, "a@b.test:123456"));
        assertThat(hash).isNotEqualTo(Tokens.hmacSha256(KEY_TWO, "a@b.test:123456"))
                .isNotEqualTo(Tokens.hmacSha256(KEY_ONE, "a@b.test:123457"))
                .isNotEqualTo(Tokens.sha256("a@b.test:123456"));
    }
}

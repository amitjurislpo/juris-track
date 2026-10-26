package com.jurislpo.juristrack.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The reset command empties only a local database, unless it is explicitly allowed. */
class FlywayConfigTest {

    @Test
    void aLocalAppUrlMayBeReset() {
        assertThat(FlywayConfig.isResetAllowed("http://localhost:3006", false)).isTrue();
        assertThat(FlywayConfig.isResetAllowed("http://127.0.0.1:3008/", false)).isTrue();
    }

    @Test
    void anyOtherAppUrlNeedsTheAllowFlag() {
        assertThat(FlywayConfig.isResetAllowed("https://track.example.com", false)).isFalse();
        assertThat(FlywayConfig.isResetAllowed("http://localhost.example.com", false)).isFalse();
        assertThat(FlywayConfig.isResetAllowed(null, false)).isFalse();
        assertThat(FlywayConfig.isResetAllowed("https://track.example.com", true)).isTrue();
    }
}

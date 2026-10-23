package com.jurislpo.juristrack;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application clock with an {@link AdjustableClock} in every API test (inject it as {@code AdjustableClock}). */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }
}

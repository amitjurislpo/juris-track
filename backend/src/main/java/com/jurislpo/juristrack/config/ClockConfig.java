package com.jurislpo.juristrack.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of the time tracker: every "now" of a time event, of the current state and of the history comes from this bean
 * (UTC), never from {@code Instant.now()}, so tests can set and move the time.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

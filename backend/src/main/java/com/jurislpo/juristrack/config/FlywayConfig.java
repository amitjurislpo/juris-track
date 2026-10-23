package com.jurislpo.juristrack.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Flyway migrates on every start. For the {@code reset} command (development only: {@code juristrack.reset=true}) it first
 * empties the schema, and refuses unless the app URL is localhost or {@code juristrack.allow-database-reset=true}.
 */
@Configuration
public class FlywayConfig {

    @Bean
    FlywayMigrationStrategy resettableMigration(Environment environment, JuristrackProperties properties) {
        return flyway -> {
            if ("true".equals(environment.getProperty("juristrack.reset"))) {
                if (!isResetAllowed(properties.appUrl(), properties.allowDatabaseReset())) {
                    throw new IllegalStateException("Refusing to reset a database for " + properties.appUrl()
                            + ". Set juristrack.allow-database-reset=true if you really mean it.");
                }
                flyway.clean();
            }
            flyway.migrate();
        };
    }

    /** A reset is allowed for a local app URL (localhost / 127.0.0.1), or anywhere with the explicit allow flag. */
    static boolean isResetAllowed(String appUrl, boolean isExplicitlyAllowed) {
        boolean isLocal = appUrl != null && appUrl.matches("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?$");
        return isLocal || isExplicitlyAllowed;
    }
}

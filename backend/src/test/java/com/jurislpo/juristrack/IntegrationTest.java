package com.jurislpo.juristrack;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: one Postgres 16 container shared by every subclass (the Spring context is cached too),
 * schema from Flyway V1. Tests create the rows they need. Sign-in codes are the fixed {@value SignIn#CODE}; a reused refresh
 * token is treated as stolen at once (no grace period).
 */
@SpringBootTest(properties = {"juristrack.mail.host=", "juristrack.app-url=http://localhost:3007",
        "juristrack.security.jwt-secret=test-only-jwt-secret-0123456789abcdef0123456789", "juristrack.otp.fixed-code=" + SignIn.CODE,
        "juristrack.security.refresh-reuse-grace-seconds=0"})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = PostgresContainer.start();

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

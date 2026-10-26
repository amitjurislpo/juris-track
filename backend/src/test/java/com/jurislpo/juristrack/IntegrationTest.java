package com.jurislpo.juristrack;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: one Postgres 16 container shared by every subclass (the Spring context is cached too),
 * schema from the Flyway migrations, profile {@code dev} (there is no default profile). Tests create the rows they need. Sign-in codes
 * are the fixed {@value SignIn#CODE}; a reused refresh token is treated as stolen at once (no grace period). The application clock is
 * an {@link AdjustableClock} (time tracking). MockMvc requests come from 127.0.0.1, a trusted proxy, so a test chooses the client's
 * IP address with {@code X-Forwarded-For}. A pool connection is waited for at most 5 seconds (a thread that would need a second
 * connection fails fast instead of stalling the suite), and the cap on audit entries without a signed-in user is out of the way
 * (every test signs in from 127.0.0.1).
 */
@SpringBootTest(properties = {"juristrack.mail.host=", "juristrack.app-url=http://localhost:3006",
        "juristrack.security.jwt-secret=test-only-jwt-secret-0123456789abcdef0123456789", "juristrack.otp.fixed-code=" + SignIn.CODE,
        "juristrack.security.refresh-reuse-grace-seconds=0", "spring.datasource.hikari.connection-timeout=5000",
        "juristrack.audit.unauthenticated-entries-per-ip=1000000"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = PostgresContainer.start();

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}

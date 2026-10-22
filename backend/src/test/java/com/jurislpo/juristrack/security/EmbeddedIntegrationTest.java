package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.IntegrationTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need JurisOne's tokens accepted (embedded mode on, the test key pair's public key configured) and the
 * {@link ProbeController} endpoints. The standalone sign-in works in this context too. One Spring context shared by every subclass.
 */
@Import(ProbeController.class)
public abstract class EmbeddedIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void enableIntegration(DynamicPropertyRegistry registry) {
        registry.add("juristrack.integration.enabled", () -> "true");
        registry.add("juristrack.integration.issuer", () -> IntegrationTokens.ISSUER);
        registry.add("juristrack.integration.audience", () -> IntegrationTokens.AUDIENCE);
        registry.add("juristrack.integration.public-key", () -> IntegrationTokens.pemOf(IntegrationTokens.JURISONE_KEY_PAIR.getPublic()));
    }
}

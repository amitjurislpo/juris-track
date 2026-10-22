package com.jurislpo.juristrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings (application.properties and application-&lt;env&gt;.properties; secrets of sqe/uat/prd come from environment variables).
 * Sign-in and mail settings are checked by {@link EnvironmentGuard} and read by their features as those arrive.
 *
 * @param appUrl             the web app's public URL
 * @param frontendOrigin     the only origin allowed by CORS (the web app)
 * @param allowDatabaseReset lets the {@code reset} command empty a database whose app URL is not localhost
 * @param seed               what the {@code seed} command creates (see {@link Seed})
 * @param integration        running embedded in JurisOne (see {@link Integration})
 */
@ConfigurationProperties(prefix = "juristrack")
public record JuristrackProperties(String appUrl, String frontendOrigin, boolean allowDatabaseReset, Seed seed,
        Integration integration) {

    public JuristrackProperties {
        if (seed == null) seed = new Seed(null, false);
        if (integration == null) integration = new Integration(false, null, Integration.DEFAULT_AUDIENCE, null, Integration.DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /**
     * The {@code seed} command (idempotent).
     *
     * @param superAdminEmail the platform super admin, created if missing (required outside dev)
     * @param demo            also create the demo business (dev and sqe only; uat and prd refuse it)
     */
    public record Seed(String superAdminEmail, boolean demo) {}

    /**
     * Embedded mode: the JurisOne API is the gateway and sends a short-lived JWT signed with its private key
     * (see security.IntegrationTokenVerifier). Standalone sign-in works whether this is enabled or not.
     *
     * @param enabled            accept JurisOne's tokens
     * @param issuer             the expected {@code iss} claim (JurisOne's public URL)
     * @param audience           the expected {@code aud} claim ({@code juristrack})
     * @param publicKey          JurisOne's public key (PEM, RSA or EC); never a private key
     * @param clockSkewSeconds   tolerance for {@code exp} / {@code iat} between the two servers' clocks
     */
    public record Integration(boolean enabled, String issuer, String audience, String publicKey, int clockSkewSeconds) {

        static final String DEFAULT_AUDIENCE = "juristrack";
        static final int DEFAULT_CLOCK_SKEW_SECONDS = 30;

        public Integration {
            if (audience == null || audience.isBlank()) audience = DEFAULT_AUDIENCE;
            if (clockSkewSeconds < 0) clockSkewSeconds = DEFAULT_CLOCK_SKEW_SECONDS;
        }
    }
}

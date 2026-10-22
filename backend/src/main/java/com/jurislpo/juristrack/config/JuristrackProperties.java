package com.jurislpo.juristrack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings (application.properties and application-&lt;env&gt;.properties; secrets of sqe/uat/prd come from environment variables).
 * Sign-in and mail settings are also checked by {@link EnvironmentGuard}.
 *
 * @param appUrl             the web app's public URL
 * @param frontendOrigin     the only origin allowed by CORS and by the Origin check on writes (the web app)
 * @param allowDatabaseReset lets the {@code reset} command empty a database whose app URL is not localhost
 * @param seed               what the {@code seed} command creates (see {@link Seed})
 * @param integration        running embedded in JurisOne (see {@link Integration})
 * @param security           session tokens and cookies (see {@link Security})
 * @param otp                the emailed sign-in code (see {@link Otp})
 * @param mail               outgoing email (see {@link Mail})
 */
@ConfigurationProperties(prefix = "juristrack")
public record JuristrackProperties(String appUrl, String frontendOrigin, boolean allowDatabaseReset, Seed seed,
        Integration integration, Security security, Otp otp, Mail mail) {

    public JuristrackProperties {
        if (seed == null) seed = new Seed(null, false);
        if (integration == null) integration = new Integration(false, null, Integration.DEFAULT_AUDIENCE, null, Integration.DEFAULT_CLOCK_SKEW_SECONDS);
        if (security == null) security = new Security(null, 0, 0, 0, false, -1);
        if (otp == null) otp = new Otp(null, 0, 0, 0, 0, 0, 0);
        if (mail == null) mail = new Mail(null, 0, null, null, null);
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
     * @param clockSkewSeconds   tolerance for {@code exp} / {@code nbf} between the two servers' clocks
     */
    public record Integration(boolean enabled, String issuer, String audience, String publicKey, int clockSkewSeconds) {

        static final String DEFAULT_AUDIENCE = "juristrack";
        static final int DEFAULT_CLOCK_SKEW_SECONDS = 30;

        public Integration {
            if (audience == null || audience.isBlank()) audience = DEFAULT_AUDIENCE;
            if (clockSkewSeconds < 0) clockSkewSeconds = DEFAULT_CLOCK_SKEW_SECONDS;
        }
    }

    /**
     * The standalone session: a short-lived JWT access token ({@code jt_at}) and a rotating refresh token ({@code jt_rt}),
     * both HTTP-only {@code SameSite=Lax} cookies.
     *
     * @param jwtSecret                 signs the access tokens (HS256), at least 32 bytes
     * @param accessTokenTtlSeconds     lifetime of an access token (about 5 minutes; renewed silently)
     * @param sessionTtlDays            lifetime of a session with "keep me signed in"
     * @param shortSessionHours         lifetime of a session without it (the cookies then also end with the browser)
     * @param cookieSecure              send the cookies only over https (true wherever the app is served over https)
     * @param refreshReuseGraceSeconds  a refresh token used again within this many seconds is a parallel request, not a stolen copy
     */
    public record Security(String jwtSecret, int accessTokenTtlSeconds, int sessionTtlDays, int shortSessionHours, boolean cookieSecure,
            int refreshReuseGraceSeconds) {

        static final int DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 300;
        static final int DEFAULT_SESSION_TTL_DAYS = 30;
        static final int DEFAULT_SHORT_SESSION_HOURS = 12;
        static final int DEFAULT_REFRESH_REUSE_GRACE_SECONDS = 30;

        public Security {
            if (accessTokenTtlSeconds <= 0) accessTokenTtlSeconds = DEFAULT_ACCESS_TOKEN_TTL_SECONDS;
            if (sessionTtlDays <= 0) sessionTtlDays = DEFAULT_SESSION_TTL_DAYS;
            if (shortSessionHours <= 0) shortSessionHours = DEFAULT_SHORT_SESSION_HOURS;
            if (refreshReuseGraceSeconds < 0) refreshReuseGraceSeconds = DEFAULT_REFRESH_REUSE_GRACE_SECONDS;
        }
    }

    /**
     * The emailed sign-in code and its limits.
     *
     * @param fixedCode            dev only: every code is this value (empty everywhere else: random codes)
     * @param codeTtlMinutes       how long a code works
     * @param maxAttempts          wrong tries after which the code is burnt
     * @param requestsPerIp        code requests per IP address per window
     * @param requestsPerEmail     code requests per email address per window
     * @param failedSignInsPerIp   wrong codes per IP address per window before sign-in answers 429
     * @param windowMinutes        the rate-limit window
     */
    public record Otp(String fixedCode, int codeTtlMinutes, int maxAttempts, int requestsPerIp, int requestsPerEmail, int failedSignInsPerIp,
            int windowMinutes) {

        public Otp {
            fixedCode = fixedCode == null ? "" : fixedCode.trim();
            if (codeTtlMinutes <= 0) codeTtlMinutes = 10;
            if (maxAttempts <= 0) maxAttempts = 5;
            if (requestsPerIp <= 0) requestsPerIp = 10;
            if (requestsPerEmail <= 0) requestsPerEmail = 5;
            if (failedSignInsPerIp <= 0) failedSignInsPerIp = 20;
            if (windowMinutes <= 0) windowMinutes = 15;
        }
    }

    /**
     * Outgoing email (sign-in codes). Without a host the message is written to the server log instead (dev).
     *
     * @param host      SMTP server; empty = log only
     * @param port      587 (STARTTLS) or 465 (SSL)
     * @param username  SMTP login, empty for none
     * @param password  SMTP password
     * @param from      the sender, e.g. {@code JurisTrack <no-reply@jurislpo.com>}
     */
    public record Mail(String host, int port, String username, String password, String from) {

        public Mail {
            host = host == null ? "" : host.trim();
            if (port <= 0) port = 587;
            username = username == null ? "" : username.trim();
            password = password == null ? "" : password;
            if (from == null || from.isBlank()) from = "JurisTrack <no-reply@jurislpo.com>";
        }
    }
}

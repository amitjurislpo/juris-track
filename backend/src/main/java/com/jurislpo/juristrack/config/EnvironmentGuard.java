package com.jurislpo.juristrack.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a shared environment with development shortcuts or unfilled placeholders. Only dev may use the fixed
 * sign-in code; sqe, uat and prd must send real codes by email (SMTP set), name the platform super admin and, when
 * embedded in JurisOne, hold JurisOne's public key. uat and prd are also checked for demo data, an http:// public URL, cookies without the Secure flag and secrets still written as
 * {@code <...>} or missing. The environment name is {@code juristrack.env} (dev | sqe | uat | prd), set by each
 * application-&lt;env&gt;.properties.
 */
@Component
public class EnvironmentGuard {

    static final List<String> ENVIRONMENTS = List.of("dev", "sqe", "uat", "prd");
    static final List<String> STRICT_ENVIRONMENTS = List.of("uat", "prd");
    private static final List<String> SECRETS = List.of("juristrack.security.jwt-secret", "spring.datasource.password");

    private final Environment environment;

    public EnvironmentGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void refuseUnsafeConfiguration() {
        // there is no default profile: a start without one would otherwise be taken for dev
        if (environment.getProperty("juristrack.env", "").isBlank()) {
            throw new IllegalStateException("No environment is active: start with SPRING_PROFILES_ACTIVE=" + String.join("|", ENVIRONMENTS)
                    + " (there is no default profile, so a forgotten setting can never run with the dev shortcuts)");
        }
        List<String> problems = findProblems(environment::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration for juristrack.env=" + environment.getProperty("juristrack.env", "dev")
                    + ":\n - " + String.join("\n - ", problems));
        }
    }

    /** What is wrong with this configuration (empty = fine). {@code property} returns a property or null. */
    static List<String> findProblems(Function<String, String> property) {
        List<String> problems = new ArrayList<>();
        String environmentName = valueOrDefault(property.apply("juristrack.env"), "dev").trim().toLowerCase();
        if (!ENVIRONMENTS.contains(environmentName)) {
            problems.add("juristrack.env must be one of " + ENVIRONMENTS + ", not \"" + environmentName + "\"");
            return problems;
        }
        if ("dev".equals(environmentName)) {
            // whatever juristrack.env says: dev's fixed sign-in code and public secrets are for a machine of its own only
            if (!valueOrDefault(property.apply("juristrack.otp.fixed-code"), "").isBlank()) {
                String databaseUrl = valueOrDefault(property.apply("spring.datasource.url"), "");
                if (!databaseUrl.isEmpty() && !isLocal(databaseUrl)) {
                    problems.add("spring.datasource.url is not on this machine: the fixed sign-in code (dev) is refused against a shared database");
                }
                String appUrl = valueOrDefault(property.apply("juristrack.app-url"), "");
                if (!appUrl.isEmpty() && !isLocal(appUrl)) {
                    problems.add("juristrack.app-url is not localhost: the fixed sign-in code (dev) is refused for a public address");
                }
            }
            return problems;
        }
        if (!valueOrDefault(property.apply("juristrack.otp.fixed-code"), "").isBlank()) {
            problems.add("juristrack.otp.fixed-code must be empty (a fixed sign-in code is for dev only)");
        }
        if (valueOrDefault(property.apply("juristrack.mail.host"), "").isBlank()) {
            problems.add("juristrack.mail.host must be set (sign-in codes are emailed)");
        }
        String superAdminEmail = valueOrDefault(property.apply("juristrack.seed.super-admin-email"), "");
        if (!superAdminEmail.contains("@") || superAdminEmail.contains("<")) {
            problems.add("juristrack.seed.super-admin-email must be set to the platform super admin's email");
        }
        if ("true".equalsIgnoreCase(valueOrDefault(property.apply("juristrack.integration.enabled"), "false"))) {
            String publicKey = valueOrDefault(property.apply("juristrack.integration.public-key"), "");
            if (publicKey.isBlank() || publicKey.contains("<")) {
                problems.add("juristrack.integration.public-key must be set when juristrack.integration.enabled=true (JurisOne's public key)");
            }
        }
        if (!STRICT_ENVIRONMENTS.contains(environmentName)) return problems;
        if ("true".equalsIgnoreCase(valueOrDefault(property.apply("juristrack.seed.demo"), "false"))) {
            problems.add("juristrack.seed.demo must be false (no demo data outside dev and sqe)");
        }
        if (!valueOrDefault(property.apply("juristrack.app-url"), "").startsWith("https://")) {
            problems.add("juristrack.app-url must be an https:// URL");
        }
        if (!"true".equalsIgnoreCase(valueOrDefault(property.apply("juristrack.security.cookie-secure"), "false"))) {
            problems.add("juristrack.security.cookie-secure must be true (session cookies over https only)");
        }
        for (String secretName : SECRETS) {
            String value = valueOrDefault(property.apply(secretName), "");
            if (value.isBlank() || value.contains("<")) problems.add(secretName + " is not set (still a placeholder or empty)");
        }
        return problems;
    }

    /** A URL whose host is this machine or a docker-compose service next to it: {@code localhost}, {@code 127.0.0.1}, {@code [::1]} or a name without dots ({@code db}). */
    private static boolean isLocal(String url) {
        return url.matches("(?i)^[a-z0-9+.:-]*//(localhost|127\\.0\\.0\\.1|\\[::1]|[a-z0-9_-]+)([:/?].*)?$");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

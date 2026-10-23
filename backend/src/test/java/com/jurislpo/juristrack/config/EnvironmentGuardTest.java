package com.jurislpo.juristrack.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Only dev may use the fixed sign-in code; sqe/uat/prd need email, the super admin's address and, when embedded in JurisOne, its public key;
 * uat and prd are also checked for demo data, http and placeholders.
 */
class EnvironmentGuardTest {

    static Map<String, String> completeConfiguration(String environmentName) {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("juristrack.env", environmentName);
        configuration.put("juristrack.otp.fixed-code", "");
        configuration.put("juristrack.seed.demo", "false");
        configuration.put("juristrack.mail.host", "smtp.example.com");
        configuration.put("juristrack.seed.super-admin-email", "admin@jurislpo.com");
        configuration.put("juristrack.app-url", "https://track.example.com");
        configuration.put("juristrack.security.cookie-secure", "true");
        configuration.put("juristrack.security.jwt-secret", "x".repeat(64));
        configuration.put("spring.datasource.password", "database-password");
        configuration.put("juristrack.integration.enabled", "false");
        configuration.put("juristrack.integration.public-key", "");
        return configuration;
    }

    static List<String> problemsOf(Map<String, String> configuration) {
        return EnvironmentGuard.findProblems(configuration::get);
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        assertThat(problemsOf(completeConfiguration("prd"))).isEmpty();
        assertThat(problemsOf(completeConfiguration("uat"))).isEmpty();
    }

    @Test
    void devMayUseTheFixedCodeDemoDataAndHttp() {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("juristrack.env", "dev");
        configuration.put("juristrack.otp.fixed-code", "000000");
        configuration.put("juristrack.seed.demo", "true");
        configuration.put("juristrack.app-url", "http://localhost:3007");
        configuration.put("juristrack.integration.enabled", "true");
        assertThat(problemsOf(configuration)).isEmpty();
    }

    @Test
    void anUnsetEnvironmentIsDev() {
        assertThat(problemsOf(new HashMap<>())).isEmpty();
    }

    @Test
    void anUnknownEnvironmentIsRejected() {
        assertThat(problemsOf(completeConfiguration("staging"))).singleElement().asString().contains("juristrack.env must be one of");
    }

    @Test
    void sqeNeedsRealCodesByEmailButMayKeepDemoDataAndHttp() {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("juristrack.env", "sqe");
        configuration.put("juristrack.seed.demo", "true");
        configuration.put("juristrack.app-url", "http://sqe.internal:3017");
        configuration.put("juristrack.mail.host", "smtp.example.com");
        configuration.put("juristrack.seed.super-admin-email", "admin@jurislpo.com");
        assertThat(problemsOf(configuration)).isEmpty();
        configuration.put("juristrack.otp.fixed-code", "000000");
        configuration.put("juristrack.mail.host", "");
        assertThat(problemsOf(configuration)).hasSize(2)
                .anyMatch(problem -> problem.contains("juristrack.otp.fixed-code"))
                .anyMatch(problem -> problem.contains("juristrack.mail.host"));
    }

    @Test
    void aFixedSignInCodeIsRefusedInProduction() {
        for (String environmentName : List.of("uat", "prd")) {
            Map<String, String> configuration = completeConfiguration(environmentName);
            configuration.put("juristrack.otp.fixed-code", "000000");
            assertThat(problemsOf(configuration)).as(environmentName).singleElement().asString().contains("juristrack.otp.fixed-code");
        }
    }

    @Test
    void demoDataMissingEmailAndHttpAreRefused() {
        Map<String, String> configuration = completeConfiguration("prd");
        configuration.put("juristrack.seed.demo", "true");
        configuration.put("juristrack.mail.host", "");
        configuration.put("juristrack.app-url", "http://track.example.com");
        assertThat(problemsOf(configuration)).hasSize(3);
    }

    @Test
    void sessionCookiesMustBeSecureInProduction() {
        for (String environmentName : List.of("uat", "prd")) {
            Map<String, String> configuration = completeConfiguration(environmentName);
            configuration.put("juristrack.security.cookie-secure", "false");
            assertThat(problemsOf(configuration)).as(environmentName).singleElement().asString().contains("juristrack.security.cookie-secure");
        }
        // sqe is served over http and may keep plain cookies
        Map<String, String> sqeConfiguration = completeConfiguration("sqe");
        sqeConfiguration.put("juristrack.security.cookie-secure", "false");
        assertThat(problemsOf(sqeConfiguration)).isEmpty();
    }

    @Test
    void placeholderAndEmptySecretsAreRefused() {
        Map<String, String> configuration = completeConfiguration("prd");
        configuration.put("juristrack.security.jwt-secret", "<generate: openssl rand -base64 48>");
        configuration.remove("spring.datasource.password");
        assertThat(problemsOf(configuration)).hasSize(2)
                .anyMatch(problem -> problem.startsWith("juristrack.security.jwt-secret"))
                .anyMatch(problem -> problem.startsWith("spring.datasource.password"));
    }

    @Test
    void embeddedModeOutsideDevNeedsJurisOnesPublicKey() {
        for (String environmentName : List.of("sqe", "uat", "prd")) {
            Map<String, String> configuration = completeConfiguration(environmentName);
            configuration.put("juristrack.integration.enabled", "true");
            assertThat(problemsOf(configuration)).as(environmentName).singleElement().asString().contains("juristrack.integration.public-key");
            configuration.put("juristrack.integration.public-key", "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQc=\n-----END PUBLIC KEY-----");
            assertThat(problemsOf(configuration)).as(environmentName).isEmpty();
        }
    }

    @Test
    void sharedEnvironmentsMustNameTheSuperAdmin() {
        for (String environmentName : List.of("sqe", "uat", "prd")) {
            Map<String, String> configuration = completeConfiguration(environmentName);
            configuration.put("juristrack.seed.super-admin-email", "");
            assertThat(problemsOf(configuration)).as(environmentName).singleElement().asString().contains("juristrack.seed.super-admin-email");
            configuration.put("juristrack.seed.super-admin-email", "<owner@example.com>");
            assertThat(problemsOf(configuration)).as(environmentName).singleElement().asString().contains("juristrack.seed.super-admin-email");
        }
    }

    @Test
    void devValuesAreRefusedAgainstAnythingButThisMachine() {
        Map<String, String> configuration = new HashMap<>();
        configuration.put("juristrack.env", "dev");
        configuration.put("juristrack.otp.fixed-code", "000000");
        configuration.put("juristrack.app-url", "http://localhost:3007");
        configuration.put("spring.datasource.url", "jdbc:postgresql://localhost:5437/juristrack?currentSchema=public");
        assertThat(problemsOf(configuration)).isEmpty();
        configuration.put("spring.datasource.url", "jdbc:postgresql://db:5432/juristrack"); // a docker-compose service
        assertThat(problemsOf(configuration)).isEmpty();
        configuration.put("spring.datasource.url", "jdbc:postgresql://db.internal.example.com:5432/juristrack");
        assertThat(problemsOf(configuration)).singleElement().asString().contains("spring.datasource.url");
        configuration.put("spring.datasource.url", "jdbc:postgresql://localhost.evil.example.com:5432/juristrack");
        assertThat(problemsOf(configuration)).singleElement().asString().contains("spring.datasource.url");
        configuration.put("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5437/juristrack");
        configuration.put("juristrack.app-url", "https://track.example.com");
        assertThat(problemsOf(configuration)).singleElement().asString().contains("juristrack.app-url");
    }
}

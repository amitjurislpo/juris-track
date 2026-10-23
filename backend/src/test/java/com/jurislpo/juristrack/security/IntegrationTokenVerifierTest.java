package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.IntegrationTokenVerifier.InvalidIntegrationTokenException;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The token checks on their own (no Spring): algorithms per key type, claims, role mapping, key configuration. */
class IntegrationTokenVerifierTest {

    static IntegrationTokenVerifier verifierFor(KeyPair keyPair) {
        JuristrackProperties properties = new JuristrackProperties(null, null, false, null,
                new JuristrackProperties.Integration(true, IntegrationTokens.ISSUER, IntegrationTokens.AUDIENCE, IntegrationTokens.pemOf(keyPair.getPublic()), 30),
                null, null, null);
        return new IntegrationTokenVerifier(properties);
    }

    static Employee employee() {
        Employee employee = IntegrationTokens.newEmployee(UUID.randomUUID(), "Acme", "MANAGER");
        return employee.withManager(UUID.randomUUID());
    }

    @Test
    void anEs256TokenIsAcceptedWithAnEcKey() {
        KeyPair ecKeyPair = IntegrationTokens.ecKeyPair();
        Employee employee = employee();
        IntegrationClaims claims = verifierFor(ecKeyPair).verify(IntegrationTokens.sign(IntegrationTokens.claimsOf(employee).build(), ecKeyPair));
        assertThat(claims).isEqualTo(new IntegrationClaims(employee.employeeId(), employee.email(), "Asha Rao", employee.businessId(), "Acme",
                employee.managerId(), Role.MANAGER));
    }

    @Test
    void theAlgorithmMustMatchTheKeyType() throws Exception {
        Employee employee = employee();
        // RS256 against the configured EC key
        IntegrationTokenVerifier ecVerifier = verifierFor(IntegrationTokens.ecKeyPair());
        String rsaToken = IntegrationTokens.sign(IntegrationTokens.claimsOf(employee).build(), IntegrationTokens.rsaKeyPair());
        assertThatThrownBy(() -> ecVerifier.verify(rsaToken)).isInstanceOf(InvalidIntegrationTokenException.class);

        // an unsigned token and a symmetric (HS256) one are refused
        IntegrationTokenVerifier rsaVerifier = verifierFor(IntegrationTokens.JURISONE_KEY_PAIR);
        String unsignedToken = new PlainJWT(new PlainHeader(), IntegrationTokens.claimsOf(employee).build()).serialize();
        assertThatThrownBy(() -> rsaVerifier.verify(unsignedToken)).isInstanceOf(InvalidIntegrationTokenException.class);
        SignedJWT symmetricToken = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), IntegrationTokens.claimsOf(employee).build());
        symmetricToken.sign(new MACSigner("a-shared-secret-of-at-least-32-bytes!!".getBytes()));
        assertThatThrownBy(() -> rsaVerifier.verify(symmetricToken.serialize())).isInstanceOf(InvalidIntegrationTokenException.class);
    }

    @Test
    void everyRequiredClaimMustBePresent() {
        IntegrationTokenVerifier verifier = verifierFor(IntegrationTokens.JURISONE_KEY_PAIR);
        Employee employee = employee();
        for (String claimName : new String[] {"email", "name", "businessId", "businessName"}) {
            String token = IntegrationTokens.tokenFor(employee, claims -> claims.claim(claimName, null));
            assertThatThrownBy(() -> verifier.verify(token)).as(claimName).isInstanceOf(InvalidIntegrationTokenException.class);
        }
        String withoutSubject = IntegrationTokens.tokenFor(employee, claims -> claims.subject(null));
        assertThatThrownBy(() -> verifier.verify(withoutSubject)).isInstanceOf(InvalidIntegrationTokenException.class);
        String withoutExpiry = IntegrationTokens.tokenFor(employee, claims -> claims.expirationTime(null));
        assertThatThrownBy(() -> verifier.verify(withoutExpiry)).isInstanceOf(InvalidIntegrationTokenException.class);
        String notAUuid = IntegrationTokens.tokenFor(employee, claims -> claims.claim("businessId", "JC-001"));
        assertThatThrownBy(() -> verifier.verify(notAUuid)).isInstanceOf(InvalidIntegrationTokenException.class);
        // managerId may be missing (no manager)
        assertThat(verifier.verify(IntegrationTokens.tokenFor(employee, claims -> claims.claim("managerId", null))).jurisoneManagerId()).isNull();
    }

    @Test
    void trackRoleMapsToManagerEmployeeOrAdmin() {
        assertThat(IntegrationTokenVerifier.roleOf("MANAGER")).isEqualTo(Role.MANAGER);
        assertThat(IntegrationTokenVerifier.roleOf("manager")).isEqualTo(Role.MANAGER);
        assertThat(IntegrationTokenVerifier.roleOf("EMPLOYEE")).isEqualTo(Role.EMPLOYEE);
        assertThat(IntegrationTokenVerifier.roleOf("ADMIN")).isEqualTo(Role.ADMIN);
        assertThat(IntegrationTokenVerifier.roleOf("SUPER_ADMIN")).isEqualTo(Role.EMPLOYEE);
        assertThat(IntegrationTokenVerifier.roleOf("HR")).isEqualTo(Role.EMPLOYEE);
        assertThat(IntegrationTokenVerifier.roleOf(null)).isEqualTo(Role.EMPLOYEE);
    }

    @Test
    void theKeyMustBeAPublicKey() {
        KeyPair keyPair = IntegrationTokens.rsaKeyPair();
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        assertThatThrownBy(() -> IntegrationTokenVerifier.parsePublicKey(privateKeyPem)).hasMessageContaining("private key");
        assertThatThrownBy(() -> IntegrationTokenVerifier.parsePublicKey("")).hasMessageContaining("must be set");
        assertThatThrownBy(() -> IntegrationTokenVerifier.parsePublicKey("-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void whenDisabledEveryTokenIsRefused() {
        IntegrationTokenVerifier disabledVerifier = new IntegrationTokenVerifier(new JuristrackProperties(null, null, false, null, null, null, null, null));
        assertThat(disabledVerifier.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabledVerifier.verify(IntegrationTokens.tokenFor(employee()))).isInstanceOf(InvalidIntegrationTokenException.class);
    }

    @Test
    void aTokenNeedsAnIssueTimeAndAnIdAndMayLiveTenMinutesAtMost() {
        IntegrationTokenVerifier verifier = verifierFor(IntegrationTokens.JURISONE_KEY_PAIR);
        Employee employee = employee();
        String withoutIssueTime = IntegrationTokens.tokenFor(employee, claims -> claims.issueTime(null));
        assertThatThrownBy(() -> verifier.verify(withoutIssueTime)).hasMessage("missing_issue_time");
        String withoutId = IntegrationTokens.tokenFor(employee, claims -> claims.jwtID(null));
        assertThatThrownBy(() -> verifier.verify(withoutId)).hasMessage("missing_claim: jti");
        Instant issuedAt = Instant.now();
        String longLived = IntegrationTokens.tokenFor(employee, claims -> claims.issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plusSeconds(601))));
        assertThatThrownBy(() -> verifier.verify(longLived)).hasMessage("lifetime_too_long");
        String tenMinutes = IntegrationTokens.tokenFor(employee, claims -> claims.issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plusSeconds(600))));
        assertThat(verifier.verify(tenMinutes).jurisoneEmployeeId()).isEqualTo(employee.employeeId());
    }

    @Test
    void aTokenWorksOnceOnly() {
        IntegrationTokenVerifier verifier = verifierFor(IntegrationTokens.JURISONE_KEY_PAIR);
        String token = IntegrationTokens.tokenFor(employee());
        verifier.verify(token);
        assertThatThrownBy(() -> verifier.verify(token)).hasMessage("replayed_token");
    }
}

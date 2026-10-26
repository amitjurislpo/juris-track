package com.jurislpo.juristrack.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/** Plays JurisOne in the tests: key pairs made at runtime and tokens signed with them. */
public final class IntegrationTokens {
    private IntegrationTokens() {}

    public static final String ISSUER = "http://jurisone.test";
    public static final String AUDIENCE = "juristrack";
    /** The key pair configured as JurisOne's (its public key is juristrack.integration.public-key). */
    public static final KeyPair JURISONE_KEY_PAIR = rsaKeyPair();

    public static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pemOf(PublicKey publicKey) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    /** One employee as JurisOne would describe them in a token. */
    public record Employee(UUID employeeId, String email, String name, UUID businessId, String businessName, UUID managerId, String trackRole) {

        public Employee withTrackRole(String newTrackRole) {
            return new Employee(employeeId, email, name, businessId, businessName, managerId, newTrackRole);
        }

        public Employee withManager(UUID newManagerId) {
            return new Employee(employeeId, email, name, businessId, businessName, newManagerId, trackRole);
        }

        public Employee withName(String newName) {
            return new Employee(employeeId, email, newName, businessId, businessName, managerId, trackRole);
        }
    }

    /** The usual claims of {@code employee}, valid for 5 minutes. */
    public static JWTClaimsSet.Builder claimsOf(Employee employee) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(employee.employeeId().toString())
                .claim("email", employee.email())
                .claim("name", employee.name())
                .claim("businessId", employee.businessId().toString())
                .claim("businessName", employee.businessName())
                .claim("managerId", employee.managerId() == null ? null : employee.managerId().toString())
                .claim("trackRole", employee.trackRole())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    /** The claims of JurisOne's service token for the roster sync of one business (scope {@code employees:sync}, no {@code sub}, no {@code email}). */
    public static JWTClaimsSet.Builder serviceClaimsOf(UUID businessId, String businessName) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("scope", IntegrationTokenVerifier.ROSTER_SYNC_SCOPE)
                .claim("businessId", businessId.toString())
                .claim("businessName", businessName)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    /** A service token for the roster sync of {@code businessId}, after {@code adjustClaims} (e.g. another scope). */
    public static String serviceTokenFor(UUID businessId, String businessName, Consumer<JWTClaimsSet.Builder> adjustClaims) {
        JWTClaimsSet.Builder claims = serviceClaimsOf(businessId, businessName);
        adjustClaims.accept(claims);
        return sign(claims.build(), JURISONE_KEY_PAIR);
    }

    public static String serviceTokenFor(UUID businessId, String businessName) {
        return serviceTokenFor(businessId, businessName, claims -> {});
    }

    /** A token JurisOne would send for {@code employee}, after {@code adjustClaims} (e.g. a wrong audience). */
    public static String tokenFor(Employee employee, Consumer<JWTClaimsSet.Builder> adjustClaims) {
        JWTClaimsSet.Builder claims = claimsOf(employee);
        adjustClaims.accept(claims);
        return sign(claims.build(), JURISONE_KEY_PAIR);
    }

    public static String tokenFor(Employee employee) {
        return tokenFor(employee, claims -> {});
    }

    /** Signs with RS256 (RSA key) or ES256 (EC key). */
    public static String sign(JWTClaimsSet claims, KeyPair keyPair) {
        try {
            boolean isRsa = keyPair.getPrivate() instanceof RSAPrivateKey;
            JWSSigner signer = isRsa ? new RSASSASigner(keyPair.getPrivate()) : new ECDSASigner((ECPrivateKey) keyPair.getPrivate());
            SignedJWT token = new SignedJWT(new JWSHeader(isRsa ? JWSAlgorithm.RS256 : JWSAlgorithm.ES256), claims);
            token.sign(signer);
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new JurisOne employee of {@code businessId}. */
    public static Employee newEmployee(UUID businessId, String businessName, String trackRole) {
        UUID employeeId = UUID.randomUUID();
        return new Employee(employeeId, "jurisone." + employeeId.toString().substring(0, 8) + "@test.juristrack.test", "Asha Rao", businessId, businessName,
                null, trackRole);
    }
}

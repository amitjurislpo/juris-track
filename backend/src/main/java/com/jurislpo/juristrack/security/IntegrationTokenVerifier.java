package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.entity.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Verifies the token JurisOne sends when JurisTrack runs embedded in it.
 *
 * <p>In embedded mode the browser talks only to JurisOne; the JurisOne API is the gateway ({@code /api/juris-track/**} there,
 * forwarded to this API) and adds {@code Authorization: Bearer <jwt>}: a short-lived JWT signed with JurisOne's <b>private</b>
 * key. JurisTrack holds only the public key ({@code juristrack.integration.public-key}, PEM): an RSA key accepts only RS256,
 * an EC P-256 key only ES256, so {@code alg=none} and symmetric algorithms are refused. Checked:
 * <ul>
 *   <li>the signature;</li>
 *   <li>{@code iss} = {@code juristrack.integration.issuer}, {@code aud} contains {@code juristrack.integration.audience};</li>
 *   <li>{@code exp} present and not passed, {@code nbf} / {@code iat} not in the future, each within
 *       {@code juristrack.integration.clock-skew-seconds};</li>
 *   <li>the claims {@code sub} (JurisOne employee id), {@code email}, {@code name}, {@code businessId} and {@code businessName};
 *       {@code managerId} (null when absent) and {@code trackRole} (MANAGER or EMPLOYEE; ADMIN only when sent; anything else
 *       or nothing is EMPLOYEE).</li>
 * </ul>
 * Any failure is an {@link InvalidIntegrationTokenException}; the request filter answers 401 without the reason and never logs
 * the token. Only used while {@code juristrack.integration.enabled=true}.
 */
@Component
public class IntegrationTokenVerifier {

    /**
     * Why a token was refused: the message is a short reason code ({@code bad_signature}, {@code expired}, {@code missing_claim: email},
     * ...) for the server log and the audit log, never sent to the caller and never containing the token.
     */
    public static class InvalidIntegrationTokenException extends RuntimeException {
        InvalidIntegrationTokenException(String reasonCode) {
            super(reasonCode);
        }
    }

    private final boolean isEnabled;
    private final String issuer;
    private final String audience;
    private final Duration clockSkew;
    private final JWSVerifier signatureVerifier;
    private final JWSAlgorithm expectedAlgorithm;

    public IntegrationTokenVerifier(JuristrackProperties properties) {
        JuristrackProperties.Integration integration = properties.integration();
        this.isEnabled = integration.enabled();
        this.issuer = integration.issuer();
        this.audience = integration.audience();
        this.clockSkew = Duration.ofSeconds(integration.clockSkewSeconds());
        if (!isEnabled) {
            this.signatureVerifier = null;
            this.expectedAlgorithm = null;
            return;
        }
        if (issuer == null || issuer.isBlank()) throw new IllegalStateException("juristrack.integration.issuer must be set when the integration is enabled");
        PublicKey publicKey = parsePublicKey(integration.publicKey());
        try {
            if (publicKey instanceof RSAPublicKey rsaPublicKey) {
                this.signatureVerifier = new RSASSAVerifier(rsaPublicKey);
                this.expectedAlgorithm = JWSAlgorithm.RS256;
            } else if (publicKey instanceof ECPublicKey ecPublicKey) {
                this.signatureVerifier = new ECDSAVerifier(ecPublicKey);
                this.expectedAlgorithm = JWSAlgorithm.ES256;
            } else {
                throw new IllegalStateException("juristrack.integration.public-key must be an RSA or EC public key");
            }
        } catch (JOSEException e) {
            throw new IllegalStateException("juristrack.integration.public-key cannot verify ES256 (an EC key must be on curve P-256)", e);
        }
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    /** The verified claims, or {@link InvalidIntegrationTokenException}. */
    public IntegrationClaims verify(String token) {
        if (!isEnabled) throw new InvalidIntegrationTokenException("integration_disabled");
        JWTClaimsSet claims;
        try {
            SignedJWT signedToken = SignedJWT.parse(token);
            if (!expectedAlgorithm.equals(signedToken.getHeader().getAlgorithm())) throw new InvalidIntegrationTokenException("unexpected_algorithm");
            if (!signedToken.verify(signatureVerifier)) throw new InvalidIntegrationTokenException("bad_signature");
            claims = signedToken.getJWTClaimsSet();
        } catch (ParseException | JOSEException | IllegalStateException e) {
            throw new InvalidIntegrationTokenException("malformed_token");
        }
        checkIssuerAndAudience(claims);
        checkTimes(claims, Instant.now());
        return readClaims(claims);
    }

    private void checkIssuerAndAudience(JWTClaimsSet claims) {
        if (!issuer.equals(claims.getIssuer())) throw new InvalidIntegrationTokenException("wrong_issuer");
        List<String> audiences = claims.getAudience();
        if (audiences == null || !audiences.contains(audience)) throw new InvalidIntegrationTokenException("wrong_audience");
    }

    private void checkTimes(JWTClaimsSet claims, Instant now) {
        Date expiresAt = claims.getExpirationTime();
        if (expiresAt == null) throw new InvalidIntegrationTokenException("missing_expiry");
        if (!expiresAt.toInstant().plus(clockSkew).isAfter(now)) throw new InvalidIntegrationTokenException("expired");
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null && notBefore.toInstant().minus(clockSkew).isAfter(now)) throw new InvalidIntegrationTokenException("not_valid_yet");
        Date issuedAt = claims.getIssueTime();
        if (issuedAt != null && issuedAt.toInstant().minus(clockSkew).isAfter(now)) throw new InvalidIntegrationTokenException("issued_in_the_future");
    }

    private static IntegrationClaims readClaims(JWTClaimsSet claims) {
        try {
            UUID jurisoneEmployeeId = requiredUuid(claims.getSubject(), "sub");
            String email = requiredText(claims.getStringClaim("email"), "email").toLowerCase(Locale.ROOT);
            if (!email.contains("@")) throw new InvalidIntegrationTokenException("invalid_claim: email");
            String name = requiredText(claims.getStringClaim("name"), "name");
            UUID jurisoneBusinessId = requiredUuid(claims.getStringClaim("businessId"), "businessId");
            String businessName = requiredText(claims.getStringClaim("businessName"), "businessName");
            String managerId = claims.getStringClaim("managerId");
            UUID jurisoneManagerId = managerId == null || managerId.isBlank() ? null : requiredUuid(managerId, "managerId");
            return new IntegrationClaims(jurisoneEmployeeId, email, name, jurisoneBusinessId, businessName, jurisoneManagerId,
                    roleOf(claims.getStringClaim("trackRole")));
        } catch (ParseException e) {
            throw new InvalidIntegrationTokenException("invalid_claim_type");
        }
    }

    /** {@code trackRole}: MANAGER and EMPLOYEE as sent, ADMIN only when JurisOne sends it; anything else (or nothing) is EMPLOYEE. */
    static Role roleOf(String trackRole) {
        if (trackRole == null) return Role.EMPLOYEE;
        return switch (trackRole.trim().toUpperCase(Locale.ROOT)) {
            case "MANAGER" -> Role.MANAGER;
            case "ADMIN" -> Role.ADMIN;
            default -> Role.EMPLOYEE;
        };
    }

    private static String requiredText(String value, String claimName) {
        if (value == null || value.isBlank()) throw new InvalidIntegrationTokenException("missing_claim: " + claimName);
        return value.trim();
    }

    private static UUID requiredUuid(String value, String claimName) {
        try {
            return UUID.fromString(requiredText(value, claimName));
        } catch (IllegalArgumentException e) {
            throw new InvalidIntegrationTokenException("invalid_claim: " + claimName);
        }
    }

    /** A PEM ({@code -----BEGIN PUBLIC KEY-----}, X.509 SubjectPublicKeyInfo) RSA or EC public key. */
    static PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) throw new IllegalStateException("juristrack.integration.public-key must be set when the integration is enabled");
        if (pem.contains("PRIVATE KEY")) throw new IllegalStateException("juristrack.integration.public-key holds a private key: configure the public key only");
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] encodedKey;
        try {
            encodedKey = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("juristrack.integration.public-key is not a PEM public key", e);
        }
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(encodedKey));
            } catch (InvalidKeySpecException e) {
                // not this key type: try the next one
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("juristrack.integration.public-key is neither an RSA nor an EC public key");
    }
}

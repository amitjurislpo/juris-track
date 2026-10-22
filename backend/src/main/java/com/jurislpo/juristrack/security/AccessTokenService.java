package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.config.JuristrackProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The standalone access token ({@code jt_at} cookie): an HS256 JWT signed with {@code juristrack.security.jwt-secret}.
 * Claims: {@code iss} = {@value #ISSUER}, {@code sub} = user id, {@code sid} = session id (the refresh-token family),
 * {@code iat}, {@code exp}, {@code jti}. It only proves who the caller is; the request filter still checks the session and
 * the user in the database on every request, so signing out or a deactivation applies at once.
 */
@Service
public class AccessTokenService {

    public static final String ISSUER = "juristrack";
    private static final String SESSION_CLAIM = "sid";
    private static final int MINIMUM_SECRET_BYTES = 32;

    /** The verified claims of an access token. */
    public record AccessClaims(UUID userId, String sessionId) {}

    /** A token that was valid but has run out (the client should refresh). */
    public static class ExpiredTokenException extends RuntimeException {
        ExpiredTokenException() {
            super("access token expired");
        }
    }

    /** A malformed, forged or foreign token. */
    public static class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String reason) {
            super(reason);
        }
    }

    private final byte[] secret;
    private final Duration lifetime;

    public AccessTokenService(JuristrackProperties properties) {
        String jwtSecret = properties.security().jwtSecret();
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("juristrack.security.jwt-secret must be at least 32 bytes (e.g. openssl rand -base64 48)");
        }
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = Duration.ofSeconds(properties.security().accessTokenTtlSeconds());
    }

    public Duration lifetime() {
        return lifetime;
    }

    public String issue(UUID userId, String sessionId) {
        return issue(userId, sessionId, Instant.now());
    }

    /** A token issued at {@code issuedAt} (tests use a past instant for an expired token). */
    public String issue(UUID userId, String sessionId, Instant issuedAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(SESSION_CLAIM, sessionId)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(lifetime)))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            token.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the access token", e);
        }
        return token.serialize();
    }

    /** The claims of a valid token; {@link ExpiredTokenException} when it ran out, {@link InvalidTokenException} for anything else wrong. */
    public AccessClaims verify(String token) {
        try {
            SignedJWT signedToken = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(signedToken.getHeader().getAlgorithm())) throw new InvalidTokenException("unexpected algorithm");
            if (!signedToken.verify(new MACVerifier(secret))) throw new InvalidTokenException("bad signature");
            JWTClaimsSet claims = signedToken.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) throw new InvalidTokenException("wrong issuer");
            Date expiresAt = claims.getExpirationTime();
            if (expiresAt == null) throw new InvalidTokenException("no expiry");
            if (!expiresAt.toInstant().isAfter(Instant.now())) throw new ExpiredTokenException();
            String sessionId = claims.getStringClaim(SESSION_CLAIM);
            if (sessionId == null || claims.getSubject() == null) throw new InvalidTokenException("missing claims");
            return new AccessClaims(UUID.fromString(claims.getSubject()), sessionId);
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            throw new InvalidTokenException("malformed token");
        }
    }
}

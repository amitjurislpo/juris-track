package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.common.RequestMetadata;
import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.crypto.Tokens;
import com.jurislpo.juristrack.repository.SessionRepository;
import com.jurislpo.juristrack.repository.SessionRepository.RefreshTokenSession;
import com.jurislpo.juristrack.security.AccessTokenService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standalone sessions. A {@code sessions} row is one signed-in device and its refresh-token family; it lasts
 * {@code juristrack.security.session-ttl-days} with "keep me signed in", else {@code short-session-hours}. The browser holds
 * two HTTP-only {@code SameSite=Lax} cookies: {@value #ACCESS_COOKIE} (the short JWT access token) and {@value #REFRESH_COOKIE}
 * (an opaque refresh token that rotates on every use; only its SHA-256 is stored). Without "keep me signed in" both are
 * browser-session cookies.
 */
@Service
public class SessionService {

    public static final String ACCESS_COOKIE = "jt_at";
    public static final String REFRESH_COOKIE = "jt_rt";
    static final String REVOKED_FOR_SIGN_OUT = "sign_out";
    static final String REVOKED_FOR_REFRESH_REUSE = "refresh_reuse";
    static final String REVOKED_FOR_INACTIVE_USER = "user_inactive";

    /** A new or renewed session: its id, its user and the cookies to set. */
    public record IssuedSession(String sessionId, UUID userId, List<ResponseCookie> cookies) {}

    /** Why a refresh was refused (the caller answers 401 and clears the cookies). */
    public static class RefreshRefusedException extends RuntimeException {
        private final UUID userId;
        private final boolean wasReused;

        RefreshRefusedException(String reason, UUID userId, boolean wasReused) {
            super(reason);
            this.userId = userId;
            this.wasReused = wasReused;
        }

        public UUID userId() { return userId; }

        public boolean wasReused() { return wasReused; }
    }

    private final SessionRepository sessionRepository;
    private final AccessTokenService accessTokenService;
    private final JuristrackProperties.Security settings;

    public SessionService(SessionRepository sessionRepository, AccessTokenService accessTokenService, JuristrackProperties properties) {
        this.sessionRepository = sessionRepository;
        this.accessTokenService = accessTokenService;
        this.settings = properties.security();
    }

    /** Starts a session after a successful sign-in (in the caller's transaction) and returns its cookies. */
    @Transactional
    public IssuedSession create(UUID userId, boolean isRemembered, RequestMetadata requestMetadata) {
        // the id is the SHA-256 of a random token (sessions.id); it travels only inside the signed access token
        String sessionId = Tokens.sha256(Tokens.randomToken(32));
        Duration sessionLifetime = isRemembered ? Duration.ofDays(settings.sessionTtlDays()) : Duration.ofHours(settings.shortSessionHours());
        Instant expiresAt = Instant.now().plus(sessionLifetime).truncatedTo(ChronoUnit.MICROS);
        sessionRepository.insertSession(sessionId, userId, expiresAt, requestMetadata.ipAddress(), requestMetadata.userAgent());
        String refreshToken = createRefreshToken(sessionId, expiresAt, requestMetadata);
        return new IssuedSession(sessionId, userId, cookies(userId, sessionId, refreshToken, isRemembered ? sessionLifetime : null));
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token. A token that was already used means it was
     * copied: the whole session is ended, unless it was used only moments ago ({@code refresh-reuse-grace-seconds}: the
     * browser sending two requests at once when the access token ran out).
     */
    @Transactional(noRollbackFor = RefreshRefusedException.class) // a reused token must stay revoked although the call fails
    public IssuedSession refresh(String refreshToken, RequestMetadata requestMetadata) {
        if (refreshToken == null || refreshToken.isEmpty()) throw new RefreshRefusedException("missing", null, false);
        String tokenHash = Tokens.sha256(refreshToken);
        RefreshTokenSession tokenSession = sessionRepository.findRefreshTokenForUpdate(tokenHash)
                .orElseThrow(() -> new RefreshRefusedException("unknown", null, false));
        String sessionId = tokenSession.sessionId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (tokenSession.revokedAt() != null) throw new RefreshRefusedException("revoked", tokenSession.userId(), false);
        if (tokenSession.usedAt() != null
                && Duration.between(tokenSession.usedAt(), now).compareTo(Duration.ofSeconds(settings.refreshReuseGraceSeconds())) >= 0) {
            sessionRepository.revoke(sessionId, REVOKED_FOR_REFRESH_REUSE, now);
            throw new RefreshRefusedException("reused", tokenSession.userId(), true);
        }
        if (!tokenSession.sessionExpiresAt().isAfter(now)) throw new RefreshRefusedException("expired", tokenSession.userId(), false);
        if (!tokenSession.isUserActive()) {
            sessionRepository.revoke(sessionId, REVOKED_FOR_INACTIVE_USER, now);
            throw new RefreshRefusedException("inactive", tokenSession.userId(), false);
        }
        if (tokenSession.usedAt() == null) sessionRepository.markRefreshTokenUsed(tokenHash, now);
        sessionRepository.updateLastSeen(sessionId, now);
        String nextRefreshToken = createRefreshToken(sessionId, tokenSession.sessionExpiresAt(), requestMetadata);
        Duration sessionLifetime = Duration.between(tokenSession.sessionCreatedAt(), tokenSession.sessionExpiresAt());
        boolean isRemembered = sessionLifetime.compareTo(Duration.ofHours(settings.shortSessionHours() + 1L)) > 0;
        return new IssuedSession(sessionId, tokenSession.userId(), cookies(tokenSession.userId(), sessionId, nextRefreshToken,
                isRemembered ? Duration.between(now, tokenSession.sessionExpiresAt()) : null));
    }

    /** Ends one session (sign-out). */
    @Transactional
    public void revoke(String sessionId) {
        if (sessionId == null) return;
        sessionRepository.revoke(sessionId, REVOKED_FOR_SIGN_OUT, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Ends every session of a user who was deactivated (in the caller's transaction); returns how many were ended. */
    @Transactional
    public int revokeAllOfUser(UUID userId) {
        return sessionRepository.revokeAllOfUser(userId, REVOKED_FOR_INACTIVE_USER, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Sign-out with only the refresh cookie (the access token had run out): the session it belongs to, if any. */
    @Transactional
    public String revokeByRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return null;
        String sessionId = sessionRepository.findSessionIdByRefreshToken(Tokens.sha256(refreshToken)).orElse(null);
        revoke(sessionId);
        return sessionId;
    }

    private String createRefreshToken(String sessionId, Instant expiresAt, RequestMetadata requestMetadata) {
        String token = Tokens.randomToken(32);
        sessionRepository.insertRefreshToken(Tokens.sha256(token), sessionId, expiresAt, requestMetadata.ipAddress(), requestMetadata.userAgent());
        return token;
    }

    /** {@code maxAge} null = browser-session cookies (no "keep me signed in"). */
    private List<ResponseCookie> cookies(UUID userId, String sessionId, String refreshToken, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder accessCookie = cookie(ACCESS_COOKIE, accessTokenService.issue(userId, sessionId));
        ResponseCookie.ResponseCookieBuilder refreshCookie = cookie(REFRESH_COOKIE, refreshToken);
        if (maxAge != null) {
            accessCookie.maxAge(maxAge);
            refreshCookie.maxAge(maxAge);
        }
        return List.of(accessCookie.build(), refreshCookie.build());
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String name, String value) {
        return ResponseCookie.from(name, value).httpOnly(true).sameSite("Lax").secure(settings.cookieSecure()).path("/");
    }

    /** Cookies that remove both sign-in cookies from the browser. */
    public List<ResponseCookie> clearingCookies() {
        return List.of(cookie(ACCESS_COOKIE, "").maxAge(0).build(), cookie(REFRESH_COOKIE, "").maxAge(0).build());
    }
}

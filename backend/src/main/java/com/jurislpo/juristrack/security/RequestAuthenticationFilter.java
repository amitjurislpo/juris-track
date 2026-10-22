package com.jurislpo.juristrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.ForbiddenException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.exception.UnauthenticatedException;
import com.jurislpo.juristrack.repository.SessionRepository;
import com.jurislpo.juristrack.repository.SessionRepository.SessionUser;
import com.jurislpo.juristrack.service.IntegrationProvisioningService;
import com.jurislpo.juristrack.service.IntegrationProvisioningService.AccessRefusedException;
import com.jurislpo.juristrack.service.SessionService;
import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Builds the request's {@link CurrentUser}, from one of two sources, and makes the user's business the request's
 * {@link TenantContext} (row-level security; the SUPER_ADMIN has none and runs in system mode). The tenant is always cleared
 * when the request ends.
 * <ul>
 *   <li><b>Embedded</b> ({@code juristrack.integration.enabled=true} and an {@code Authorization: Bearer} header): JurisOne's
 *       token is verified ({@link IntegrationTokenVerifier}) and its business and user are provisioned just in time. A refused
 *       token answers 401 at once (no details, never logged), a deactivated user 403.</li>
 *   <li><b>Standalone</b>: the {@code jt_at} access-token cookie. Signature and expiry are checked first, then one lookup confirms
 *       that the session was not ended or expired and that the user is still active, so a sign-out or a deactivation applies on
 *       the very next request. {@code last_seen_at} is touched at most every 5 minutes. An expired token marks the request
 *       ({@link #TOKEN_EXPIRED_ATTRIBUTE}) so the 401 says {@code TOKEN_EXPIRED}.</li>
 * </ul>
 * Not a Spring bean: SecurityConfig adds it to the security filter chain only.
 */
public class RequestAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_EXPIRED_ATTRIBUTE = "juristrack.accessTokenExpired";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(RequestAuthenticationFilter.class);

    private final AccessTokenService accessTokenService;
    private final SessionRepository sessionRepository;
    private final IntegrationTokenVerifier integrationTokenVerifier;
    private final IntegrationProvisioningService integrationProvisioningService;
    private final ObjectMapper objectMapper;

    public RequestAuthenticationFilter(AccessTokenService accessTokenService, SessionRepository sessionRepository,
            IntegrationTokenVerifier integrationTokenVerifier, IntegrationProvisioningService integrationProvisioningService, ObjectMapper objectMapper) {
        this.accessTokenService = accessTokenService;
        this.sessionRepository = sessionRepository;
        this.integrationTokenVerifier = integrationTokenVerifier;
        this.integrationProvisioningService = integrationProvisioningService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        TenantContext.clear(); // the lookups below are system queries
        try {
            String authorization = request.getHeader("Authorization");
            boolean hasBearerToken = authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
            if (integrationTokenVerifier.isEnabled() && hasBearerToken) {
                if (!authenticateIntegrationToken(authorization.substring(BEARER_PREFIX.length()).trim(), request, response)) return;
            } else {
                authenticateSessionCookie(request);
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** False when the response was already written (401 or 403). */
    private boolean authenticateIntegrationToken(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        CurrentUser currentUser;
        try {
            IntegrationClaims claims = integrationTokenVerifier.verify(token);
            currentUser = provision(claims);
        } catch (IntegrationTokenVerifier.InvalidIntegrationTokenException e) {
            log.info("Rejected an integration token: {}", e.getMessage()); // the reason only, never the token
            writeProblem(request, response, HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE);
            return false;
        } catch (AccessRefusedException | DataIntegrityViolationException e) {
            log.info("Refused an integration user: {}", e.getMessage());
            writeProblem(request, response, HttpStatus.FORBIDDEN, Problems.FORBIDDEN, ForbiddenException.MESSAGE);
            return false;
        }
        authenticate(currentUser);
        return true;
    }

    /** Two first requests of a new business or user at the same moment: the loser's insert fails once, and the retry finds the rows. */
    private CurrentUser provision(IntegrationClaims claims) {
        try {
            return integrationProvisioningService.provision(claims);
        } catch (DataIntegrityViolationException e) {
            return integrationProvisioningService.provision(claims);
        }
    }

    private void authenticateSessionCookie(HttpServletRequest request) {
        String token = cookieValue(request, SessionService.ACCESS_COOKIE);
        if (token == null || token.isEmpty()) return;
        try {
            AccessTokenService.AccessClaims claims = accessTokenService.verify(token);
            CurrentUser currentUser = loadSessionUser(claims);
            if (currentUser != null) authenticate(currentUser);
        } catch (AccessTokenService.ExpiredTokenException e) {
            request.setAttribute(TOKEN_EXPIRED_ATTRIBUTE, Boolean.TRUE);
        } catch (AccessTokenService.InvalidTokenException e) {
            log.debug("Rejected an access token: {}", e.getMessage()); // forged or malformed: treated as signed out
        }
    }

    private CurrentUser loadSessionUser(AccessTokenService.AccessClaims claims) {
        SessionUser sessionUser = sessionRepository.findSessionUser(claims.sessionId()).orElse(null);
        Instant now = Instant.now();
        if (sessionUser == null || !claims.userId().equals(sessionUser.sessionUserId())) return null;
        if (sessionUser.revokedAt() != null || !sessionUser.expiresAt().isAfter(now) || !sessionUser.isActive()) return null;
        if (Duration.between(sessionUser.lastSeenAt(), now).compareTo(TOUCH_EVERY) >= 0) {
            try {
                sessionRepository.updateLastSeen(claims.sessionId(), now);
            } catch (RuntimeException e) {
                log.debug("Touching the session failed", e);
            }
        }
        return new CurrentUser(sessionUser.userId(), sessionUser.businessId(), sessionUser.businessName(), sessionUser.role(), sessionUser.firstName(),
                sessionUser.lastName(), sessionUser.email(), AuthenticationSource.STANDALONE, sessionUser.sessionId());
    }

    private static void authenticate(CurrentUser currentUser) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role().name()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(currentUser, null, authorities));
        TenantContext.set(currentUser.businessId()); // null for the super admin: platform requests run in system mode
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Problems.of(status, code, message, request.getRequestURI()));
    }

    /** A cookie's value, or null. */
    public static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}

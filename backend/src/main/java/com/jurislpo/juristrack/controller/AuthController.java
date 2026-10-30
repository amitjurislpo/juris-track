package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.RequestMetadata;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.exception.UnauthenticatedException;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.RequestAuthenticationFilter;
import com.jurislpo.juristrack.service.AuthService;
import com.jurislpo.juristrack.service.AuthService.SignInResult;
import com.jurislpo.juristrack.service.SessionService;
import com.jurislpo.juristrack.service.SessionService.IssuedSession;
import com.jurislpo.juristrack.service.SessionService.RefreshRefusedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth/*}: standalone sign-in by emailed code. A code request creates an emailed code, signing in creates a
 * session (the {@code jt_at} / {@code jt_rt} cookies), a refresh creates a renewed session, signing out deletes the current one.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** {@code POST /api/v1/auth/otp-requests} body. */
    public record CodeRequest(String email) {}

    /** {@code POST /api/v1/auth/sessions} body: {@code remember} = "keep me signed in". */
    public record SignInRequest(String email, String otp, Boolean remember) {}

    private final AuthService authService;
    private final SessionService sessionService;
    private final Auth auth;

    public AuthController(AuthService authService, SessionService sessionService, Auth auth) {
        this.authService = authService;
        this.sessionService = sessionService;
        this.auth = auth;
    }

    private static String[] setCookieHeaders(List<ResponseCookie> cookies) {
        return cookies.stream().map(ResponseCookie::toString).toArray(String[]::new);
    }

    /**
     * Emails a 6-digit sign-in code to an active account. Always 200 with the same message (it never reveals who has an
     * account); 400 for a missing or invalid email, 429 when too many codes were asked for (per IP address and per email).
     */
    @PostMapping("/otp-requests")
    public ApiResult<Void> requestCode(@RequestBody(required = false) CodeRequest body, HttpServletRequest request) {
        return ApiResult.message(authService.requestCode(body == null ? null : body.email(), RequestMetadata.of(request)));
    }

    /**
     * Signs in with the emailed code: 201 {@code {ok, data: signed-in user}} and the session cookies; 400 for a malformed code,
     * 401 for a wrong, expired or burnt code (always the same message), 429 after too many failures from one IP address.
     */
    @PostMapping("/sessions")
    public ResponseEntity<ApiResult<SignedInUser>> signIn(@RequestBody(required = false) SignInRequest body, HttpServletRequest request) {
        SignInRequest signInRequest = body == null ? new SignInRequest(null, null, null) : body;
        SignInResult result = authService.signIn(signInRequest.email(), signInRequest.otp(), Boolean.TRUE.equals(signInRequest.remember()),
                RequestMetadata.of(request));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, setCookieHeaders(result.session().cookies()))
                .body(ApiResult.success(SignedInUser.of(result.user())));
    }

    /**
     * Renews the session with the refresh cookie (the refresh token rotates): 201 and new cookies, or 401 with the cookies
     * cleared when the session ended, expired, the user was deactivated or the refresh token was used twice.
     */
    @PostMapping("/session-refreshes")
    public ResponseEntity<?> refreshSession(HttpServletRequest request) {
        try {
            IssuedSession session = authService.refresh(RequestAuthenticationFilter.cookieValue(request, SessionService.REFRESH_COOKIE),
                    RequestMetadata.of(request));
            return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, setCookieHeaders(session.cookies())).body(ApiResult.success());
        } catch (RefreshRefusedException e) {
            ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE, request.getRequestURI());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, setCookieHeaders(sessionService.clearingCookies()))
                    .body(problem);
        }
    }

    /** Signs out: always 200; ends this device's session (from the access token, else the refresh cookie) and clears the cookies. */
    @DeleteMapping("/sessions/current")
    public ResponseEntity<ApiResult<Void>> signOut(HttpServletRequest request) {
        authService.signOut(auth.current(), RequestAuthenticationFilter.cookieValue(request, SessionService.REFRESH_COOKIE));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, setCookieHeaders(sessionService.clearingCookies())).body(ApiResult.success());
    }
}

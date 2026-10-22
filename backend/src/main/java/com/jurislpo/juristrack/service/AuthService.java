package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.common.RequestMetadata;
import com.jurislpo.juristrack.config.JuristrackProperties;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import com.jurislpo.juristrack.service.SessionService.IssuedSession;
import com.jurislpo.juristrack.service.SessionService.RefreshRefusedException;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standalone sign-in by emailed code, and sign-out. Login is by email only (unique across every business, so the business
 * comes from the user). Every answer to a code request is the same, so it never reveals whether an account exists or is
 * active; a refused sign-in always says the code is wrong. Runs in system mode (no business is known yet).
 */
@Service
public class AuthService {

    public static final String CODE_REQUESTED = "If that account exists and is active, a 6-digit code is on its way to the email.";
    public static final String WRONG_CODE = "That code is wrong or has expired. Request a new one.";
    public static final String TOO_MANY_CODE_REQUESTS = "Too many code requests. Please wait a few minutes and try again.";
    public static final String TOO_MANY_FAILED_SIGN_INS = "Too many failed sign-in attempts. Please wait a few minutes and try again.";
    static final String EMAIL_MISSING = "Enter your email.";
    static final String EMAIL_INVALID = "Enter a valid email.";
    static final String CODE_INVALID = "Enter the 6-digit code.";
    private static final int MAXIMUM_EMAIL_LENGTH = 200;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9]{6}$");
    private static final String SESSION_ENTITY = "session";

    /** A successful sign-in: the session (with its cookies) and who signed in. */
    public record SignInResult(IssuedSession session, CurrentUser user) {}

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final OtpService otpService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate transactionTemplate;
    private final JuristrackProperties.Otp settings;

    public AuthService(UserRepository userRepository, BusinessRepository businessRepository, OtpService otpService, SessionService sessionService,
            AuditService auditService, RateLimiter rateLimiter, TransactionTemplate transactionTemplate, JuristrackProperties properties) {
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.otpService = otpService;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        this.transactionTemplate = transactionTemplate;
        this.settings = properties.otp();
    }

    private long windowMillis() {
        return settings.windowMinutes() * 60_000L;
    }

    /** Emails a sign-in code to an active account. Always the same answer (unknown and inactive accounts get no code). */
    public String requestCode(String rawEmail, RequestMetadata requestMetadata) {
        String email = normalizeEmail(rawEmail);
        String ipAddress = requestMetadata.ipAddress() != null ? requestMetadata.ipAddress() : "unknown";
        RateLimiter.Result byIpAddress = rateLimiter.checkAndHit("otp-ip:" + ipAddress, settings.requestsPerIp(), windowMillis());
        RateLimiter.Result byEmail = rateLimiter.checkAndHit("otp-email:" + email, settings.requestsPerEmail(), windowMillis());
        if (byIpAddress.isLimited() || byEmail.isLimited()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_CODE_REQUESTS);
        TenantContext.run(null, () -> userRepository.findByEmail(email).filter(User::isActive).ifPresent(user -> {
            otpService.issue(email, user.getFirstName(), requestMetadata.ipAddress());
            auditService.record(new AuditEntry(user.getBusinessId(), user.getId(), user.getId(), AuditAction.SIGN_IN_CODE_REQUESTED, SESSION_ENTITY,
                    null, null, null, null));
        }));
        return CODE_REQUESTED;
    }

    /** Signs in with the emailed code: a new session, {@code last_login_at} and an audit entry, in one transaction. */
    public SignInResult signIn(String rawEmail, String rawCode, boolean isRemembered, RequestMetadata requestMetadata) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!CODE_PATTERN.matcher(code).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, CODE_INVALID);
        String failedSignInKey = "sign-in-ip:" + (requestMetadata.ipAddress() != null ? requestMetadata.ipAddress() : "unknown");
        if (rateLimiter.check(failedSignInKey, settings.failedSignInsPerIp()).isLimited()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_FAILED_SIGN_INS);
        }
        return TenantContext.call(null, () -> {
            User user = userRepository.findByEmail(email).orElse(null);
            boolean isCodeRight = otpService.verify(email, code);
            if (user == null || !user.isActive() || !isCodeRight) {
                rateLimiter.hit(failedSignInKey, windowMillis());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("email", AuditService.maskEmail(email));
                details.put("reason", user == null ? "unknown_email" : !user.isActive() ? "inactive" : "wrong_code");
                auditService.record(new AuditEntry(user != null ? user.getBusinessId() : null, null, user != null ? user.getId() : null,
                        AuditAction.SIGN_IN_FAILED, SESSION_ENTITY, null, null, details, null));
                throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_CODE);
            }
            return transactionTemplate.execute(transactionStatus -> {
                User signingInUser = userRepository.findById(user.getId()).orElseThrow();
                signingInUser.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
                IssuedSession session = sessionService.create(signingInUser.getId(), isRemembered, requestMetadata);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("ipAddress", requestMetadata.ipAddress());
                details.put("userAgent", requestMetadata.userAgent());
                details.put("isRemembered", isRemembered);
                auditService.record(new AuditEntry(signingInUser.getBusinessId(), signingInUser.getId(), signingInUser.getId(), AuditAction.SIGNED_IN,
                        SESSION_ENTITY, session.sessionId(), null, details, null));
                return new SignInResult(session, currentUserOf(signingInUser, session.sessionId()));
            });
        });
    }

    /** Renews the session cookies; a reused refresh token ends the session and is audited. */
    public IssuedSession refresh(String refreshToken, RequestMetadata requestMetadata) {
        try {
            return TenantContext.call(null, () -> sessionService.refresh(refreshToken, requestMetadata));
        } catch (RefreshRefusedException e) {
            if (e.wasReused()) {
                TenantContext.run(null, () -> userRepository.findById(e.userId()).ifPresent(user -> auditService.record(new AuditEntry(
                        user.getBusinessId(), null, user.getId(), AuditAction.SESSION_REVOKED_FOR_REFRESH_REUSE, SESSION_ENTITY, null, null,
                        Map.of("ipAddress", String.valueOf(requestMetadata.ipAddress())), null))));
            }
            throw e;
        }
    }

    /** Ends this device's session: the one in the access token, else the refresh cookie's. Always succeeds. */
    public void signOut(CurrentUser currentUser, String refreshToken) {
        TenantContext.run(null, () -> transactionTemplate.executeWithoutResult(transactionStatus -> {
            if (currentUser != null && currentUser.sessionId() != null) {
                sessionService.revoke(currentUser.sessionId());
                auditService.record(new AuditEntry(currentUser.businessId(), currentUser.userId(), currentUser.userId(), AuditAction.SIGNED_OUT,
                        SESSION_ENTITY, currentUser.sessionId(), null, null, null));
            } else {
                sessionService.revokeByRefreshToken(refreshToken);
            }
        }));
    }

    private CurrentUser currentUserOf(User user, String sessionId) {
        String businessName = user.getBusinessId() == null ? null : businessRepository.findById(user.getBusinessId()).map(Business::getName).orElse(null);
        return new CurrentUser(user.getId(), user.getBusinessId(), businessName, user.getRole(), user.getFirstName(), user.getLastName(), user.getEmail(),
                AuthenticationSource.STANDALONE, sessionId);
    }

    /** Trimmed and lower-cased; 400 when empty, too long or not an email address. */
    static String normalizeEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, EMAIL_MISSING);
        if (email.length() > MAXIMUM_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, EMAIL_INVALID);
        return email;
    }
}

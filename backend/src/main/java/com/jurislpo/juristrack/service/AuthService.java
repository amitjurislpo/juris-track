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
import com.jurislpo.juristrack.service.OtpService.CodeCheck;
import com.jurislpo.juristrack.service.SessionService.IssuedSession;
import com.jurislpo.juristrack.service.SessionService.RefreshRefusedException;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
    /** The rate limits as {@code auth.rate_limited} names them. */
    static final String CODE_REQUESTS_PER_IP_LIMIT = "code_requests_per_ip";
    static final String CODE_REQUESTS_PER_EMAIL_LIMIT = "code_requests_per_email";
    static final String FAILED_SIGN_INS_PER_IP_LIMIT = "failed_sign_ins_per_ip";
    static final String FAILED_SIGN_INS_PER_EMAIL_LIMIT = "failed_sign_ins_per_email";

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

    /**
     * Emails a sign-in code to an active account. Always the same answer (unknown and inactive accounts get no code). Every request is
     * audited: {@code auth.sign_in_code_requested} with outcome SUCCESS when a code was sent, DENIED with the reason otherwise (the
     * caller never learns which); a rate limit hit is recorded once per limit, key and window ({@code auth.rate_limited}).
     */
    public String requestCode(String rawEmail, RequestMetadata requestMetadata) {
        String email = normalizeEmail(rawEmail);
        String ipAddress = requestMetadata.ipAddress() != null ? requestMetadata.ipAddress() : "unknown";
        RateLimiter.Result byIpAddress = rateLimiter.checkAndHit("otp-ip:" + ipAddress, settings.requestsPerIp(), windowMillis());
        RateLimiter.Result byEmail = rateLimiter.checkAndHit("otp-email:" + email, settings.requestsPerEmail(), windowMillis());
        if (byIpAddress.isLimited()) recordRateLimit(CODE_REQUESTS_PER_IP_LIMIT, ipAddress, email, settings.requestsPerIp());
        if (byEmail.isLimited()) recordRateLimit(CODE_REQUESTS_PER_EMAIL_LIMIT, email, email, settings.requestsPerEmail());
        if (byIpAddress.isLimited() || byEmail.isLimited()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_CODE_REQUESTS);
        TenantContext.run(null, () -> {
            User user = userRepository.findByEmail(email).orElse(null);
            String refusal = refusalReason(user);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("email", AuditService.maskEmail(email));
            if (refusal != null) details.put("reason", refusal);
            if (refusal == null) otpService.issue(email, user.getFirstName(), requestMetadata.ipAddress());
            AuditEntry entry = new AuditEntry(user != null ? user.getBusinessId() : null, null, user != null ? user.getId() : null,
                    AuditAction.SIGN_IN_CODE_REQUESTED, SESSION_ENTITY, null, null, details, refusal,
                    refusal == null ? AuditOutcome.SUCCESS : AuditOutcome.DENIED);
            if (refusal == null) {
                auditService.record(entry);
            } else {
                // a refused request (unknown or inactive account) is the one anybody can repeat for free: once per address, IP and window
                details.put("aggregationWindowMinutes", AuditAggregation.WINDOW.toMinutes());
                auditService.recordOncePerWindow(email + "|" + ipAddress, entry);
            }
        });
        return CODE_REQUESTED;
    }

    /**
     * Signs in with the emailed code: a new session, {@code last_login_at} and an audit entry, in one transaction. A refusal is audited
     * as {@code auth.sign_in_failed} with the reason (and {@code auth.sign_in_code_burnt} for the try that used up a code); the caller
     * always gets the same 401.
     */
    public SignInResult signIn(String rawEmail, String rawCode, boolean isRemembered, RequestMetadata requestMetadata) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!CODE_PATTERN.matcher(code).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, CODE_INVALID);
        String ipAddress = requestMetadata.ipAddress() != null ? requestMetadata.ipAddress() : "unknown";
        // every attempt is counted before the code is looked at (check and count are one step, so a parallel burst cannot slip past the
        // limit) and given back when the sign-in succeeds: only failures use up the limits, per IP address and per account
        String failedSignInIpKey = "sign-in-ip:" + ipAddress;
        String failedSignInEmailKey = "sign-in-email:" + email;
        if (rateLimiter.checkAndHit(failedSignInIpKey, settings.failedSignInsPerIp(), windowMillis()).isLimited()) {
            recordRateLimit(FAILED_SIGN_INS_PER_IP_LIMIT, ipAddress, email, settings.failedSignInsPerIp());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_FAILED_SIGN_INS);
        }
        if (rateLimiter.checkAndHit(failedSignInEmailKey, settings.failedSignInsPerEmail(), windowMillis()).isLimited()) {
            rateLimiter.release(failedSignInIpKey);
            recordRateLimit(FAILED_SIGN_INS_PER_EMAIL_LIMIT, email, email, settings.failedSignInsPerEmail());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_FAILED_SIGN_INS);
        }
        return TenantContext.call(null, () -> {
            User user = userRepository.findByEmail(email).orElse(null);
            CodeCheck codeCheck = otpService.verify(email, code);
            String accountRefusal = refusalReason(user);
            String refusal = accountRefusal != null ? accountRefusal : codeRefusalReason(codeCheck);
            if (refusal != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("email", AuditService.maskEmail(email));
                details.put("reason", refusal);
                UUID businessId = user != null ? user.getBusinessId() : null;
                UUID subjectId = user != null ? user.getId() : null;
                auditService.record(new AuditEntry(businessId, null, subjectId, AuditAction.SIGN_IN_FAILED, SESSION_ENTITY, null, null, details, refusal));
                if (codeCheck == CodeCheck.WRONG_CODE_BURNT_NOW) {
                    auditService.record(new AuditEntry(businessId, null, subjectId, AuditAction.SIGN_IN_CODE_BURNT, SESSION_ENTITY, null, null,
                            Map.of("email", AuditService.maskEmail(email), "maximumAttempts", settings.maxAttempts()), "too_many_wrong_codes"));
                }
                throw new ApiException(HttpStatus.UNAUTHORIZED, WRONG_CODE);
            }
            rateLimiter.release(failedSignInIpKey);
            rateLimiter.release(failedSignInEmailKey);
            return transactionTemplate.execute(transactionStatus -> {
                User signingInUser = userRepository.findById(user.getId()).orElseThrow();
                signingInUser.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
                IssuedSession session = sessionService.create(signingInUser.getId(), isRemembered, requestMetadata);
                auditService.record(new AuditEntry(signingInUser.getBusinessId(), signingInUser.getId(), signingInUser.getId(), AuditAction.SIGNED_IN,
                        SESSION_ENTITY, session.sessionId(), null, Map.of("isRemembered", isRemembered), null));
                return new SignInResult(session, currentUserOf(signingInUser, session.sessionId()));
            });
        });
    }

    /**
     * Renews the session cookies. A successful refresh is not audited (it happens every few minutes); a reused refresh token ends the
     * session and is audited, and any other refusal (unknown, revoked or expired session, deactivated user) is recorded once per reason,
     * IP address, user and window ({@code auth.session_refresh_failed}). A request without a refresh cookie is not recorded.
     */
    public IssuedSession refresh(String refreshToken, RequestMetadata requestMetadata) {
        try {
            return TenantContext.call(null, () -> sessionService.refresh(refreshToken, requestMetadata));
        } catch (RefreshRefusedException e) {
            TenantContext.run(null, () -> recordRefusedRefresh(e, requestMetadata));
            throw e;
        }
    }

    private void recordRefusedRefresh(RefreshRefusedException refusal, RequestMetadata requestMetadata) {
        if (SessionService.REFRESH_MISSING.equals(refusal.getMessage())) return;
        User user = refusal.userId() == null ? null : userRepository.findById(refusal.userId()).orElse(null);
        UUID businessId = user != null ? user.getBusinessId() : null;
        UUID subjectId = user != null ? user.getId() : null;
        if (refusal.wasReused()) {
            auditService.record(new AuditEntry(businessId, null, subjectId, AuditAction.SESSION_REVOKED_FOR_REFRESH_REUSE, SESSION_ENTITY, null, null,
                    Map.of("ipAddress", String.valueOf(requestMetadata.ipAddress())), SessionService.REVOKED_FOR_REFRESH_REUSE));
            return;
        }
        auditService.recordOncePerWindow(refusal.getMessage() + "|" + requestMetadata.ipAddress() + "|" + subjectId, new AuditEntry(businessId, null,
                subjectId, AuditAction.SESSION_REFRESH_FAILED, SESSION_ENTITY, null, null, Map.of("reason", refusal.getMessage(),
                        "aggregationWindowMinutes", AuditAggregation.WINDOW.toMinutes()), refusal.getMessage()));
    }

    /** Ends this device's session: the one in the access token, else the refresh cookie's. Always succeeds; an ended session is audited. */
    public void signOut(CurrentUser currentUser, String refreshToken) {
        TenantContext.run(null, () -> transactionTemplate.executeWithoutResult(transactionStatus -> {
            if (currentUser != null && currentUser.sessionId() != null) {
                sessionService.revoke(currentUser.sessionId());
                auditService.record(new AuditEntry(currentUser.businessId(), currentUser.userId(), currentUser.userId(), AuditAction.SIGNED_OUT,
                        SESSION_ENTITY, currentUser.sessionId(), null, null, null));
            } else {
                sessionService.revokeByRefreshToken(refreshToken).flatMap(userRepository::findById).ifPresent(user -> auditService.record(new AuditEntry(
                        user.getBusinessId(), user.getId(), user.getId(), AuditAction.SIGNED_OUT, SESSION_ENTITY, null, null,
                        Map.of("withRefreshCookieOnly", true), null)));
            }
        }));
    }

    /** One entry per limit, key and window: who was limited (the email masked), never the request body. */
    private void recordRateLimit(String limit, String limitedKey, String email, int maximumHits) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("limit", limit);
        details.put("email", AuditService.maskEmail(email));
        details.put("maximumPerWindow", maximumHits);
        details.put("windowMinutes", settings.windowMinutes());
        auditService.recordOncePerWindow(limit + "|" + limitedKey, new AuditEntry(null, null, null, AuditAction.RATE_LIMITED, SESSION_ENTITY, null,
                null, details, limit));
    }

    /**
     * Why this account may not sign in, or null: {@code unknown_email}, {@code inactive}, {@code login_disabled} (the owner switched
     * sign-in off), {@code business_suspended}. Only the audit entry tells them apart; the caller always gets the same answer.
     */
    private String refusalReason(User user) {
        if (user == null) return "unknown_email";
        if (!user.isActive()) return "inactive";
        if (!user.isLoginEnabled()) return "login_disabled";
        return maySignIn(user) ? null : "business_suspended";
    }

    /** Why the code was refused, or null when it matched. */
    private static String codeRefusalReason(CodeCheck codeCheck) {
        return switch (codeCheck) {
            case WRONG_CODE, WRONG_CODE_BURNT_NOW -> "wrong_code";
            case ALREADY_BURNT -> "code_burnt";
            case NO_OPEN_CODE -> "expired_or_used_code";
            case MATCHED -> null;
        };
    }

    /** Active, login not switched off, and their business (if any: the super admin has none) is not suspended. */
    private boolean maySignIn(User user) {
        if (!user.isActive() || !user.isLoginEnabled()) return false;
        return user.getBusinessId() == null || businessRepository.findById(user.getBusinessId()).map(business -> !business.isSuspended()).orElse(false);
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

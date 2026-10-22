package com.jurislpo.juristrack.exception;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Error bodies in RFC 7807 / 9457 form ({@code application/problem+json}):
 * {@code {"type":"about:blank","title","status","detail","instance","code","errors"?}}. {@code detail} is the message
 * the user sees; {@code code} is stable for programs.
 */
public final class Problems {
    private Problems() {}

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    /** 401 because the access token ran out: refresh the session and retry. */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String CONFLICT = "CONFLICT";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String BUSINESS_RULE = "BUSINESS_RULE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL = "INTERNAL_ERROR";
    /** 403: the user's business was suspended by the platform super admin. */
    public static final String BUSINESS_SUSPENDED = "BUSINESS_SUSPENDED";
    /** 409: the email belongs to another user (emails are unique across every business). */
    public static final String EMAIL_IN_USE = "EMAIL_IN_USE";
    /** 409: another user of the business has this employee code. */
    public static final String EMPLOYEE_CODE_IN_USE = "EMPLOYEE_CODE_IN_USE";
    /** 422: the manager is unknown in this business (also a user of another business). */
    public static final String MANAGER_NOT_FOUND = "MANAGER_NOT_FOUND";
    /** 422: the manager is deactivated. */
    public static final String MANAGER_INACTIVE = "MANAGER_INACTIVE";
    /** 422: the manager's role is EMPLOYEE (a manager must be a MANAGER or the owner, ADMIN). */
    public static final String MANAGER_ROLE_REQUIRED = "MANAGER_ROLE_REQUIRED";
    /** 422: an employee cannot be their own manager. */
    public static final String MANAGER_IS_SELF = "MANAGER_IS_SELF";
    /** 422: the manager reports (directly or further down) to the employee, so the reporting line would be a loop. */
    public static final String MANAGER_CYCLE = "MANAGER_CYCLE";
    /** 422: a MANAGER (or the owner) who still has active reports cannot become an EMPLOYEE. */
    public static final String MANAGER_HAS_REPORTS = "MANAGER_HAS_REPORTS";
    /** 422: this role cannot be given (or taken away) here: ADMIN and SUPER_ADMIN are created by the platform only. */
    public static final String ROLE_NOT_ASSIGNABLE = "ROLE_NOT_ASSIGNABLE";
    /** 422: the owner cannot deactivate their own account. */
    public static final String CANNOT_DEACTIVATE_SELF = "CANNOT_DEACTIVATE_SELF";
    /** 422: the business must keep at least one active owner (ADMIN). */
    public static final String LAST_ACTIVE_OWNER = "LAST_ACTIVE_OWNER";
    /** 409: a break or the end of the day before the workday started (a break also after the day ended). */
    public static final String WORKDAY_NOT_STARTED = "WORKDAY_NOT_STARTED";
    /** 409: resume work without a break to end. */
    public static final String NOT_ON_BREAK = "NOT_ON_BREAK";
    /** 409: end the day while on a break; the break must be ended (resume) first. */
    public static final String BREAK_IN_PROGRESS = "BREAK_IN_PROGRESS";
    /** 500: the open workday's intervals do not match its status (an administrator has to correct it). */
    public static final String TIME_RECORD_INCONSISTENT = "TIME_RECORD_INCONSISTENT";

    public record FieldError(String field, String message) {}

    static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> BUSINESS_RULE;
            case 429 -> RATE_LIMITED;
            default -> status.is5xxServerError() ? INTERNAL : "ERROR";
        };
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        problem.setTitle(knownStatus != null ? knownStatus.getReasonPhrase() : "Error");
        if (instance != null) problem.setInstance(URI.create(instance));
        problem.setProperty("code", code != null ? code : defaultCode(status));
        return problem;
    }

    public static ProblemDetail of(HttpStatusCode status, String detail, String instance) {
        return of(status, defaultCode(status), detail, instance);
    }

    public static ProblemDetail withErrors(ProblemDetail problem, List<FieldError> errors) {
        if (errors != null && !errors.isEmpty()) problem.setProperty("errors", errors);
        return problem;
    }
}

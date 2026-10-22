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

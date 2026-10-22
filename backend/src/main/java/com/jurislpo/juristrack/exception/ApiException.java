package com.jurislpo.juristrack.exception;

import org.springframework.http.HttpStatus;

/** An error whose message is shown to the user as-is, with a stable machine-readable {@link #code()}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, Problems.defaultCode(status), message);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }
}

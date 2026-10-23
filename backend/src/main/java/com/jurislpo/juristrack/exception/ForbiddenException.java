package com.jurislpo.juristrack.exception;

import org.springframework.http.HttpStatus;

/** Signed in, but not allowed to do this. */
public class ForbiddenException extends ApiException {
    public static final String MESSAGE = "You don't have permission to do that.";

    public ForbiddenException() { super(HttpStatus.FORBIDDEN, MESSAGE); }
}

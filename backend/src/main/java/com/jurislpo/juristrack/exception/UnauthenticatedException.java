package com.jurislpo.juristrack.exception;

import org.springframework.http.HttpStatus;

/** No valid session. */
public class UnauthenticatedException extends ApiException {
    public static final String MESSAGE = "Your session has ended. Please sign in again.";

    public UnauthenticatedException() { super(HttpStatus.UNAUTHORIZED, MESSAGE); }
}

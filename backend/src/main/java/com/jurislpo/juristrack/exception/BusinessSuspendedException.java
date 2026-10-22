package com.jurislpo.juristrack.exception;

import org.springframework.http.HttpStatus;

/** 403 {@code BUSINESS_SUSPENDED}: the user's business was suspended by the platform super admin. */
public class BusinessSuspendedException extends ApiException {
    public static final String MESSAGE = "Your company's access to JurisTrack is suspended. Please contact support.";

    public BusinessSuspendedException() { super(HttpStatus.FORBIDDEN, Problems.BUSINESS_SUSPENDED, MESSAGE); }
}

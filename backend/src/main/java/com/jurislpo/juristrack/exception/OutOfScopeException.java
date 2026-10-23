package com.jurislpo.juristrack.exception;

import org.springframework.http.HttpStatus;

/**
 * 404 for a record the caller may not see: unknown, in another business, or outside their reporting scope. The caller cannot tell
 * which (so existence is not revealed), but {@link ApiExceptionHandler} records every probe once per user and window as
 * {@code security.out_of_scope_access}, after the request's transaction has ended. {@code recordType} names the kind of record
 * asked for ({@code user}, {@code workday}).
 */
public class OutOfScopeException extends ApiException {

    private final String recordType;

    public OutOfScopeException(String recordType, String message) {
        super(HttpStatus.NOT_FOUND, Problems.NOT_FOUND, message);
        this.recordType = recordType;
    }

    public String recordType() {
        return recordType;
    }
}

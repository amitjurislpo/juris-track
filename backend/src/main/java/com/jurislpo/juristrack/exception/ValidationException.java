package com.jurislpo.juristrack.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/** 400 {@code VALIDATION_FAILED} with one entry per invalid field ({@code errors}); the first one is the message shown. */
public class ValidationException extends ApiException {
    private final List<Problems.FieldError> errors;

    public ValidationException(List<Problems.FieldError> errors) {
        super(HttpStatus.BAD_REQUEST, Problems.VALIDATION_FAILED, errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    public List<Problems.FieldError> errors() { return errors; }
}

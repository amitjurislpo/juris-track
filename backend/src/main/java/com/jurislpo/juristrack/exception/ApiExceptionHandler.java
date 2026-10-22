package com.jurislpo.juristrack.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every error as {@code application/problem+json} (see {@link Problems}): user errors keep their message as
 * {@code detail}, validation lists the field errors, unique violations become 409 "That already exists.", and anything
 * unexpected is logged and answered with a generic 500 (no stack traces or SQL leave the server).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String INVALID = "Some details are invalid.";
    static final String DUPLICATE = "That already exists.";
    static final String GENERIC = "Something went wrong. Please try again.";

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException e, HttpServletRequest request) {
        return respond(Problems.of(e.status(), e.code(), e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ValidationException e, HttpServletRequest request) {
        return respond(Problems.withErrors(Problems.of(e.status(), e.code(), e.getMessage(), request.getRequestURI()), e.errors()));
    }

    /** {@code @PreAuthorize} denial: 401 when signed out, 403 otherwise. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            return respond(Problems.of(HttpStatus.UNAUTHORIZED, UnauthenticatedException.MESSAGE, request.getRequestURI()));
        }
        return respond(Problems.of(HttpStatus.FORBIDDEN, ForbiddenException.MESSAGE, request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Problems.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new Problems.FieldError(fieldError.getField(),
                        fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : INVALID))
                .toList();
        // the first issue is the message shown
        String message = errors.isEmpty()
                ? e.getBindingResult().getAllErrors().stream().findFirst().map(objectError -> objectError.getDefaultMessage()).orElse(INVALID)
                : errors.get(0).message();
        return respond(Problems.withErrors(Problems.of(HttpStatus.BAD_REQUEST, message == null ? INVALID : message, request.getRequestURI()), errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        List<Problems.FieldError> errors = e.getConstraintViolations().stream()
                .map(violation -> new Problems.FieldError(String.valueOf(violation.getPropertyPath()), violation.getMessage()))
                .toList();
        String message = errors.isEmpty() ? INVALID : errors.get(0).message();
        return respond(Problems.withErrors(Problems.of(HttpStatus.BAD_REQUEST, message, request.getRequestURI()), errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleUnreadableRequest(Exception e, HttpServletRequest request) {
        return respond(Problems.of(HttpStatus.BAD_REQUEST, INVALID, request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e, HttpServletRequest request) {
        if (e.getMostSpecificCause() instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
            return respond(Problems.of(HttpStatus.CONFLICT, DUPLICATE, request.getRequestURI()));
        }
        log.error("[api] data integrity {}", request.getRequestURI(), e);
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC, request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        // Spring's own 404 / 405 / 415 ... keep their status and headers (Allow, Accept)
        if (e instanceof ErrorResponse errorResponse && !errorResponse.getStatusCode().is5xxServerError()) {
            String message = switch (errorResponse.getStatusCode().value()) {
                case 404 -> "Not found.";
                case 405 -> "Method not allowed.";
                case 415 -> "Unsupported content type.";
                default -> INVALID;
            };
            return ResponseEntity.status(errorResponse.getStatusCode()).headers(errorResponse.getHeaders())
                    .body(Problems.of(errorResponse.getStatusCode(), message, request.getRequestURI()));
        }
        log.error("[api] {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC, request.getRequestURI()));
    }
}

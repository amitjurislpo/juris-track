package com.jurislpo.juristrack.common;

import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.exception.Problems.FieldError;
import com.jurislpo.juristrack.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks the fields of one request body and collects every problem; {@link #throwIfInvalid()} then answers 400
 * {@code VALIDATION_FAILED} with one entry per field ({@code errors}). Each check returns the cleaned value (trimmed, emails
 * lower-cased, time zones in their canonical form), or null when the field is invalid or empty.
 */
public final class InputValidator {

    /** The longest email the sign-in accepts. */
    public static final int MAXIMUM_EMAIL_LENGTH = 200;
    public static final int MAXIMUM_NAME_LENGTH = 60;
    public static final int MAXIMUM_BUSINESS_NAME_LENGTH = 80;
    public static final int MAXIMUM_EMPLOYEE_CODE_LENGTH = 32;
    public static final int MAXIMUM_REASON_LENGTH = 500;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern EMPLOYEE_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final List<FieldError> errors = new ArrayList<>();

    /** Records a problem with {@code field}. */
    public void reject(String field, String message) {
        errors.add(new FieldError(field, message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** 400 with every problem found so far; nothing when all fields were fine. */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    /** Trimmed text of 1 to {@code maximumLength} characters. */
    public String requiredText(String field, String label, String value, int maximumLength) {
        String trimmedValue = value == null ? "" : value.trim();
        if (trimmedValue.isEmpty()) {
            reject(field, label + " is required.");
            return null;
        }
        if (trimmedValue.length() > maximumLength) {
            reject(field, label + " must be at most " + maximumLength + " characters.");
            return null;
        }
        return trimmedValue;
    }

    /** Trimmed text of at most {@code maximumLength} characters; empty becomes null. */
    public String optionalText(String field, String label, String value, int maximumLength) {
        String trimmedValue = value == null ? "" : value.trim();
        if (trimmedValue.isEmpty()) return null;
        if (trimmedValue.length() > maximumLength) {
            reject(field, label + " must be at most " + maximumLength + " characters.");
            return null;
        }
        return trimmedValue;
    }

    /** A required email, trimmed and lower-cased (the sign-in compares lower-case emails). */
    public String email(String field, String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            reject(field, "Email is required.");
            return null;
        }
        if (email.length() > MAXIMUM_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            reject(field, "Enter a valid email.");
            return null;
        }
        return email;
    }

    /** An optional employee code: letters, digits, dashes and underscores, at most 32 characters; empty becomes null. */
    public String employeeCode(String field, String value) {
        String code = optionalText(field, "Employee code", value, MAXIMUM_EMPLOYEE_CODE_LENGTH);
        if (code != null && !EMPLOYEE_CODE_PATTERN.matcher(code).matches()) {
            reject(field, "Use letters, numbers, dashes or underscores in the employee code.");
            return null;
        }
        return code;
    }

    /**
     * A required time zone, as {@link BusinessCalendar#findZone} accepts it (IANA names and links in any case, three-letter ids,
     * UTC offsets); the canonical id is returned ({@code asia/calcutta} becomes {@code Asia/Calcutta}, {@code IST} becomes
     * {@code Asia/Kolkata}).
     */
    public String timeZone(String field, String value) {
        String trimmedValue = value == null ? "" : value.trim();
        if (trimmedValue.isEmpty()) {
            reject(field, "Time zone is required.");
            return null;
        }
        return BusinessCalendar.findZone(trimmedValue).map(zone -> zone.getId()).orElseGet(() -> {
            reject(field, "Unknown time zone: " + trimmedValue + ".");
            return null;
        });
    }

    /** A whole number between {@code minimum} and {@code maximum}. */
    public Integer wholeNumber(String field, String label, Integer value, int minimum, int maximum) {
        if (value == null) {
            reject(field, label + " is required.");
            return null;
        }
        if (value < minimum || value > maximum) {
            reject(field, label + " must be between " + minimum + " and " + maximum + ".");
            return null;
        }
        return value;
    }
}

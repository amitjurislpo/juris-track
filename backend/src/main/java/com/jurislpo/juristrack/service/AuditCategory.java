package com.jurislpo.juristrack.service;

import java.util.Locale;
import java.util.Optional;

/** The short group of an audit entry ({@code audit_logs.category}), for filtering; every {@link AuditAction} belongs to one. */
public enum AuditCategory {
    /** Sign-in codes, sign-in, sign-out, sessions, rate limits. */
    AUTH,
    /** Employees: added, changed, deactivated, reactivated, and refused changes. */
    PEOPLE,
    /** Businesses and their settings: created, changed, suspended, reactivated. */
    BUSINESS,
    /** Time tracking: start, break, resume, end, reopen, workdays closed by somebody else. */
    TIME,
    /** Refusals for a role, a suspended business, a bad session cookie or a cross-site request. */
    SECURITY,
    /** Somebody read another person's data (lists and details of people and businesses). */
    ACCESS,
    /** Embedded in JurisOne: provisioning from the token and refused tokens. */
    INTEGRATION,
    /** The audit log itself (its CSV exports). */
    AUDIT;

    /** As stored and sent: lower case. */
    public String storedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The category with this stored name (any case), or empty. */
    public static Optional<AuditCategory> fromStoredName(String storedName) {
        for (AuditCategory category : values()) if (category.storedName().equalsIgnoreCase(storedName)) return Optional.of(category);
        return Optional.empty();
    }
}

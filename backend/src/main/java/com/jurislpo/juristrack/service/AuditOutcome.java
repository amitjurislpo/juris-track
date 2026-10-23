package com.jurislpo.juristrack.service;

/** How the audited movement ended ({@code audit_logs.outcome}). */
public enum AuditOutcome {
    /** It happened. */
    SUCCESS,
    /** It was refused: a business rule, a role, a suspended business, a wrong code, a bad token or cookie, a rate limit. */
    DENIED,
    /** It was allowed but went wrong. */
    FAILED
}

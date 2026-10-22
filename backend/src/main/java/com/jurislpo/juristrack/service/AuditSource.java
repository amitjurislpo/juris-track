package com.jurislpo.juristrack.service;

/** Where an audited movement came from ({@code audit_logs.source}). */
public enum AuditSource {
    /** An HTTP request of the standalone app: the cookie session, or a standalone sign-in request. */
    STANDALONE,
    /** An HTTP request with JurisOne's Bearer token (embedded mode). */
    INTEGRATION,
    /** No HTTP request: a command or another background task. */
    SYSTEM
}

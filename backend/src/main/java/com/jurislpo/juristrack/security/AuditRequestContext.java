package com.jurislpo.juristrack.security;

/**
 * The HTTP request the current thread serves, as the audit log records it: request id, IP address, user agent, method and path, and
 * whether the request came with JurisOne's Bearer token. Opened and closed by {@link RequestIdFilter} around every request; outside a
 * request (commands) there is none and {@link #current()} is null. AuditService reads it, so callers never pass it.
 */
public final class AuditRequestContext {

    private static final ThreadLocal<AuditRequestContext> CURRENT = new ThreadLocal<>();

    private final String requestId;
    private final String ipAddress;
    private final String userAgent;
    private final String httpMethod;
    private final String requestPath;
    private volatile boolean isIntegrationRequest;

    AuditRequestContext(String requestId, String ipAddress, String userAgent, String httpMethod, String requestPath) {
        this.requestId = requestId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.httpMethod = httpMethod;
        this.requestPath = requestPath;
    }

    /** The current request's context, or null outside a request. */
    public static AuditRequestContext current() {
        return CURRENT.get();
    }

    static void open(AuditRequestContext context) {
        CURRENT.set(context);
    }

    static void close() {
        CURRENT.remove();
    }

    /** Marks the current request as authenticated by JurisOne's token (no effect outside a request). */
    static void markIntegrationRequest() {
        AuditRequestContext context = CURRENT.get();
        if (context != null) context.isIntegrationRequest = true;
    }

    public String requestId() { return requestId; }

    public String ipAddress() { return ipAddress; }

    public String userAgent() { return userAgent; }

    public String httpMethod() { return httpMethod; }

    public String requestPath() { return requestPath; }

    public boolean isIntegrationRequest() { return isIntegrationRequest; }
}

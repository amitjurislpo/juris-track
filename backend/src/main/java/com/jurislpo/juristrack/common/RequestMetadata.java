package com.jurislpo.juristrack.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's IP address and user agent, for rate limits, sessions and the audit log: the first {@code X-Forwarded-For}
 * hop (the web server in front forwards it), else {@code X-Real-IP}, else the connection's address. The user agent is cut
 * to 400 characters.
 */
public record RequestMetadata(String ipAddress, String userAgent) {

    private static final int MAXIMUM_USER_AGENT_LENGTH = 400;

    public static RequestMetadata of(HttpServletRequest request) {
        String forwardedFor = request.getHeader("x-forwarded-for");
        String ipAddress = forwardedFor != null && !forwardedFor.isBlank() ? forwardedFor.split(",")[0].trim() : request.getHeader("x-real-ip");
        if (ipAddress == null || ipAddress.isBlank()) ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("user-agent");
        return new RequestMetadata(ipAddress, userAgent == null ? null : userAgent.substring(0, Math.min(MAXIMUM_USER_AGENT_LENGTH, userAgent.length())));
    }
}

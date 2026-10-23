package com.jurislpo.juristrack.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's IP address and user agent, for rate limits, sessions and the audit log. The IP address is the one
 * {@code RequestIdFilter} resolved with {@link TrustedProxies} (the connection's address, or behind a trusted proxy the client it
 * forwarded), never a header a caller can write freely; outside that filter it is the connection's address. The user agent is cut
 * to 400 characters.
 */
public record RequestMetadata(String ipAddress, String userAgent) {

    /** The request attribute that holds the resolved client address. */
    public static final String CLIENT_ADDRESS_ATTRIBUTE = "juristrack.clientAddress";
    private static final int MAXIMUM_USER_AGENT_LENGTH = 400;

    public static RequestMetadata of(HttpServletRequest request) {
        String ipAddress = request.getAttribute(CLIENT_ADDRESS_ATTRIBUTE) instanceof String clientAddress ? clientAddress : request.getRemoteAddr();
        String userAgent = request.getHeader("user-agent");
        return new RequestMetadata(ipAddress, userAgent == null ? null : userAgent.substring(0, Math.min(MAXIMUM_USER_AGENT_LENGTH, userAgent.length())));
    }
}

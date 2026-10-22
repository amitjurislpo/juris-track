package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.common.RequestMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and opens its {@link AuditRequestContext}, ahead of the security filters (so refusals by them are
 * recorded with it too). A caller's {@value #REQUEST_ID_HEADER} is kept when it is 1 to 64 letters, digits, {@code .}, {@code _},
 * {@code :} or {@code -} (the JurisOne gateway passes its own); anything else is replaced by a new UUID. The id is echoed in the
 * response's {@value #REQUEST_ID_HEADER} and stored in every audit entry of the request. The IP address and user agent follow
 * {@link RequestMetadata} (the rate limits use the same IP address); the path is stored without the query string, at most 300
 * characters.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final int MAXIMUM_PATH_LENGTH = 300;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String requestId = acceptedRequestId(request.getHeader(REQUEST_ID_HEADER));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        RequestMetadata requestMetadata = RequestMetadata.of(request);
        String path = request.getRequestURI();
        AuditRequestContext.open(new AuditRequestContext(requestId, requestMetadata.ipAddress(), requestMetadata.userAgent(), request.getMethod(),
                path == null ? null : path.substring(0, Math.min(MAXIMUM_PATH_LENGTH, path.length()))));
        try {
            chain.doFilter(request, response);
        } finally {
            AuditRequestContext.close();
        }
    }

    /** The caller's id when it is acceptable, else a new UUID. */
    static String acceptedRequestId(String headerValue) {
        return headerValue != null && REQUEST_ID_PATTERN.matcher(headerValue).matches() ? headerValue : UUID.randomUUID().toString();
    }
}

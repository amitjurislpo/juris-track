package com.jurislpo.juristrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The CSRF defence (with the {@code SameSite=Lax} cookies): every state-changing {@code /api} request must carry an
 * {@code Origin} header equal to {@code juristrack.frontend-origin}, the web app's public URL; otherwise 403
 * {@code CROSS_SITE_BLOCKED}. Requests authenticated by JurisOne's Bearer token are exempt: a browser never sends that header by
 * itself, so there is nothing to forge. Runs after {@link RequestAuthenticationFilter}. Not a Spring bean: SecurityConfig adds it to
 * the security filter chain only.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "Cross-site request blocked.";
    public static final String CODE = "CROSS_SITE_BLOCKED";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String allowedOrigin;
    private final ObjectMapper objectMapper;

    public OriginCheckFilter(String frontendOrigin, ObjectMapper objectMapper) {
        this.allowedOrigin = normalizeOrigin(frontendOrigin);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (isAuthenticatedByIntegrationToken() || (!allowedOrigin.isEmpty() && allowedOrigin.equals(normalizeOrigin(request.getHeader("origin"))))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, CODE, MESSAGE, request.getRequestURI()));
    }

    private static boolean isAuthenticatedByIntegrationToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser
                && currentUser.source() == AuthenticationSource.INTEGRATION;
    }

    /** Lower-cased, without a trailing slash; empty for none. */
    static String normalizeOrigin(String origin) {
        if (origin == null) return "";
        String trimmedOrigin = origin.trim().toLowerCase(Locale.ROOT);
        return trimmedOrigin.endsWith("/") ? trimmedOrigin.substring(0, trimmedOrigin.length() - 1) : trimmedOrigin;
    }
}

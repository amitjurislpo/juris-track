package com.jurislpo.juristrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.BusinessSuspendedException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.exception.UnauthenticatedException;
import com.jurislpo.juristrack.security.RequestAuthenticationFilter.SuspendedBusinessUser;
import com.jurislpo.juristrack.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * A signed-out request to a protected endpoint: 401 as {@code application/problem+json} with code {@code UNAUTHENTICATED}, or
 * {@code TOKEN_EXPIRED} when the access token has merely run out (the client should refresh the session and retry); 403
 * {@code BUSINESS_SUSPENDED} when the session is valid but the user's business is suspended (audited as
 * {@code security.suspended_business_request}, once per user in each aggregation window). A 401 is not audited.
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public JsonAuthEntryPoint(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (request.getAttribute(RequestAuthenticationFilter.BUSINESS_SUSPENDED_ATTRIBUTE) instanceof SuspendedBusinessUser suspendedUser) {
            RequestAuthenticationFilter.recordSuspendedBusinessRequest(auditService, suspendedUser.userId(), suspendedUser.businessId());
            response.setStatus(HttpStatus.FORBIDDEN.value());
            objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, Problems.BUSINESS_SUSPENDED,
                    BusinessSuspendedException.MESSAGE, request.getRequestURI()));
            return;
        }
        boolean hasExpiredToken = Boolean.TRUE.equals(request.getAttribute(RequestAuthenticationFilter.TOKEN_EXPIRED_ATTRIBUTE));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.UNAUTHORIZED,
                hasExpiredToken ? Problems.TOKEN_EXPIRED : Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE, request.getRequestURI()));
    }
}

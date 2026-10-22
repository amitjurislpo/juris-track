package com.jurislpo.juristrack.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.exception.UnauthenticatedException;
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
 * {@code TOKEN_EXPIRED} when the access token has merely run out (the client should refresh the session and retry).
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        boolean hasExpiredToken = Boolean.TRUE.equals(request.getAttribute(RequestAuthenticationFilter.TOKEN_EXPIRED_ATTRIBUTE));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.UNAUTHORIZED,
                hasExpiredToken ? Problems.TOKEN_EXPIRED : Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE, request.getRequestURI()));
    }
}

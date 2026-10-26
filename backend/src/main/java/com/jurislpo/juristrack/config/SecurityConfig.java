package com.jurislpo.juristrack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.exception.ForbiddenException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.SessionRepository;
import com.jurislpo.juristrack.security.AccessTokenService;
import com.jurislpo.juristrack.security.IntegrationTokenVerifier;
import com.jurislpo.juristrack.security.JsonAuthEntryPoint;
import com.jurislpo.juristrack.security.OriginCheckFilter;
import com.jurislpo.juristrack.security.RequestAuthenticationFilter;
import com.jurislpo.juristrack.security.RequestIdFilter;
import com.jurislpo.juristrack.service.AuditService;
import com.jurislpo.juristrack.service.IntegrationProvisioningService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless: no JSESSIONID, no form or basic login. The caller is authenticated by {@link RequestAuthenticationFilter}: the
 * {@code jt_at} session cookie (standalone sign-in by emailed code), or JurisOne's Bearer token when embedded. Only the health
 * endpoint and the sign-in endpoints are open; every other {@code /api/**} call needs a signed-in user and answers 401
 * problem+json without one. Spring's CSRF token is off because the defence is SameSite=Lax cookies + the
 * {@link OriginCheckFilter} on writes, as in JurisOne. Spring Security's default security headers stay on
 * ({@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}, no-cache, HSTS over https).
 * Roles are checked in the controllers ({@code Auth.requireRole}). Not loaded for the one-shot CLI commands, which run without a web server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JsonAuthEntryPoint entryPoint, JuristrackProperties properties,
            AccessTokenService accessTokenService, SessionRepository sessionRepository, IntegrationTokenVerifier integrationTokenVerifier,
            IntegrationProvisioningService integrationProvisioningService, ObjectMapper objectMapper, AuditService auditService) throws Exception {
        RequestAuthenticationFilter authenticationFilter = new RequestAuthenticationFilter(accessTokenService, sessionRepository,
                integrationTokenVerifier, integrationProvisioningService, objectMapper, auditService);
        OriginCheckFilter originCheckFilter = new OriginCheckFilter(properties.frontendOrigin(), objectMapper, auditService);
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .addFilterBefore(authenticationFilter, AnonymousAuthenticationFilter.class)
            .addFilterAfter(originCheckFilter, RequestAuthenticationFilter.class)
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler((request, response, accessDenied) -> {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, Problems.FORBIDDEN, ForbiddenException.MESSAGE,
                        request.getRequestURI()));
            }))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                // signing in, renewing and ending a session work without a valid access token
                .requestMatchers(HttpMethod.POST, "/api/auth/otp-requests", "/api/auth/sessions", "/api/auth/session-refreshes").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/auth/sessions/current").permitAll()
                // the roster sync takes JurisOne's service token and nothing else (RequestAuthenticationFilter); a user or a cookie session is refused
                .requestMatchers("/api/integration/**").hasRole("INTEGRATION_SERVICE")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** CORS: only the web app's origin, with credentials (the browser normally reaches us through the app's /api proxy). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(JuristrackProperties properties) {
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(List.of(properties.frontendOrigin()));
        corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        corsConfiguration.setAllowedHeaders(List.of("Content-Type", "Accept", RequestIdFilter.REQUEST_ID_HEADER));
        corsConfiguration.setExposedHeaders(List.of(RequestIdFilter.REQUEST_ID_HEADER));
        corsConfiguration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource corsConfigurationSource = new UrlBasedCorsConfigurationSource();
        corsConfigurationSource.registerCorsConfiguration("/api/**", corsConfiguration);
        return corsConfigurationSource;
    }
}

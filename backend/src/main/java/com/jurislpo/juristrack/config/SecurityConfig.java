package com.jurislpo.juristrack.config;

import com.jurislpo.juristrack.security.JsonAuthEntryPoint;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless: no JSESSIONID, no form or basic login. Only the health endpoint is open; every {@code /api/**} call needs a
 * signed-in user and answers 401 problem+json without one. The sign-in filters (own emailed-code session cookies, and
 * JurisOne's bearer token when embedded) come with the auth phase. Spring's CSRF token is off because the defence is
 * SameSite=Lax cookies + an Origin check on writes, as in JurisOne.
 * Not loaded for the one-shot CLI commands, which run without a web server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, JsonAuthEntryPoint entryPoint) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
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
        corsConfiguration.setAllowedHeaders(List.of("Content-Type", "Accept"));
        corsConfiguration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource corsConfigurationSource = new UrlBasedCorsConfigurationSource();
        corsConfigurationSource.registerCorsConfiguration("/api/**", corsConfiguration);
        return corsConfigurationSource;
    }
}

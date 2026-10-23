package com.jurislpo.juristrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.security.AccessTokenService;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** The session: a short JWT access token plus a rotating refresh token; reuse detection; sign-out; deactivation applies at once. */
class SessionTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccessTokenService accessTokenService;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID userId;
    String email;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        UUID businessId = testData.createBusiness("Session Test " + UUID.randomUUID());
        email = TestData.uniqueEmail("session");
        userId = testData.createUser(businessId, "EMPLOYEE", email, null);
    }

    String sessionIdOf(UUID ownerId) {
        return jdbcTemplate.queryForObject("SELECT id FROM sessions WHERE user_id = ?", String.class, ownerId);
    }

    @Test
    void theAccessTokenIsASignedJwtOfAboutFiveMinutes() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        String[] parts = cookies.accessCookie().getValue().split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"sub\":\"" + userId + "\"").contains("\"sid\":\"" + sessionIdOf(userId) + "\"").contains("\"iss\":\"juristrack\"");
        assertThat(accessTokenService.lifetime().toSeconds()).isEqualTo(300);
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(userId.toString()));
    }

    @Test
    void anExpiredOrForgedAccessTokenIsRefusedWithProblemJson() throws Exception {
        SignIn.session(mockMvc, email);
        String expiredToken = accessTokenService.issue(userId, sessionIdOf(userId), Instant.now().minusSeconds(301));
        mockMvc.perform(get("/api/me").cookie(new Cookie("jt_at", expiredToken))).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));

        String validToken = accessTokenService.issue(userId, sessionIdOf(userId));
        String forgedToken = validToken.substring(0, validToken.lastIndexOf('.') + 1) + "AAAA";
        mockMvc.perform(get("/api/me").cookie(new Cookie("jt_at", forgedToken))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesTheTokenAndAReusedRefreshTokenEndsTheSession() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        MockHttpServletResponse response = mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ok").value(true)).andReturn().getResponse();
        SessionCookies renewed = SignIn.cookiesOf(response);
        assertThat(renewed.refreshCookie().getValue()).isNotEqualTo(cookies.refreshCookie().getValue());
        mockMvc.perform(get("/api/me").cookie(renewed.accessCookie())).andExpect(status().isOk());

        // the first refresh token was used: presenting it again means it was copied, and the whole session ends
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/me").cookie(renewed.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(renewed.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_reason FROM sessions WHERE user_id = ?", String.class, userId)).isEqualTo("refresh_reuse");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.session_revoked_refresh_reuse' AND subject_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void aRefusedRefreshClearsTheCookies() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(new Cookie("jt_rt", "unknown-token")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(SignIn.setCookieHeader(response, "jt_at")).contains("Max-Age=0");
        assertThat(SignIn.setCookieHeader(response, "jt_rt")).contains("Max-Age=0");
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes"))).andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        MockHttpServletResponse response = mockMvc.perform(SignIn.fromWebApp(delete("/api/auth/sessions/current"))
                        .cookie(cookies.accessCookie(), cookies.refreshCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)).andReturn().getResponse();
        assertThat(SignIn.setCookieHeader(response, "jt_at")).contains("Max-Age=0");
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_reason FROM sessions WHERE user_id = ?", String.class, userId)).isEqualTo("sign_out");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.signed_out' AND actor_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void signingOutWithOnlyTheRefreshCookieEndsTheSessionToo() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        mockMvc.perform(SignIn.fromWebApp(delete("/api/auth/sessions/current")).cookie(cookies.refreshCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
        // signing out twice, or signed out, is fine
        mockMvc.perform(SignIn.fromWebApp(delete("/api/auth/sessions/current"))).andExpect(status().isOk());
    }

    @Test
    void deactivatingAUserStopsTheNextRequestAndTheRefresh() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isOk());
        testData.deactivate(userId);
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_reason FROM sessions WHERE user_id = ?", String.class, userId)).isEqualTo("user_inactive");
    }

    @Test
    void anExpiredSessionCannotBeRefreshed() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, email);
        jdbcTemplate.update("UPDATE sessions SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie())).andExpect(status().isUnauthorized());
    }
}

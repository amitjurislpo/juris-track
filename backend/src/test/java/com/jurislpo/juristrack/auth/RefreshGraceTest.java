package com.jurislpo.juristrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The refresh grace (two requests of one browser at once when the access token ran out): a refresh token used again within the
 * grace gets a new access token only, never a second refresh token, so a copied token replayed in the grace cannot start a parallel
 * refresh chain; after the grace a reuse still ends the session.
 */
@TestPropertySource(properties = "juristrack.security.refresh-reuse-grace-seconds=30")
class RefreshGraceTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
    }

    private MockHttpServletResponse refresh(Cookie refreshCookie) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/session-refreshes")).cookie(refreshCookie)).andReturn().getResponse();
    }

    @Test
    void aReuseInsideTheGraceGetsAnAccessTokenButNoSecondRefreshToken() throws Exception {
        String email = TestData.uniqueEmail("grace");
        UUID userId = new TestData(jdbcTemplate).createUser(new TestData(jdbcTemplate).createBusiness("Grace Co " + UUID.randomUUID()), "EMPLOYEE",
                email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);

        MockHttpServletResponse first = refresh(cookies.refreshCookie());
        assertThat(first.getStatus()).isEqualTo(201);
        String successor = SignIn.cookieValue(first, "jt_rt");
        assertThat(successor).isNotBlank();

        // the same (now used) token a moment later: still signed in, but no new refresh token and the browser's successor is untouched
        MockHttpServletResponse parallel = refresh(cookies.refreshCookie());
        assertThat(parallel.getStatus()).isEqualTo(201);
        assertThat(SignIn.setCookieHeader(parallel, "jt_rt")).isNull();
        String accessToken = SignIn.cookieValue(parallel, "jt_at");
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie("jt_at", accessToken))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens token JOIN sessions session ON session.id = token.session_id "
                + "WHERE session.user_id = ?", Integer.class, userId)).as("the sign-in token and one successor, no fork").isEqualTo(2);

        // the chain goes on with the successor only
        MockHttpServletResponse next = refresh(new Cookie("jt_rt", successor));
        assertThat(next.getStatus()).isEqualTo(201);
        assertThat(SignIn.cookieValue(next, "jt_rt")).isNotBlank().isNotEqualTo(successor);
    }
}

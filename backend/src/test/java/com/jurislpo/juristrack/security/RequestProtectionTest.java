package com.jurislpo.juristrack.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What every request goes through: the Origin check on cookie-authenticated writes, 401 / 403 / 404 problem details, the tenant of
 * the request (a user of business A cannot reach business B's rows through the API) and the security headers.
 */
class RequestProtectionTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID alphaBusinessId;
    UUID betaBusinessId;
    String alphaEmployeeEmail;
    UUID alphaEmployeeId;
    UUID betaEmployeeId;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        alphaBusinessId = testData.createBusiness("Alpha " + UUID.randomUUID());
        betaBusinessId = testData.createBusiness("Beta " + UUID.randomUUID());
        alphaEmployeeEmail = TestData.uniqueEmail("alpha");
        alphaEmployeeId = testData.createUser(alphaBusinessId, "EMPLOYEE", alphaEmployeeEmail, null);
        betaEmployeeId = testData.createUser(betaBusinessId, "EMPLOYEE", TestData.uniqueEmail("beta"), null);
    }

    @Test
    void cookieAuthenticatedWritesNeedTheWebAppsOrigin() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaEmployeeEmail);
        mockMvc.perform(post("/api/test-probes/writes").cookie(cookies.accessCookie()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"))
                .andExpect(jsonPath("$.detail").value("Cross-site request blocked."));
        // a foreign origin is already refused by CORS (only the web app's origin is allowed)
        mockMvc.perform(post("/api/test-probes/writes").cookie(cookies.accessCookie()).header("origin", "https://evil.example.com"))
                .andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(post("/api/test-probes/writes")).cookie(cookies.accessCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Written."));
        // reads need no Origin
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isOk());
    }

    @Test
    void theSignInEndpointsNeedTheOriginToo() throws Exception {
        mockMvc.perform(post("/api/auth/otp-requests").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + alphaEmployeeEmail + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
        mockMvc.perform(post("/api/auth/sessions").header("origin", "http://localhost:9999").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedOutApiCallsAnswer401ProblemDetails() throws Exception {
        mockMvc.perform(get("/api/test-probes/users/" + alphaEmployeeId)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.instance").value("/api/test-probes/users/" + alphaEmployeeId));
        // also for paths that do not exist: nothing is revealed before signing in
        mockMvc.perform(get("/api/no-such-thing")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingRoleIs403AndAnUnknownPathIs404() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaEmployeeEmail);
        mockMvc.perform(get("/api/test-probes/owner-area").cookie(cookies.accessCookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.detail").value("You don't have permission to do that."));
        mockMvc.perform(get("/api/no-such-thing").cookie(cookies.accessCookie())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        String ownerEmail = TestData.uniqueEmail("owner");
        testData.createUser(alphaBusinessId, "ADMIN", ownerEmail, null);
        mockMvc.perform(get("/api/test-probes/owner-area").cookie(SignIn.session(mockMvc, ownerEmail).accessCookie())).andExpect(status().isOk());
    }

    @Test
    void aUserOfOneBusinessCannotReachAnotherBusinessThroughTheApi() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaEmployeeEmail);
        mockMvc.perform(get("/api/test-probes/users/" + alphaEmployeeId).cookie(cookies.accessCookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(alphaEmployeeEmail));
        mockMvc.perform(get("/api/test-probes/users/" + betaEmployeeId).cookie(cookies.accessCookie())).andExpect(status().isNotFound());

        // the super admin works in system mode and sees both businesses
        String superAdminEmail = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        SessionCookies superAdminCookies = SignIn.session(mockMvc, superAdminEmail);
        mockMvc.perform(get("/api/test-probes/users/" + betaEmployeeId).cookie(superAdminCookies.accessCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/test-probes/users/" + alphaEmployeeId).cookie(superAdminCookies.accessCookie())).andExpect(status().isOk());
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/me").cookie(SignIn.session(mockMvc, alphaEmployeeEmail).accessCookie())).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}

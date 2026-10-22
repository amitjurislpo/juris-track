package com.jurislpo.juristrack.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * What every request goes through, shown on the real endpoints: the Origin check on cookie-authenticated writes, 401 / 403 / 404
 * problem details, the tenant of the request (a user of business A cannot reach business B's rows through the API) and the
 * security headers.
 */
class RequestProtectionTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID alphaBusinessId;
    UUID betaBusinessId;
    String alphaOwnerEmail;
    String alphaEmployeeEmail;
    UUID alphaEmployeeId;
    UUID betaEmployeeId;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        alphaBusinessId = testData.createBusiness("Alpha " + UUID.randomUUID());
        betaBusinessId = testData.createBusiness("Beta " + UUID.randomUUID());
        alphaOwnerEmail = TestData.uniqueEmail("alpha.owner");
        testData.createUser(alphaBusinessId, "ADMIN", alphaOwnerEmail, null);
        alphaEmployeeEmail = TestData.uniqueEmail("alpha");
        alphaEmployeeId = testData.createUser(alphaBusinessId, "EMPLOYEE", alphaEmployeeEmail, null);
        betaEmployeeId = testData.createUser(betaBusinessId, "EMPLOYEE", TestData.uniqueEmail("beta"), null);
    }

    @Test
    void cookieAuthenticatedWritesNeedTheWebAppsOrigin() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaOwnerEmail);
        mockMvc.perform(patch("/api/business").cookie(cookies.accessCookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"))
                .andExpect(jsonPath("$.detail").value("Cross-site request blocked."));
        // a foreign origin is already refused by CORS (only the web app's origin is allowed)
        mockMvc.perform(patch("/api/business").cookie(cookies.accessCookie()).header("origin", "https://evil.example.com").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(cookies.accessCookie()).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Settings saved."));
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
        mockMvc.perform(get("/api/employees/" + alphaEmployeeId)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.instance").value("/api/employees/" + alphaEmployeeId));
        // also for paths that do not exist: nothing is revealed before signing in
        mockMvc.perform(get("/api/no-such-thing")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingRoleIs403AndAnUnknownPathIs404() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaEmployeeEmail);
        mockMvc.perform(get("/api/employees").cookie(cookies.accessCookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.detail").value("You don't have permission to do that."));
        mockMvc.perform(get("/api/no-such-thing").cookie(cookies.accessCookie())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/employees").cookie(SignIn.session(mockMvc, alphaOwnerEmail).accessCookie())).andExpect(status().isOk());
    }

    @Test
    void aUserOfOneBusinessCannotReachAnotherBusinessThroughTheApi() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, alphaOwnerEmail);
        mockMvc.perform(get("/api/employees/" + alphaEmployeeId).cookie(cookies.accessCookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(alphaEmployeeEmail));
        mockMvc.perform(get("/api/employees/" + betaEmployeeId).cookie(cookies.accessCookie())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // the super admin works in system mode and sees both businesses (but no business's employee pages)
        String superAdminEmail = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        SessionCookies superAdminCookies = SignIn.session(mockMvc, superAdminEmail);
        mockMvc.perform(get("/api/businesses/" + alphaBusinessId).cookie(superAdminCookies.accessCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/businesses/" + betaBusinessId).cookie(superAdminCookies.accessCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + alphaEmployeeId).cookie(superAdminCookies.accessCookie())).andExpect(status().isForbidden());
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/me").cookie(SignIn.session(mockMvc, alphaEmployeeEmail).accessCookie())).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}

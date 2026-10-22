package com.jurislpo.juristrack.auth;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/me} for every role: {id, firstName, lastName, email, role, businessName}; the super admin has no business. */
class MeTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MANAGER", "EMPLOYEE"})
    void aBusinessUserSeesThemselvesAndTheirBusiness(String role) throws Exception {
        String businessName = "Me Test " + UUID.randomUUID();
        UUID businessId = testData.createBusiness(businessName);
        String email = TestData.uniqueEmail(role.toLowerCase());
        UUID userId = testData.createUser(businessId, role, email, null);
        mockMvc.perform(get("/api/me").cookie(SignIn.session(mockMvc, email).accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.firstName").value("Test"))
                .andExpect(jsonPath("$.data.lastName").value("Person"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value(role))
                .andExpect(jsonPath("$.data.businessName").value(businessName));
    }

    @Test
    void theSuperAdminHasNoBusiness() throws Exception {
        String email = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", email, null);
        mockMvc.perform(get("/api/me").cookie(SignIn.session(mockMvc, email).accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.businessName").value(nullValue()));
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}

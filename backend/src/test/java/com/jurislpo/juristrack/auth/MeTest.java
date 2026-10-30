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

/**
 * {@code GET /api/v1/me} for every role: {id, firstName, lastName, email, role, businessName, employeeCode, managerName, businessTimeZone};
 * the super admin has no business.
 */
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
        mockMvc.perform(get("/api/v1/me").cookie(SignIn.session(mockMvc, email).accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.firstName").value("Test"))
                .andExpect(jsonPath("$.data.lastName").value("Person"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value(role))
                .andExpect(jsonPath("$.data.businessName").value(businessName))
                .andExpect(jsonPath("$.data.employeeCode").value(nullValue()))
                .andExpect(jsonPath("$.data.managerName").value(nullValue()))
                .andExpect(jsonPath("$.data.businessTimeZone").value("Asia/Kolkata"));
    }

    @Test
    void theProfileAddsTheEmployeeCodeTheManagerAndTheBusinessTimeZone() throws Exception {
        UUID businessId = testData.createBusiness("Me Profile " + UUID.randomUUID());
        jdbcTemplate.update("UPDATE businesses SET time_zone = 'Europe/London' WHERE id = ?", businessId);
        UUID managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("manager"), null, "Manish", "Manager", "M-1");
        String email = TestData.uniqueEmail("report");
        testData.createUser(businessId, "EMPLOYEE", email, managerId, "Asha", "Rao", "E-7");
        mockMvc.perform(get("/api/v1/me").cookie(SignIn.session(mockMvc, email).accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstName").value("Asha"))
                .andExpect(jsonPath("$.data.employeeCode").value("E-7"))
                .andExpect(jsonPath("$.data.managerName").value("Manish Manager"))
                .andExpect(jsonPath("$.data.businessTimeZone").value("Europe/London"));
    }

    @Test
    void theSuperAdminHasNoBusiness() throws Exception {
        String email = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", email, null);
        mockMvc.perform(get("/api/v1/me").cookie(SignIn.session(mockMvc, email).accessCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.businessName").value(nullValue()))
                .andExpect(jsonPath("$.data.businessTimeZone").value(nullValue()));
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}

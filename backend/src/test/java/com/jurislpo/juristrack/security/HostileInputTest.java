package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Hostile input on the endpoints that take input: SQL and script text in query parameters and paths is refused or harmless (never a 5xx,
 * never data of another business), and ids of another business's rows answer 404 on reads and writes alike (IDOR).
 */
class HostileInputTest extends IntegrationTest {

    private static final List<String> HOSTILE_TEXTS = List.of("' OR '1'='1", "'; DROP TABLE users; --", "1; SELECT pg_sleep(5)--",
            "<script>alert(1)</script>", "../../etc/passwd", "%00", "a".repeat(5000), "\\u0000");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    UUID alphaBusinessId;
    UUID betaBusinessId;
    UUID betaEmployeeId;
    UUID betaShiftId;
    UUID betaAuditLogId;
    Cookie alphaOwnerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        alphaBusinessId = testData.createBusiness("Hostile Alpha " + UUID.randomUUID());
        betaBusinessId = testData.createBusiness("Hostile Beta " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("hostile.owner");
        testData.createUser(alphaBusinessId, "ADMIN", ownerEmail, null);
        betaEmployeeId = testData.createUser(betaBusinessId, "EMPLOYEE", TestData.uniqueEmail("hostile.beta"), null);
        betaShiftId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO shifts (id, business_id, name, start_minute, end_minute) VALUES (?, ?, 'Beta shift', 360, 1200)", betaShiftId, betaBusinessId);
        betaAuditLogId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO audit_logs (id, business_id, action, entity_type, category, outcome, source) VALUES (?, ?, 'test.beta', 'test', 'audit', 'SUCCESS', 'SYSTEM')",
                betaAuditLogId, betaBusinessId);
        alphaOwnerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.cookie(alphaOwnerCookie)).andReturn().getResponse().getStatus();
    }

    @Test
    void hostileQueryParametersNeverCauseAServerErrorOrLeakAnotherBusiness() throws Exception {
        int usersBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class);
        List<String> parameterNames = List.of("search", "status", "role", "managerId", "sort", "sortDirection", "page", "pageSize", "from", "to", "month",
                "employeeId", "action", "entityId", "businessId", "format");
        for (String path : List.of("/api/v1/employees", "/api/v1/audit-logs", "/api/v1/reports/workdays", "/api/v1/reports/summaries", "/api/v1/shifts",
                "/api/v1/schedule-entries", "/api/v1/workdays/mine")) {
            for (String parameterName : parameterNames) {
                for (String hostileText : HOSTILE_TEXTS) {
                    assertThat(statusOf(get(path).queryParam(parameterName, hostileText))).as(path + "?" + parameterName + "=" + hostileText).isLessThan(500);
                }
            }
        }
        // nothing was dropped, and an injected filter did not widen the list to the other business
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
        mockMvc.perform(get("/api/v1/employees").queryParam("search", "' OR '1'='1").cookie(alphaOwnerCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        mockMvc.perform(get("/api/v1/audit-logs").queryParam("businessId", betaBusinessId.toString()).cookie(alphaOwnerCookie))
                .andExpect(jsonPath("$.data.rows[?(@.action == 'test.beta')]").isEmpty());
    }

    @Test
    void malformedIdsInThePathAreRefusedNotServerErrors() throws Exception {
        for (String hostileId : List.of("not-a-uuid", "1%27%20OR%20%271%27=%271", "%3Cscript%3E", "00000000-0000-0000-0000-000000000000")) {
            for (String path : List.of("/api/v1/employees/", "/api/v1/shifts/", "/api/v1/audit-logs/", "/api/v1/workdays/", "/api/v1/team/members/", "/api/v1/businesses/")) {
                assertThat(statusOf(get(path + hostileId))).as(path + hostileId).isBetween(400, 404);
            }
        }
    }

    @Test
    void rowsOfAnotherBusinessAnswer404OnReadsAndWrites() throws Exception {
        String body = "{\"firstName\":\"Hacked\",\"name\":\"Hacked\"}";
        assertThat(statusOf(get("/api/v1/employees/" + betaEmployeeId))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(patch("/api/v1/employees/" + betaEmployeeId)).content(body))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(put("/api/v1/employees/" + betaEmployeeId + "/status")).content("{\"isActive\":false}"))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(put("/api/v1/employees/" + betaEmployeeId + "/login-access")).content("{\"hasLoginAccess\":false}"))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(put("/api/v1/employees/" + betaEmployeeId + "/shift")).content("{\"shiftId\":\"" + betaShiftId + "\"}"))).isEqualTo(404);
        assertThat(statusOf(get("/api/v1/shifts/" + betaShiftId))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(patch("/api/v1/shifts/" + betaShiftId)).content(body))).isEqualTo(404);
        assertThat(statusOf(SignIn.fromWebApp(put("/api/v1/shifts/" + betaShiftId + "/status")).content("{\"isActive\":false}"))).isEqualTo(404);
        assertThat(statusOf(get("/api/v1/audit-logs/" + betaAuditLogId))).isEqualTo(404);
        assertThat(statusOf(get("/api/v1/team/members/" + betaEmployeeId))).isBetween(403, 404);
        assertThat(statusOf(get("/api/v1/businesses/" + betaBusinessId))).isBetween(403, 404);
        assertThat(statusOf(SignIn.fromWebApp(patch("/api/v1/businesses/" + betaBusinessId)).content(body))).isBetween(403, 404);
        // and nothing of the other business changed
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, betaEmployeeId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM shifts WHERE id = ?", String.class, betaShiftId)).isEqualTo("Beta shift");
    }
}

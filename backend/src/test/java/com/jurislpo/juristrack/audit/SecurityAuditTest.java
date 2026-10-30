package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.security.AccessTokenService;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.EmployeeService;
import com.jurislpo.juristrack.service.EmployeeService.EmployeeStatusChange;
import com.jurislpo.juristrack.service.RateLimiter;
import com.jurislpo.juristrack.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Security refusals are audited with outcome DENIED and stay although the request fails: a 403 for a role, a suspended business (once per
 * user and window), a forged cookie or one of an ended session (once per reason, IP address and window), a cross-site write, and the
 * business rules that refuse a change. A plain 401 without a session, an access token that merely ran out, and validation errors are not
 * audited.
 */
class SecurityAuditTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    @Autowired AccessTokenService accessTokenService;
    @Autowired EmployeeService employeeService;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    Cookie ownerCookie;
    String employeeEmail;
    UUID employeeId;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Security Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("security.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        employeeEmail = TestData.uniqueEmail("security.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, ownerId);
    }

    @Test
    void aForbiddenRequestIsAuditedWithTheActorAndTheRequest() throws Exception {
        Cookie employeeCookie = SignIn.session(mockMvc, employeeEmail).accessCookie();
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/v1/employees")).cookie(employeeCookie)).andExpect(status().isForbidden());
        assertThat(trace.entry("security.access_denied")).containsEntry("outcome", "DENIED").containsEntry("category", "security")
                .containsEntry("actor_id", employeeId).containsEntry("actor_role", "EMPLOYEE").containsEntry("business_id", businessId)
                .containsEntry("reason", "FORBIDDEN").containsEntry("http_method", "GET").containsEntry("request_path", "/api/v1/employees");

        // a write refused for the role: the entry stays although nothing was changed
        AuditTrace writeTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(writeTrace.on(SignIn.fromWebApp(patch("/api/v1/employees/" + ownerId))).cookie(employeeCookie).content("{\"firstName\":\"X\"}"))
                .andExpect(status().isForbidden());
        assertThat(writeTrace.entry("security.access_denied")).containsEntry("http_method", "PATCH");
        assertThat(writeTrace.count("employee.updated")).isZero();
    }

    @Test
    void theSuperAdminRefusedTimeTrackingIsAPlatformEntry() throws Exception {
        String superAdminEmail = TestData.uniqueEmail("security.platform");
        UUID superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/v1/me/time-events"))).cookie(superAdminCookie).content("{\"type\":\"START\"}"))
                .andExpect(status().isForbidden());
        Map<String, Object> entry = trace.entry("security.access_denied");
        assertThat(entry).containsEntry("actor_id", superAdminId).containsEntry("actor_role", "SUPER_ADMIN");
        assertThat(entry.get("business_id")).isNull();
    }

    @Test
    void requestsOfASuspendedBusinessAreAuditedOncePerWindow() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, employeeEmail);
        testData.suspendBusiness(businessId);
        AuditTrace firstTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(firstTrace.on(get("/api/v1/me/time")).cookie(cookies.accessCookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        assertThat(firstTrace.entry("security.suspended_business_request")).containsEntry("outcome", "DENIED").containsEntry("business_id", businessId)
                .containsEntry("actor_id", employeeId).containsEntry("reason", "BUSINESS_SUSPENDED");
        AuditTrace secondTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(secondTrace.on(get("/api/v1/me/time")).cookie(cookies.accessCookie())).andExpect(status().isForbidden());
        assertThat(secondTrace.entries()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'security.suspended_business_request' AND actor_id = ?",
                Integer.class, employeeId)).isEqualTo(1);
    }

    @Test
    void aForgedCookieIsAuditedOncePerWindowWithoutItsContent() throws Exception {
        SignIn.session(mockMvc, employeeEmail);
        String sessionId = jdbcTemplate.queryForObject("SELECT id FROM sessions WHERE user_id = ?", String.class, employeeId);
        String validToken = accessTokenService.issue(employeeId, sessionId);
        String forgedToken = validToken.substring(0, validToken.lastIndexOf('.') + 1) + "AAAA";
        String ipAddress = AuditTrace.newIpAddress();
        AuditTrace firstTrace = new AuditTrace(jdbcTemplate, ipAddress);
        mockMvc.perform(firstTrace.on(get("/api/v1/me")).cookie(new Cookie("jt_at", forgedToken))).andExpect(status().isUnauthorized());
        Map<String, Object> entry = firstTrace.entry("security.invalid_session_cookie");
        assertThat(entry).containsEntry("reason", "invalid_token").containsEntry("outcome", "DENIED");
        assertThat(entry.toString()).doesNotContain(forgedToken).doesNotContain("AAAA");
        AuditTrace secondTrace = new AuditTrace(jdbcTemplate, ipAddress);
        mockMvc.perform(secondTrace.on(get("/api/v1/me")).cookie(new Cookie("jt_at", forgedToken))).andExpect(status().isUnauthorized());
        assertThat(secondTrace.entries()).isEmpty();
    }

    @Test
    void aCookieOfAnEndedSessionIsAuditedButAnExpiredTokenAndNoCookieAreNot() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, employeeEmail);
        String sessionId = jdbcTemplate.queryForObject("SELECT id FROM sessions WHERE user_id = ?", String.class, employeeId);
        // an access token that ran out is the normal refresh flow
        AuditTrace expiredTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(expiredTrace.on(get("/api/v1/me")).cookie(new Cookie("jt_at", accessTokenService.issue(employeeId, sessionId, Instant.now().minusSeconds(301)))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        // signed out: nothing to record
        AuditTrace signedOutTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(signedOutTrace.on(get("/api/v1/me/time"))).andExpect(status().isUnauthorized());
        assertThat(expiredTrace.entries()).isEmpty();
        assertThat(signedOutTrace.entries()).isEmpty();

        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status"))
                .cookie(ownerCookie).content("{\"isActive\":false}")).andExpect(status().isOk());
        AuditTrace endedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(endedTrace.on(get("/api/v1/me")).cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
        assertThat(endedTrace.entry("security.invalid_session_cookie")).containsEntry("reason", "session_ended").containsEntry("subject_id", employeeId)
                .containsEntry("business_id", businessId);
    }

    @Test
    void aCookieWriteWithoutTheWebAppsOriginIsAudited() throws Exception {
        // (a foreign Origin is already refused by the CORS check before; this is a write that names no origin at all)
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(patch("/api/v1/business")).cookie(ownerCookie).contentType("application/json").content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
        Map<String, Object> entry = trace.entry("security.cross_site_request_blocked");
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("business_id", businessId).containsEntry("reason", "CROSS_SITE_BLOCKED")
                .containsEntry("http_method", "PATCH");
        assertThat(trace.count("business.updated")).isZero();
        AuditTrace repeatTrace = new AuditTrace(jdbcTemplate, trace.ipAddress);
        mockMvc.perform(repeatTrace.on(patch("/api/v1/business")).cookie(ownerCookie).contentType("application/json").content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
        assertThat(repeatTrace.entries()).isEmpty();
    }

    @Test
    void theLastActiveOwnerRefusalIsRecordedAlthoughTheChangeRollsBack() {
        // only reachable outside the owner's own request (see EmployeeWriteTest): an ADMIN who is no longer active acts
        UUID formerOwnerId = testData.createUser(businessId, "ADMIN", TestData.uniqueEmail("security.former"), null);
        testData.deactivate(formerOwnerId);
        CurrentUser formerOwner = new CurrentUser(formerOwnerId, businessId, "Security Co", Role.ADMIN, "Former", "Owner", "former@test.juristrack.test",
                AuthenticationSource.INTEGRATION, null);
        assertThatThrownBy(() -> TenantContext.run(businessId, () -> employeeService.setStatus(formerOwner, ownerId, new EmployeeStatusChange(false, null))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LAST_ACTIVE_OWNER"));
        assertThat(jdbcTemplate.queryForMap("SELECT outcome, reason, actor_id, source FROM audit_logs WHERE action = 'employee.change_refused' AND subject_id = ?",
                ownerId)).containsEntry("outcome", "DENIED").containsEntry("reason", "LAST_ACTIVE_OWNER").containsEntry("actor_id", formerOwnerId)
                .containsEntry("source", "SYSTEM");
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, ownerId)).isTrue();
    }

    @Test
    void validationErrorsAndConflictsAreNotAudited() throws Exception {
        AuditTrace invalidTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(invalidTrace.on(SignIn.fromWebApp(post("/api/v1/employees"))).cookie(ownerCookie).content("{\"firstName\":\"\"}"))
                .andExpect(status().isBadRequest());
        AuditTrace conflictTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(conflictTrace.on(SignIn.fromWebApp(post("/api/v1/employees"))).cookie(ownerCookie)
                .content("{\"firstName\":\"Same\",\"lastName\":\"Mail\",\"email\":\"" + employeeEmail + "\"}")).andExpect(status().isConflict());
        assertThat(invalidTrace.entries()).isEmpty();
        assertThat(conflictTrace.entries()).isEmpty();
    }

    @Test
    void aTimeEventOnAnInconsistentRecordIsAuditedAsFailed() throws Exception {
        Cookie employeeCookie = SignIn.session(mockMvc, employeeEmail).accessCookie();
        // a WORKING workday without its open work session (as only a broken correction could leave it)
        jdbcTemplate.update("INSERT INTO workdays (business_id, employee_id, work_date, time_zone, started_at, active_employee_id) "
                + "VALUES (?, ?, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'Asia/Kolkata', now() - interval '1 hour', ?)", businessId, employeeId, employeeId);
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/v1/me/time-events"))).cookie(employeeCookie).content("{\"type\":\"BREAK\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TIME_RECORD_INCONSISTENT"));
        Map<String, Object> entry = trace.entry("time.event_failed");
        assertThat(entry).containsEntry("outcome", "FAILED").containsEntry("actor_id", employeeId).containsEntry("reason", "TIME_RECORD_INCONSISTENT");
        assertThat((String) entry.get("after_json")).contains("\"type\": \"BREAK\"");
        assertThat(trace.count("time.break_started")).isZero();
    }
}

package com.jurislpo.juristrack.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuthService;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code PUT /api/employees/{id}/login-access}: the owner switches a person's sign-in off and on without deactivating them. Switching
 * off ends their sessions, closes their open workday and refuses every sign-in like an unknown account; the person stays in the list.
 */
class LoginAccessTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID managerId;
    UUID employeeId;
    String employeeEmail;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Login Access Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("login.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", null);
        managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("login.manager"), ownerId, "Manish", "Manager", null);
        employeeEmail = TestData.uniqueEmail("login.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, managerId, "Esha", "Employee", null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        clock.setTo("2026-10-01T03:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private ResultActions setLoginAccess(Cookie cookie, UUID targetId, String json) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(put("/api/employees/" + targetId + "/login-access")).cookie(cookie).content(json));
    }

    private void startWork(Cookie cookie) throws Exception {
        mockMvc.perform(SignIn.fromWebApp(post("/api/me/time-events")).cookie(cookie).content("{\"type\":\"START\"}")).andExpect(status().isCreated());
    }

    private int auditCount(String action) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND subject_id = ?", Integer.class, action, employeeId);
    }

    private Map<String, Object> auditEntry(String action) {
        return jdbcTemplate.queryForMap("SELECT actor_id, entity_type, entity_id, \"before\"::text AS before_json, \"after\"::text AS after_json, reason "
                + "FROM audit_logs WHERE action = ? AND subject_id = ? ORDER BY created_at DESC LIMIT 1", action, employeeId);
    }

    @Test
    void newPeopleCanSignInAndTheFlagIsOnTheListAndTheDetail() throws Exception {
        mockMvc.perform(get("/api/employees/" + employeeId).cookie(ownerCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isLoginEnabled").value(true));
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false}").andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + employeeId).cookie(ownerCookie)).andExpect(jsonPath("$.data.isLoginEnabled").value(false))
                .andExpect(jsonPath("$.data.isActive").value(true));
        mockMvc.perform(get("/api/employees?search=Esha").cookie(ownerCookie)).andExpect(jsonPath("$.data.rows[0].isLoginEnabled").value(false))
                .andExpect(jsonPath("$.data.rows[0].isActive").value(true));
    }

    @Test
    void disablingEndsEverySessionClosesTheWorkdayAndKeepsThePersonInTheList() throws Exception {
        SessionCookies firstDevice = SignIn.session(mockMvc, employeeEmail);
        SessionCookies secondDevice = SignIn.session(mockMvc, employeeEmail);
        startWork(firstDevice.accessCookie());
        clock.advance(Duration.ofHours(2));

        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false,\"reason\":\"On leave, no access\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Login disabled.")).andExpect(jsonPath("$.data.isLoginEnabled").value(false))
                .andExpect(jsonPath("$.data.isActive").value(true));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, employeeId)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT revoked_reason FROM sessions WHERE user_id = ?", String.class, employeeId)).containsOnly("login_disabled");
        mockMvc.perform(get("/api/me").cookie(firstDevice.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").cookie(secondDevice.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(secondDevice.refreshCookie())).andExpect(status().isUnauthorized());

        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT status::text AS status, active_employee_id, productive_seconds FROM workdays WHERE employee_id = ?",
                employeeId);
        assertThat(workday).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", 7200);
        assertThat(workday.get("active_employee_id")).isNull();
        assertThat(jdbcTemplate.queryForMap("SELECT actor_id, reason FROM audit_logs WHERE action = 'workday.closed' AND subject_id = ?", employeeId))
                .containsEntry("actor_id", ownerId).containsEntry("reason", "login_disabled");

        Map<String, Object> entry = auditEntry("employee.login_disabled");
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("entity_type", "user").containsEntry("entity_id", employeeId.toString())
                .containsEntry("reason", "On leave, no access");
        assertThat((String) entry.get("before_json")).contains("\"isLoginEnabled\": true");
        assertThat((String) entry.get("after_json")).contains("\"isLoginEnabled\": false").contains("\"endedSessions\": 2").contains("\"closedWorkdayId\"");

        // still in the business: the default list (active people) and the detail show them
        mockMvc.perform(get("/api/employees/" + employeeId).cookie(ownerCookie)).andExpect(status().isOk());
    }

    @Test
    void aDisabledPersonGetsTheSameAnswersAsAnUnknownAccountAndNoCode() throws Exception {
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false}").andExpect(status().isOk());
        String unknownEmail = TestData.uniqueEmail("nobody");
        String answerForUnknown = SignIn.requestCode(mockMvc, unknownEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String answerForDisabled = SignIn.requestCode(mockMvc, employeeEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(answerForDisabled).isEqualTo(answerForUnknown);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, employeeEmail)).isZero();

        String unknownBody = SignIn.postCode(mockMvc, unknownEmail, SignIn.CODE, true).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE)).andReturn().getResponse().getContentAsString();
        String disabledBody = SignIn.postCode(mockMvc, employeeEmail, SignIn.CODE, true).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE)).andReturn().getResponse().getContentAsString();
        assertThat(disabledBody).isEqualTo(unknownBody);

        assertThat(jdbcTemplate.queryForMap("SELECT outcome, reason FROM audit_logs WHERE action = 'auth.sign_in_code_requested' AND subject_id = ?", employeeId))
                .containsEntry("outcome", "DENIED").containsEntry("reason", "login_disabled");
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM audit_logs WHERE action = 'auth.sign_in_failed' AND subject_id = ?", String.class, employeeId))
                .isEqualTo("login_disabled");
    }

    @Test
    void enablingRestoresSignInOnlyAndNothingReopens() throws Exception {
        startWork(SignIn.session(mockMvc, employeeEmail).accessCookie());
        clock.advance(Duration.ofHours(1));
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false}").andExpect(status().isOk());
        rateLimiter.clear();

        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":true,\"reason\":\"Back\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Login enabled.")).andExpect(jsonPath("$.data.isLoginEnabled").value(true));
        assertThat(auditEntry("employee.login_enabled")).containsEntry("actor_id", ownerId).containsEntry("reason", "Back");
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE employee_id = ?", String.class, employeeId)).isEqualTo("COMPLETED");
        mockMvc.perform(get("/api/me").cookie(SignIn.session(mockMvc, employeeEmail).accessCookie())).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ? AND active_employee_id IS NOT NULL", Integer.class,
                employeeId)).isZero();
    }

    @Test
    void settingTheStateThePersonAlreadyHasChangesNothingAndWritesNoAudit() throws Exception {
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.data.isLoginEnabled").value(true));
        assertThat(auditCount("employee.login_enabled")).isZero();
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false}").andExpect(status().isOk());
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false,\"reason\":\"again\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.isLoginEnabled").value(false));
        assertThat(auditCount("employee.login_disabled")).isEqualTo(1);
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":true}").andExpect(status().isOk());
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":true}").andExpect(status().isOk());
        assertThat(auditCount("employee.login_enabled")).isEqualTo(1);
    }

    @Test
    void theOwnerCannotSwitchOffTheirOwnLoginButAnotherOwnersIsFine() throws Exception {
        setLoginAccess(ownerCookie, ownerId, "{\"isEnabled\":false}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_OWN_LOGIN"));
        assertThat(jdbcTemplate.queryForObject("SELECT is_login_enabled FROM users WHERE id = ?", Boolean.class, ownerId)).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT reason, outcome FROM audit_logs WHERE action = 'employee.change_refused' AND subject_id = ?", ownerId))
                .containsEntry("reason", "CANNOT_DISABLE_OWN_LOGIN").containsEntry("outcome", "DENIED");
        // switching it "on" when it is on is a no-op, not an error
        setLoginAccess(ownerCookie, ownerId, "{\"isEnabled\":true}").andExpect(status().isOk());
        UUID secondOwnerId = testData.createUser(businessId, "ADMIN", TestData.uniqueEmail("login.second.owner"), null);
        setLoginAccess(ownerCookie, secondOwnerId, "{\"isEnabled\":false}").andExpect(status().isOk());
    }

    @Test
    void badInputIs400() throws Exception {
        setLoginAccess(ownerCookie, employeeId, "{\"reason\":\"x\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("isEnabled"));
        setLoginAccess(ownerCookie, employeeId, "{\"isEnabled\":false,\"reason\":\"" + "r".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mockMvc.perform(SignIn.fromWebApp(put("/api/employees/" + employeeId + "/login-access")).cookie(ownerCookie)).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT is_login_enabled FROM users WHERE id = ?", Boolean.class, employeeId)).isTrue();
    }

    @Test
    void anotherBusinessesPersonIs404AndTheSuperAdminIs404() throws Exception {
        UUID otherBusinessId = testData.createBusiness("Isolated " + UUID.randomUUID());
        UUID strangerId = testData.createUser(otherBusinessId, "EMPLOYEE", TestData.uniqueEmail("stranger"), null);
        setLoginAccess(ownerCookie, strangerId, "{\"isEnabled\":false}").andExpect(status().isNotFound());
        UUID superAdminId = testData.createUser(null, "SUPER_ADMIN", TestData.uniqueEmail("platform"), null);
        setLoginAccess(ownerCookie, superAdminId, "{\"isEnabled\":false}").andExpect(status().isNotFound());
        setLoginAccess(ownerCookie, UUID.randomUUID(), "{\"isEnabled\":false}").andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT is_login_enabled FROM users WHERE id = ?", Boolean.class, strangerId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ?", Integer.class, strangerId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "SUPER_ADMIN"})
    void onlyTheOwnerSwitchesLoginAccess(String role) throws Exception {
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser("SUPER_ADMIN".equals(role) ? null : businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        setLoginAccess(cookie, employeeId, "{\"isEnabled\":false}").andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT is_login_enabled FROM users WHERE id = ?", Boolean.class, employeeId)).isTrue();
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/employees/" + employeeId + "/login-access")).content("{\"isEnabled\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionOpenedWhileLoginWasSwitchedOffByHandIsRefusedAtOnce() throws Exception {
        SessionCookies device = SignIn.session(mockMvc, employeeEmail);
        // the flag flips without the sessions being ended (as a race with a sign-in would leave it)
        jdbcTemplate.update("UPDATE users SET is_login_enabled = false WHERE id = ?", employeeId);
        mockMvc.perform(get("/api/me").cookie(device.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(device.refreshCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_reason FROM sessions WHERE user_id = ?", String.class, employeeId)).isEqualTo("login_disabled");
    }
}

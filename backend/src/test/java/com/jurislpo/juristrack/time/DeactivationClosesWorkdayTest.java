package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.RateLimiter;
import com.jurislpo.juristrack.service.TimeTrackingService;
import com.jurislpo.juristrack.service.TimeTrackingService.TimeEventInput;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deactivating an employee ("Deactivation" of the old verify-flows checks) closes their open workday at that moment in the same
 * transaction (status COMPLETED, every interval closed, totals recomputed, audited as {@code workday.closed} with the reason), and
 * the deactivated person cannot send time events any more.
 */
class DeactivationClosesWorkdayTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    @Autowired TimeTrackingService timeTrackingService;
    UUID ownerId;
    UUID employeeId;
    Cookie ownerCookie;
    Cookie employeeCookie;
    TimeTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        UUID businessId = testData.createBusiness("Deactivation Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("deactivation.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        String employeeEmail = TestData.uniqueEmail("deactivation.carol");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, ownerId);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        employeeCookie = SignIn.session(mockMvc, employeeEmail).accessCookie();
        tracker = new TimeTracker(mockMvc, employeeCookie);
        clock.setTo("2026-10-01T03:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private void deactivate(String reasonJson) throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie)
                .content("{\"isActive\":false" + reasonJson + "}")).andExpect(status().isOk());
    }

    @Test
    void deactivatingClosesTheOpenWorkdayAndBlocksTimeEvents() throws Exception {
        String workdayId = JsonPath.read(tracker.apply("START"), "$.data.state.workday.id");
        clock.advance(Duration.ofHours(2));
        tracker.apply("BREAK");
        clock.advance(Duration.ofMinutes(20));
        deactivate(",\"reason\":\"Verification: left the organization\"");

        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT status::text AS status, ended_at, active_employee_id, productive_seconds, "
                + "break_seconds, break_count FROM workdays WHERE id = ?::uuid", workdayId);
        assertThat(workday).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", 7200).containsEntry("break_seconds", 1200)
                .containsEntry("break_count", 1);
        assertThat(workday.get("active_employee_id")).isNull();
        assertThat(workday.get("ended_at").toString()).startsWith("2026-10-01");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL", Integer.class, employeeId)
                + jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at IS NULL", Integer.class, employeeId))
                .isZero();

        Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT actor_id, entity_type, entity_id, reason, \"before\"->>'status' AS status_before, "
                + "\"after\"->>'endedAt' AS ended_at FROM audit_logs WHERE action = 'workday.closed' AND subject_id = ?", employeeId);
        assertThat(audit).containsEntry("actor_id", ownerId).containsEntry("entity_type", "workday").containsEntry("entity_id", workdayId)
                .containsEntry("reason", "Verification: left the organization").containsEntry("status_before", "ON_BREAK")
                .containsEntry("ended_at", "2026-10-01T05:50:00Z");
        assertThat(jdbcTemplate.queryForObject("SELECT \"after\"->>'closedWorkdayId' FROM audit_logs WHERE action = 'employee.deactivated' "
                + "AND subject_id = ?", String.class, employeeId)).isEqualTo(workdayId);

        // the deactivated person is signed out: no more time events or reads
        tracker.send("RESUME").andExpect(status().isUnauthorized());
        tracker.send("START").andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/time").cookie(employeeCookie)).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, employeeId)).isEqualTo(1);
    }

    @Test
    void withoutAReasonTheClosingSaysEmployeeDeactivatedAndWithoutAnOpenDayNothingIsClosed() throws Exception {
        tracker.apply("START");
        clock.advance(Duration.ofHours(1));
        deactivate("");
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM audit_logs WHERE action = 'workday.closed' AND subject_id = ?", String.class,
                employeeId)).isEqualTo("Employee deactivated.");
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE employee_id = ?", Integer.class, employeeId))
                .isEqualTo(3600);

        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie).content("{\"isActive\":true}"))
                .andExpect(status().isOk());
        deactivate(""); // nothing open this time
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'workday.closed' AND subject_id = ?", Integer.class,
                employeeId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'employee.deactivated' AND subject_id = ? "
                + "AND \"after\"->>'closedWorkdayId' IS NOT NULL", Integer.class, employeeId)).isEqualTo(1);
    }

    /** A request that was already past the sign-in check when the deactivation committed is refused under the employee's lock. */
    @Test
    void anEventOfAnAlreadyDeactivatedAccountIsRefusedUnderTheLock() throws Exception {
        deactivate("");
        CurrentUser staleSessionUser = new CurrentUser(employeeId, null, null, Role.EMPLOYEE, "Test", "Person", "carol@example.test",
                AuthenticationSource.STANDALONE, null);
        assertThatThrownBy(() -> timeTrackingService.recordEvent(staleSessionUser, new TimeEventInput("START")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getMessage()).isEqualTo("This account is deactivated.");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, employeeId)).isZero();
    }
}

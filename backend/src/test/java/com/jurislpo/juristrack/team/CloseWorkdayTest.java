package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.service.RateLimiter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code PUT /api/workdays/{workdayId}/status} with DAY_ENDED: a manager or the owner ends a forgotten workday. The employee started
 * on Monday 5 October at 09:00 Kolkata (03:30 UTC) and never ended the day; "now" is Tuesday 10:00 Kolkata (04:30 UTC), 25 hours
 * later, so the day is stale ("not ended").
 */
class CloseWorkdayTest extends IntegrationTest {

    private static final String REASON = "Forgot to end the day; left at 18:00";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;
    String workdayId;
    String statusPath;

    @BeforeEach
    void setUp() throws Exception {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        clock.setTo("2026-10-05T03:30:00Z");
        workdayId = team.applyEvent(team.employeeId, "START");
        statusPath = "/api/workdays/" + workdayId + "/status";
        clock.setTo("2026-10-06T04:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private static String body(String endedAt, String reason) {
        return "{\"status\":\"DAY_ENDED\"" + (endedAt == null ? "" : ",\"endedAt\":\"" + endedAt + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    private int closeEntries() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.workday_closed'", Integer.class,
                team.employeeId);
    }

    private Map<String, Object> workdayRow() {
        return jdbcTemplate.queryForMap("SELECT status::text AS status, ended_at, active_employee_id, productive_seconds, break_seconds, break_count"
                + " FROM workdays WHERE id = ?::uuid", workdayId);
    }

    @Test
    void aStaleWorkdayShowsOnTheBoardAndItsManagerEndsItAtAGivenTime() throws Exception {
        team.read(team.managerBId, "/api/team/members").andExpect(jsonPath("$.data.rows[0].isStale").value(true))
                .andExpect(jsonPath("$.data.rows[0].status").value("WORKING"));
        String answer = team.putJson(team.managerBId, statusPath, body("2026-10-05T12:30:00Z", REASON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Workday ended.")).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(answer, "$.data.endedAt")).isEqualTo("2026-10-05T12:30:00Z");
        assertThat((Integer) JsonPath.read(answer, "$.data.productiveSeconds")).isEqualTo(9 * 3600);
        assertThat((Boolean) JsonPath.read(answer, "$.data.isStale")).isFalse();

        Map<String, Object> workday = workdayRow();
        assertThat(workday.get("status")).isEqualTo("COMPLETED");
        assertThat(((Timestamp) workday.get("ended_at")).toInstant()).isEqualTo(Instant.parse("2026-10-05T12:30:00Z"));
        assertThat(workday.get("active_employee_id")).isNull();
        assertThat(workday.get("productive_seconds")).isEqualTo(9 * 3600);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM work_sessions WHERE workday_id = ?::uuid AND (ended_at IS NULL OR open_workday_id"
                + " IS NOT NULL)", Integer.class, workdayId)).isZero();

        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT actor_id, entity_type, entity_id, reason, before::text AS before_json,"
                + " after::text AS after_json FROM audit_logs WHERE action = 'time.workday_closed' AND subject_id = ?", team.employeeId);
        assertThat(entry.get("actor_id")).isEqualTo(team.managerBId);
        assertThat(entry.get("entity_type")).isEqualTo("workday");
        assertThat(entry.get("entity_id")).isEqualTo(workdayId);
        assertThat(entry.get("reason")).isEqualTo(REASON);
        assertThat((String) JsonPath.read((String) entry.get("before_json"), "$.workday.status")).isEqualTo("WORKING");
        assertThat((Object) JsonPath.read((String) entry.get("before_json"), "$.workday.endedAt")).isNull();
        assertThat((String) JsonPath.read((String) entry.get("after_json"), "$.workday.endedAt")).isEqualTo("2026-10-05T12:30:00Z");
        assertThat((Integer) JsonPath.read((String) entry.get("after_json"), "$.workday.productiveSeconds")).isEqualTo(32400);
        List<String> closedTypes = JsonPath.read((String) entry.get("after_json"), "$.closedIntervals[*].type");
        assertThat(closedTypes).containsExactly("WORK");

        // the employee's today (Tuesday) has not started; the closed Monday stays in their history
        String state = team.read(team.employeeId, "/api/me/time").andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(state, "$.data.status")).isEqualTo("NOT_STARTED");
        team.applyEvent(team.employeeId, "START");
    }

    @Test
    void withoutAnEndTimeTheRunningIntervalEndsNowAndABreakIsClosedToo() throws Exception {
        clock.setTo("2026-10-05T08:00:00Z");
        team.applyEvent(team.employeeId, "BREAK");
        clock.setTo("2026-10-05T09:00:00Z");
        team.putJson(team.ownerId, statusPath, body(null, REASON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endedAt").value("2026-10-05T09:00:00Z"))
                .andExpect(jsonPath("$.data.productiveSeconds").value(16200)).andExpect(jsonPath("$.data.breakSeconds").value(3600));
        Map<String, Object> workday = workdayRow();
        assertThat(workday.get("break_seconds")).isEqualTo(3600);
        assertThat(workday.get("break_count")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE workday_id = ?::uuid AND ended_at IS NULL", Integer.class,
                workdayId)).isZero();
    }

    @Test
    void anEndBeforeTheLastActivityOrInTheFutureAndBadBodiesAre400() throws Exception {
        clock.setTo("2026-10-05T08:00:00Z");
        team.applyEvent(team.employeeId, "BREAK");
        clock.setTo("2026-10-05T09:00:00Z");
        team.putJson(team.ownerId, statusPath, body("2026-10-05T07:59:00Z", REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The end time cannot be before the last recorded activity."));
        team.putJson(team.ownerId, statusPath, body("2026-10-05T09:00:01Z", REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Times cannot be in the future."));
        team.putJson(team.ownerId, statusPath, body(null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        team.putJson(team.ownerId, statusPath, "{\"status\":\"WORKING\",\"reason\":\"" + REASON + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        team.putJson(team.ownerId, statusPath, body("tomorrow", REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endedAt"));
        assertThat(workdayRow().get("status")).isEqualTo("ON_BREAK");
        assertThat(closeEntries()).isZero();
    }

    @Test
    void endingAnEndedWorkdayAgainChangesNothing() throws Exception {
        team.putJson(team.managerAId, statusPath, body("2026-10-05T12:30:00Z", REASON)).andExpect(status().isOk());
        team.putJson(team.managerAId, statusPath, body("2026-10-05T13:30:00Z", REASON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("This workday had already ended."))
                .andExpect(jsonPath("$.data.endedAt").value("2026-10-05T12:30:00Z"));
        assertThat(closeEntries()).isEqualTo(1);
    }

    @Test
    void onlyAManagerInScopeOrTheOwnerMayEndIt() throws Exception {
        team.putJson(team.employeeId, statusPath, body(null, REASON)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        team.putJson(team.employeeOfAId, statusPath, body(null, REASON)).andExpect(status().isForbidden());
        team.putJson(team.managerCId, statusPath, body(null, REASON)).andExpect(status().isNotFound());
        String managerWorkdayId = team.applyEvent(team.managerBId, "START");
        team.putJson(team.managerBId, "/api/workdays/" + managerWorkdayId + "/status", body(null, REASON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CANNOT_CORRECT_OWN_TIME"));
        TeamFixture otherBusiness = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        otherBusiness.putJson(otherBusiness.ownerId, statusPath, body(null, REASON)).andExpect(status().isNotFound());
        assertThat(workdayRow().get("status")).isEqualTo("WORKING");
        assertThat(closeEntries()).isZero();
    }
}

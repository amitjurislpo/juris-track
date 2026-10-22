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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Corrections of a work or break interval ({@code PATCH /api/workdays/{workdayId}/work-sessions|break-sessions/{sessionId}}): the old
 * verify-flows checks "non-admins cannot adjust", "overlapping adjustment is rejected" and "valid adjustment recomputes totals and
 * writes an audit entry", with the new rule that a manager corrects their direct and indirect reports and nobody their own time.
 *
 * <p>The employee's day (Kolkata, Monday 5 October): work 09:00-11:00, break 11:00-11:30, work 11:30-17:00 (UTC 03:30-05:30,
 * 05:30-06:00, 06:00-11:30): productive 7.5 hours, break 30 minutes. "Now" is 17:30 (12:00 UTC).
 */
class TimeCorrectionTest extends IntegrationTest {

    private static final String REASON = "Forgot to start the timer; confirmed by the manager";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;
    String workdayId;
    UUID firstWorkSessionId;
    UUID secondWorkSessionId;
    UUID breakSessionId;

    @BeforeEach
    void setUp() throws Exception {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        clock.setTo("2026-10-05T03:30:00Z");
        workdayId = team.applyEvent(team.employeeId, "START");
        clock.setTo("2026-10-05T05:30:00Z");
        team.applyEvent(team.employeeId, "BREAK");
        clock.setTo("2026-10-05T06:00:00Z");
        team.applyEvent(team.employeeId, "RESUME");
        clock.setTo("2026-10-05T11:30:00Z");
        team.applyEvent(team.employeeId, "END");
        clock.setTo("2026-10-05T12:00:00Z");
        List<UUID> workSessionIds = jdbcTemplate.queryForList("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at",
                UUID.class, workdayId);
        firstWorkSessionId = workSessionIds.get(0);
        secondWorkSessionId = workSessionIds.get(1);
        breakSessionId = jdbcTemplate.queryForObject("SELECT id FROM break_sessions WHERE workday_id = ?::uuid", UUID.class, workdayId);
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private String workSessionPath(UUID sessionId) {
        return "/api/workdays/" + workdayId + "/work-sessions/" + sessionId;
    }

    private String breakSessionPath(UUID sessionId) {
        return "/api/workdays/" + workdayId + "/break-sessions/" + sessionId;
    }

    private static String body(String startedAt, String endedAt, String reason) {
        StringBuilder json = new StringBuilder("{");
        if (startedAt != null) json.append("\"startedAt\":").append(jsonValue(startedAt)).append(',');
        if (endedAt != null) json.append("\"endedAt\":").append(jsonValue(endedAt)).append(',');
        json.append("\"reason\":").append(reason == null ? "null" : "\"" + reason + "\"").append('}');
        return json.toString();
    }

    /** {@code "null"} is sent as JSON null. */
    private static String jsonValue(String value) {
        return value.equals("null") ? "null" : "\"" + value + "\"";
    }

    private Map<String, Object> workdayRow() {
        return jdbcTemplate.queryForMap("SELECT status::text AS status, started_at, ended_at, productive_seconds, break_seconds, break_count"
                + " FROM workdays WHERE id = ?::uuid", workdayId);
    }

    private int correctionEntries() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.session_adjusted'",
                Integer.class, team.employeeId);
    }

    /** The cached totals equal the sums of the intervals. */
    private void assertTotalsEqualTheIntervals() {
        Map<String, Object> sums = jdbcTemplate.queryForMap("""
                SELECT (SELECT COALESCE(sum(floor(extract(epoch FROM ended_at - started_at))), 0)::int FROM work_sessions WHERE workday_id = ?::uuid) AS work,
                       (SELECT COALESCE(sum(floor(extract(epoch FROM ended_at - started_at))), 0)::int FROM break_sessions WHERE workday_id = ?::uuid) AS breaks,
                       (SELECT count(*)::int FROM break_sessions WHERE workday_id = ?::uuid) AS break_count""", workdayId, workdayId, workdayId);
        Map<String, Object> workday = workdayRow();
        assertThat(workday.get("productive_seconds")).isEqualTo(sums.get("work"));
        assertThat(workday.get("break_seconds")).isEqualTo(sums.get("breaks"));
        assertThat(workday.get("break_count")).isEqualTo(sums.get("break_count"));
    }

    @Test
    void employeesCannotCorrectTimeAndNobodyCorrectsTheirOwn() throws Exception {
        String correction = body("2026-10-05T03:00:00Z", null, REASON);
        team.patchJson(team.employeeOfAId, workSessionPath(firstWorkSessionId), correction).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        team.patchJson(team.employeeId, workSessionPath(firstWorkSessionId), correction).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String managerWorkdayId = team.applyEvent(team.managerBId, "START");
        UUID managerSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid", UUID.class, managerWorkdayId);
        team.patchJson(team.managerBId, "/api/workdays/" + managerWorkdayId + "/work-sessions/" + managerSessionId,
                body("2026-10-05T11:00:00Z", null, REASON)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CANNOT_CORRECT_OWN_TIME"))
                .andExpect(jsonPath("$.detail").value("You cannot correct your own time. Ask your manager or the owner to do it."));
        String ownerWorkdayId = team.applyEvent(team.ownerId, "START");
        UUID ownerSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid", UUID.class, ownerWorkdayId);
        team.patchJson(team.ownerId, "/api/workdays/" + ownerWorkdayId + "/work-sessions/" + ownerSessionId, body("2026-10-05T11:00:00Z", null, REASON))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CANNOT_CORRECT_OWN_TIME"));
        // their own manager can
        team.patchJson(team.managerAId, "/api/workdays/" + managerWorkdayId + "/work-sessions/" + managerSessionId,
                body("2026-10-05T11:00:00Z", null, REASON)).andExpect(status().isOk());

        assertThat(workdayRow().get("productive_seconds")).isEqualTo(27000);
        assertThat(correctionEntries()).isZero();
    }

    @Test
    void aManagerOutsideTheScopeAndAnotherBusinessGet404() throws Exception {
        String correction = body("2026-10-05T03:00:00Z", null, REASON);
        team.patchJson(team.managerCId, workSessionPath(firstWorkSessionId), correction).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Workday not found."));
        team.putJson(team.managerCId, "/api/workdays/" + workdayId + "/status", "{\"status\":\"DAY_ENDED\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isNotFound());
        TeamFixture otherBusiness = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        otherBusiness.patchJson(otherBusiness.ownerId, workSessionPath(firstWorkSessionId), correction).andExpect(status().isNotFound());
        assertThat(correctionEntries()).isZero();
    }

    @Test
    void theDirectManagerTheIndirectManagerAndTheOwnerMayCorrect() throws Exception {
        team.patchJson(team.managerBId, workSessionPath(firstWorkSessionId), body("2026-10-05T03:20:00Z", null, REASON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Time corrected."));
        team.patchJson(team.managerAId, workSessionPath(firstWorkSessionId), body("2026-10-05T03:10:00Z", null, REASON))
                .andExpect(status().isOk());
        team.patchJson(team.ownerId, workSessionPath(firstWorkSessionId), body("2026-10-05T03:00:00Z", null, REASON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productiveSeconds").value(28800));
        assertThat(correctionEntries()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("SELECT actor_id FROM audit_logs WHERE subject_id = ? AND action = 'time.session_adjusted'"
                + " ORDER BY created_at", UUID.class, team.employeeId)).containsExactlyInAnyOrder(team.managerBId, team.managerAId, team.ownerId);
    }

    @Test
    void anOverlappingCorrectionIsRejected() throws Exception {
        // the first work interval would run past the end of the break
        team.patchJson(team.ownerId, workSessionPath(firstWorkSessionId), body(null, "2026-10-05T06:00:01Z", REASON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIME_INTERVAL_OVERLAP"));
        // a break moved into the second work interval
        team.patchJson(team.ownerId, breakSessionPath(breakSessionId), body("2026-10-05T07:00:00Z", "2026-10-05T07:10:00Z", REASON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIME_INTERVAL_OVERLAP"));
        // a work interval moved over the other work interval
        team.patchJson(team.ownerId, workSessionPath(secondWorkSessionId), body("2026-10-05T04:00:00Z", null, REASON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TIME_INTERVAL_OVERLAP"));
        // a break before the first work interval
        team.patchJson(team.ownerId, breakSessionPath(breakSessionId), body("2026-10-05T03:00:00Z", "2026-10-05T03:20:00Z", REASON))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WORKDAY_MUST_START_WITH_WORK"));
        assertThat(workdayRow().get("productive_seconds")).isEqualTo(27000);
        assertThat(correctionEntries()).isZero();
    }

    @Test
    void aValidCorrectionRecomputesTheTotalsAndIsAudited() throws Exception {
        String answer = team.patchJson(team.managerBId, workSessionPath(firstWorkSessionId), body("2026-10-05T02:30:00Z", null, REASON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(answer, "$.data.startedAt")).isEqualTo("2026-10-05T02:30:00Z");
        assertThat((Integer) JsonPath.read(answer, "$.data.productiveSeconds")).isEqualTo(27000 + 3600);
        assertThat((String) JsonPath.read(answer, "$.data.segments[0].startedAt")).isEqualTo("2026-10-05T02:30:00Z");

        Map<String, Object> workday = workdayRow();
        assertThat(workday.get("status")).isEqualTo("COMPLETED");
        assertThat(workday.get("productive_seconds")).isEqualTo(27000 + 3600);
        assertThat(((Timestamp) workday.get("started_at")).toInstant()).isEqualTo(Instant.parse("2026-10-05T02:30:00Z"));
        assertThat(((Timestamp) workday.get("ended_at")).toInstant()).isEqualTo(Instant.parse("2026-10-05T11:30:00Z"));
        assertTotalsEqualTheIntervals();

        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT business_id, actor_id, subject_id, entity_type, entity_id, reason,"
                + " before::text AS before_json, after::text AS after_json FROM audit_logs WHERE action = 'time.session_adjusted' AND subject_id = ?",
                team.employeeId);
        assertThat(entry.get("business_id")).isEqualTo(team.businessId);
        assertThat(entry.get("actor_id")).isEqualTo(team.managerBId);
        assertThat(entry.get("entity_type")).isEqualTo("work_session");
        assertThat(entry.get("entity_id")).isEqualTo(firstWorkSessionId.toString());
        assertThat(entry.get("reason")).isEqualTo(REASON);
        String before = (String) entry.get("before_json");
        String after = (String) entry.get("after_json");
        assertThat((String) JsonPath.read(before, "$.interval.startedAt")).isEqualTo("2026-10-05T03:30:00Z");
        assertThat((String) JsonPath.read(before, "$.interval.endedAt")).isEqualTo("2026-10-05T05:30:00Z");
        assertThat((String) JsonPath.read(before, "$.interval.type")).isEqualTo("WORK");
        assertThat((Integer) JsonPath.read(before, "$.workday.productiveSeconds")).isEqualTo(27000);
        assertThat((String) JsonPath.read(after, "$.interval.startedAt")).isEqualTo("2026-10-05T02:30:00Z");
        assertThat((Integer) JsonPath.read(after, "$.workday.productiveSeconds")).isEqualTo(30600);
        assertThat((String) JsonPath.read(after, "$.workday.startedAt")).isEqualTo("2026-10-05T02:30:00Z");
        assertThat((String) JsonPath.read(after, "$.workday.status")).isEqualTo("DAY_ENDED");
    }

    @Test
    void movingTheLastIntervalMovesTheEndOfTheDayAndABreakChangesTheBreakTime() throws Exception {
        team.patchJson(team.ownerId, workSessionPath(secondWorkSessionId), body(null, "2026-10-05T12:00:00Z", REASON)).andExpect(status().isOk());
        Map<String, Object> workday = workdayRow();
        assertThat(((Timestamp) workday.get("ended_at")).toInstant()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(workday.get("productive_seconds")).isEqualTo(27000 + 1800);
        assertThat(workday.get("status")).as("a completed workday stays completed").isEqualTo("COMPLETED");

        String answer = team.patchJson(team.ownerId, breakSessionPath(breakSessionId), body(null, "2026-10-05T05:45:00+00:00", REASON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(answer, "$.data.breakSeconds")).isEqualTo(900);
        assertThat(jdbcTemplate.queryForObject("SELECT entity_type FROM audit_logs WHERE entity_id = ? AND action = 'time.session_adjusted'",
                String.class, breakSessionId.toString())).isEqualTo("break_session");
        assertTotalsEqualTheIntervals();

        // an offset is converted to UTC
        team.patchJson(team.ownerId, workSessionPath(firstWorkSessionId), body("2026-10-05T08:30:00+05:30", null, REASON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.startedAt").value("2026-10-05T03:00:00Z"));
        assertTotalsEqualTheIntervals();
    }

    @Test
    void sendingTheSameTimesChangesNothing() throws Exception {
        team.patchJson(team.ownerId, workSessionPath(firstWorkSessionId), body("2026-10-05T03:30:00Z", "2026-10-05T05:30:00Z", REASON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Nothing changed."))
                .andExpect(jsonPath("$.data.productiveSeconds").value(27000));
        assertThat(correctionEntries()).isZero();
    }

    @Test
    void invalidCorrectionsAre400() throws Exception {
        String path = workSessionPath(firstWorkSessionId);
        team.patchJson(team.ownerId, path, body("2026-10-05T03:00:00Z", null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        team.patchJson(team.ownerId, path, body("2026-10-05T03:00:00Z", null, "  no ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Give a reason of at least 5 characters."));
        team.patchJson(team.ownerId, path, body("2026-10-05T03:00:00Z", null, "x".repeat(501))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        team.patchJson(team.ownerId, path, body("yesterday", null, REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startedAt"));
        // a time without an offset is ambiguous
        team.patchJson(team.ownerId, path, body("2026-10-05T09:00:00", null, REASON)).andExpect(status().isBadRequest());
        team.patchJson(team.ownerId, path, body("null", null, REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startedAt"));
        team.patchJson(team.ownerId, path, body(null, "null", REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("An end time is required."));
        // inverted and empty intervals
        team.patchJson(team.ownerId, path, body("2026-10-05T05:00:00Z", "2026-10-05T04:00:00Z", REASON)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The end time must be after the start time."));
        team.patchJson(team.ownerId, path, body("2026-10-05T04:00:00Z", "2026-10-05T04:00:00Z", REASON)).andExpect(status().isBadRequest());
        // the future
        team.patchJson(team.ownerId, workSessionPath(secondWorkSessionId), body(null, "2026-10-05T12:00:01Z", REASON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Times cannot be in the future."));
        team.patchJson(team.ownerId, path, "[1, 2]").andExpect(status().isBadRequest());
        assertThat(workdayRow().get("productive_seconds")).isEqualTo(27000);
        assertThat(correctionEntries()).isZero();
    }

    @Test
    void anUnknownIntervalOrOneOfAnotherWorkdayOrTypeIs404() throws Exception {
        String correction = body("2026-10-05T03:00:00Z", null, REASON);
        team.patchJson(team.ownerId, breakSessionPath(firstWorkSessionId), correction).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Time record not found."));
        team.patchJson(team.ownerId, workSessionPath(UUID.randomUUID()), correction).andExpect(status().isNotFound());
        String otherWorkdayId = team.applyEvent(team.employeeOfAId, "START");
        team.patchJson(team.ownerId, "/api/workdays/" + otherWorkdayId + "/work-sessions/" + firstWorkSessionId, correction)
                .andExpect(status().isNotFound());
        team.patchJson(team.ownerId, "/api/workdays/" + UUID.randomUUID() + "/work-sessions/" + firstWorkSessionId, correction)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Workday not found."));
        team.patchJson(team.ownerId, "/api/workdays/not-a-uuid/work-sessions/" + firstWorkSessionId, correction).andExpect(status().isBadRequest());
    }

    @Test
    void aRunningIntervalKeepsRunningAndOnlyItsStartMoves() throws Exception {
        String openWorkdayId = team.applyEvent(team.employeeOfAId, "START");
        UUID openSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid", UUID.class, openWorkdayId);
        String path = "/api/workdays/" + openWorkdayId + "/work-sessions/" + openSessionId;
        team.patchJson(team.managerAId, path, body(null, "2026-10-05T12:00:00Z", REASON)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTERVAL_STILL_RUNNING"));
        team.patchJson(team.managerAId, path, body("2026-10-05T11:00:00Z", "null", REASON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WORKING")).andExpect(jsonPath("$.data.productiveSeconds").value(3600))
                .andExpect(jsonPath("$.data.activeWorkStartedAt").value("2026-10-05T11:00:00Z"));
        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT status::text AS status, started_at, productive_seconds, active_employee_id"
                + " FROM workdays WHERE id = ?::uuid", openWorkdayId);
        assertThat(workday.get("status")).isEqualTo("WORKING");
        assertThat(workday.get("productive_seconds")).as("an open day keeps no cached totals").isNull();
        assertThat(workday.get("active_employee_id")).isEqualTo(team.employeeOfAId);
        assertThat(((Timestamp) workday.get("started_at")).toInstant()).isEqualTo(Instant.parse("2026-10-05T11:00:00Z"));
        // the employee carries on normally afterwards
        team.applyEvent(team.employeeOfAId, "BREAK");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE workday_id = ?::uuid AND ended_at IS NULL", Integer.class,
                openWorkdayId)).isEqualTo(1);
    }
}

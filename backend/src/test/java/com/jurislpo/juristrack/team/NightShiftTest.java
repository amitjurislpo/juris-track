package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
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
 * Night shifts (US / UK shifts worked from India). Time is calculated per SHIFT: a US shift 19:30-04:30 is one workday of nine
 * scheduled hours whichever calendar dates it touches, it belongs to the shift date it started on, a person still working after
 * midnight is WORKING, and a shift that ended after midnight stays the current workday (DAY_ENDED, its own date) until the next shift
 * occurrence is nearer. Every figure comes from the workday's own intervals.
 *
 * <p>Business time zone Asia/Kolkata (UTC+05:30). The shift day D is Monday 5 October 2026; 21:30 on D is 16:00 UTC. The employee works
 * the "US shift" 19:30-04:30 (3 hours of grace each side), the employee of A the "UK shift" 14:00-23:00.
 */
class NightShiftTest extends IntegrationTest {

    private static final String DATE = "2026-10-05";
    private static final String NEXT_DATE = "2026-10-06";
    private static final String SETUP_TIME = "2026-10-05T10:00:00Z";
    private static final String START_2130 = "2026-10-05T16:00:00Z";
    private static final String MIDNIGHT_PLUS_10 = "2026-10-05T18:40:00Z";
    private static final String BREAK_START_0030 = "2026-10-05T19:00:00Z";
    private static final String BREAK_END_0100 = "2026-10-05T19:30:00Z";
    private static final String END_0630 = "2026-10-06T01:00:00Z";
    private static final String NEXT_0700 = "2026-10-06T01:30:00Z";
    private static final String NEXT_0800 = "2026-10-06T02:30:00Z";
    private static final String NEXT_0830 = "2026-10-06T03:00:00Z";
    private static final String NEXT_1400 = "2026-10-06T08:30:00Z";
    private static final String NEXT_2130 = "2026-10-06T16:00:00Z";
    private static final String REASON = "Night shift check; confirmed with the manager";
    private static final int HOUR = 3600;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;
    UUID usShiftId;
    UUID ukShiftId;

    @BeforeEach
    void setUp() throws Exception {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        clock.setTo(SETUP_TIME);
        usShiftId = createShift("US shift", "19:30", "04:30");
        ukShiftId = createShift("UK shift", "14:00", "23:00");
        assign(team.employeeId, usShiftId);
        assign(team.employeeOfAId, ukShiftId);
        clock.setTo(START_2130);
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    UUID createShift(String name, String startTime, String endTime) throws Exception {
        String answer = team.postJson(team.ownerId, "/api/shifts", "{\"name\":\"" + name + "\",\"startTime\":\"" + startTime + "\",\"endTime\":\""
                + endTime + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(answer, "$.data.id"));
    }

    void assign(UUID employeeId, UUID shiftId) throws Exception {
        team.putJson(team.ownerId, "/api/employees/" + employeeId + "/shift", "{\"shiftId\":\"" + shiftId + "\"}").andExpect(status().isOk());
    }

    private String me(UUID userId) throws Exception {
        return team.read(userId, "/api/me/time").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String board(String query) throws Exception {
        return team.read(team.ownerId, "/api/team/members" + (query.isEmpty() ? "" : "?" + query)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private Map<String, Object> row(String answer, UUID employeeId) {
        List<Map<String, Object>> rows = JsonPath.read(answer, "$.data.rows[?(@.employee.id == '" + employeeId + "')]");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void at(String instant, UUID userId, String type) throws Exception {
        clock.setTo(instant);
        team.applyEvent(userId, type);
    }

    /** Start 21:30 on D, break 00:30-01:00, End at {@code endAt} (none: still running). Answers the workday id. */
    private String nightShift(UUID userId, String endAt) throws Exception {
        String workdayId = team.applyEvent(userId, "START");
        at(BREAK_START_0030, userId, "BREAK");
        at(BREAK_END_0100, userId, "RESUME");
        if (endAt != null) at(endAt, userId, "END");
        return workdayId;
    }

    private int auditEntries(UUID employeeId, String action) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = ?", Integer.class, employeeId, action);
    }

    @Test
    void elevenHourNightShiftIsNotStale() throws Exception {
        nightShift(team.employeeId, null);
        clock.setTo(NEXT_0830);
        String state = me(team.employeeId);
        assertThat((String) JsonPath.read(state, "$.data.today")).isEqualTo(NEXT_DATE);
        assertThat((String) JsonPath.read(state, "$.data.status")).isEqualTo("WORKING");
        assertThat((String) JsonPath.read(state, "$.data.workday.date")).isEqualTo(DATE);
        assertThat((Boolean) JsonPath.read(state, "$.data.workday.isStale")).as("11 hours is inside the default 14").isFalse();
        // 21:30 to 08:30 is 11 hours, 30 minutes of it a break: the hours are the same whichever side of midnight they fall on
        assertThat((Integer) JsonPath.read(state, "$.data.workday.productiveSeconds")).isEqualTo(10 * HOUR + 1800);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.breakSeconds")).isEqualTo(1800);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.spanSeconds")).isEqualTo(11 * HOUR);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.breakCount")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.extraSeconds")).as("04:30 to 08:30 is outside the shift").isEqualTo(4 * HOUR);

        jdbcTemplate.update("UPDATE businesses SET stale_workday_hours = 10 WHERE id = ?", team.businessId);
        state = me(team.employeeId);
        assertThat((Boolean) JsonPath.read(state, "$.data.workday.isStale")).as("11 hours is over a 10-hour limit").isTrue();
        assertThat((String) JsonPath.read(state, "$.data.status")).as("stale is a flag, the person is still working").isEqualTo("WORKING");
    }

    @Test
    void nightShiftBreakAfterMidnightKeepsTotalsAndBreakCount() throws Exception {
        String workdayId = nightShift(team.employeeId, NEXT_0830);
        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT work_date::text AS work_date, productive_seconds, break_seconds, break_count,"
                + " extra_seconds, shift_name, is_outside_shift FROM workdays WHERE id = ?::uuid", workdayId);
        assertThat(workday).containsEntry("work_date", DATE).containsEntry("productive_seconds", 10 * HOUR + 1800).containsEntry("break_seconds", 1800)
                .containsEntry("break_count", 1).containsEntry("extra_seconds", 4 * HOUR).containsEntry("shift_name", "US shift")
                .containsEntry("is_outside_shift", false);
        String history = team.read(team.employeeId, "/api/workdays/mine?range=custom&from=2026-10-05&to=2026-10-07").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(history, "$.data.rows")).hasSize(1);
        assertThat((String) JsonPath.read(history, "$.data.rows[0].date")).isEqualTo(DATE);
        assertThat((Integer) JsonPath.read(history, "$.data.rows[0].productiveSeconds")).isEqualTo(10 * HOUR + 1800);
        assertThat((Integer) JsonPath.read(history, "$.data.rows[0].varianceSeconds")).as("productive minus the 9 scheduled hours").isEqualTo(HOUR + 1800);
        assertThat((Integer) JsonPath.read(history, "$.data.rows[0].extraSeconds")).isEqualTo(4 * HOUR);
        assertThat((Integer) JsonPath.read(history, "$.data.totals.extraSeconds")).isEqualTo(4 * HOUR);
        assertThat((Integer) JsonPath.read(history, "$.data.rows[0].shift.scheduledSeconds")).isEqualTo(9 * HOUR);
    }

    @Test
    void boardShowsRunningNightShiftAsWorkingAndCountsItInMetrics() throws Exception {
        team.applyEvent(team.employeeId, "START");
        clock.setTo(MIDNIGHT_PLUS_10); // 00:10 on D+1
        String answer = board("");
        assertThat((String) JsonPath.read(answer, "$.data.today")).isEqualTo(NEXT_DATE);
        assertThat(row(answer, team.employeeId)).containsEntry("status", "WORKING").containsEntry("date", DATE).containsEntry("productiveSeconds", 2 * HOUR + 2400);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.working")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.notStarted")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.weekOff")).isZero();
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.totalProductiveSeconds")).isEqualTo(2 * HOUR + 2400);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.averageProductiveSeconds")).isEqualTo(2 * HOUR + 2400);

        // the UK-shift colleague started at 14:00 on D+1 would be later; at 07:00 the night shift is the only one running
        clock.setTo(NEXT_0700);
        answer = board("");
        assertThat(row(answer, team.employeeId)).containsEntry("status", "WORKING").containsEntry("productiveSeconds", 9 * HOUR + 1800);
        // a day-shift colleague on the default shift starts at 07:00 on D+1; both are working
        team.applyEvent(team.employeeOfCId, "START");
        clock.setTo(NEXT_0800);
        answer = board("");
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.working")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.totalProductiveSeconds")).isEqualTo(10 * HOUR + 1800 + HOUR);
        List<String> working = JsonPath.read(board("status=WORKING&sort=productiveSeconds&sortDirection=desc"), "$.data.rows[*].employee.id");
        assertThat(working).containsExactly(team.employeeId.toString(), team.employeeOfCId.toString());
        List<String> byStatus = JsonPath.read(board("sort=status"), "$.data.rows[*].employee.id");
        assertThat(byStatus.subList(0, 2)).containsExactlyInAnyOrder(team.employeeId.toString(), team.employeeOfCId.toString());
        assertThat((List<?>) JsonPath.read(board("status=NOT_STARTED"), "$.data.rows")).hasSize(4);
    }

    @Test
    void endedNightShiftStatusOnBoardAndMe() throws Exception {
        String nightWorkdayId = nightShift(team.employeeId, END_0630);
        clock.setTo(NEXT_0700);

        String state = me(team.employeeId);
        assertThat((String) JsonPath.read(state, "$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(state, "$.data.workday.id")).isEqualTo(nightWorkdayId);
        assertThat((String) JsonPath.read(state, "$.data.workday.date")).isEqualTo(DATE);
        assertThat((String) JsonPath.read(state, "$.data.shiftDate")).isEqualTo(DATE);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.productiveSeconds")).isEqualTo(8 * HOUR + 1800);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.varianceSeconds")).as("8.5 h worked of 9 scheduled").isEqualTo(-1800);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.extraSeconds")).as("04:30 to 06:30").isEqualTo(2 * HOUR);
        assertThat((String) JsonPath.read(state, "$.data.workday.shift.name")).isEqualTo("US shift");
        assertThat((String) JsonPath.read(state, "$.data.workday.shift.startTime")).isEqualTo("19:30");
        assertThat((String) JsonPath.read(state, "$.data.workday.shift.endTime")).isEqualTo("04:30");

        String answer = board("");
        assertThat(row(answer, team.employeeId)).containsEntry("status", "DAY_ENDED").containsEntry("workdayId", nightWorkdayId)
                .containsEntry("date", DATE).containsEntry("productiveSeconds", 8 * HOUR + 1800).containsEntry("breakSeconds", 1800)
                .containsEntry("extraSeconds", 2 * HOUR).containsEntry("varianceSeconds", -1800);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.dayEnded")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.notStarted")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.totalProductiveSeconds")).isEqualTo(8 * HOUR + 1800);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.totalExtraSeconds")).isEqualTo(2 * HOUR);
        assertThat((List<?>) JsonPath.read(board("status=DAY_ENDED"), "$.data.rows")).hasSize(1);

        String member = team.read(team.managerBId, "/api/team/members/" + team.employeeId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(member, "$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(member, "$.data.workday.date")).isEqualTo(DATE);

        // decision: the ended shift stays the current workday while its occurrence is the nearest one (08:00: still the 5th's shift),
        // and the person is NOT_STARTED for the next occurrence once that is nearer (14:00) or its window opens (21:30)
        clock.setTo(NEXT_0800);
        assertThat((String) JsonPath.read(me(team.employeeId), "$.data.status")).isEqualTo("DAY_ENDED");
        clock.setTo(NEXT_1400);
        String waiting = me(team.employeeId);
        assertThat((String) JsonPath.read(waiting, "$.data.status")).isEqualTo("NOT_STARTED");
        assertThat((String) JsonPath.read(waiting, "$.data.shiftDate")).isEqualTo(NEXT_DATE);
        assertThat((String) JsonPath.read(waiting, "$.data.shift.name")).isEqualTo("US shift");
        assertThat(row(board(""), team.employeeId)).containsEntry("status", "NOT_STARTED");

        // the first START of the next shift creates a workday dated D+1 (never a reopen of D)
        clock.setTo(NEXT_2130);
        String startAnswer = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.status")).isEqualTo("WORKING");
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.workday.date")).isEqualTo(NEXT_DATE);
        assertThat((String) JsonPath.read(startAnswer, "$.data.workdayId")).isNotEqualTo(nightWorkdayId);
        assertThat(jdbcTemplate.queryForList("SELECT work_date::text FROM workdays WHERE employee_id = ? ORDER BY started_at", String.class, team.employeeId))
                .containsExactly(DATE, NEXT_DATE);
        assertThat(jdbcTemplate.queryForObject("SELECT is_outside_shift FROM workdays WHERE employee_id = ? AND work_date = ?::date", Boolean.class,
                team.employeeId, NEXT_DATE)).isFalse();
    }

    @Test
    void repeatedEndAfterMidnightIsAlreadyApplied() throws Exception {
        String nightWorkdayId = nightShift(team.employeeId, END_0630);
        assertThat(auditEntries(team.employeeId, "time.workday_ended")).isEqualTo(1);
        long entriesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ?", Long.class, team.employeeId);

        clock.setTo(NEXT_0700);
        team.sendEvent(team.employeeId, "END").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true))
                .andExpect(jsonPath("$.data.workdayId").value(nightWorkdayId)).andExpect(jsonPath("$.data.state.status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.state.workday.date").value(DATE));
        clock.setTo(NEXT_0800);
        team.sendEvent(team.employeeId, "END").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ?", Long.class, team.employeeId))
                .as("a repeat writes no audit entry").isEqualTo(entriesBefore);
        assertThat(auditEntries(team.employeeId, "time.workday_ended")).isEqualTo(1);
        // a break or a resume after the shift ended is still refused
        team.sendEvent(team.employeeId, "BREAK").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKDAY_NOT_STARTED"));
        team.sendEvent(team.employeeId, "RESUME").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ON_BREAK"));
    }

    @Test
    void startAfterMidnightEndReopensTheSameShift() throws Exception {
        String firstWorkdayId = team.applyEvent(team.employeeId, "START");
        at(MIDNIGHT_PLUS_10, team.employeeId, "END");          // 00:10 on D+1
        clock.setTo("2026-10-05T18:55:00Z");                   // 00:25: still the same shift occurrence
        String startAnswer = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // what the code does: the same shift occurrence, so the same workday runs again; the gap is in neither productive nor break time
        assertThat((String) JsonPath.read(startAnswer, "$.data.workdayId")).isEqualTo(firstWorkdayId);
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.workday.date")).isEqualTo(DATE);
        assertThat(auditEntries(team.employeeId, "time.workday_reopened")).isEqualTo(1);
        at("2026-10-05T21:55:00Z", team.employeeId, "END");    // 03:25
        // 21:30-00:10 = 2 h 40 min and 00:25-03:25 = 3 h; the 15-minute gap is in neither productive nor break time
        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT productive_seconds, break_seconds FROM workdays WHERE id = ?::uuid", firstWorkdayId);
        assertThat(workday).containsEntry("productive_seconds", 5 * HOUR + 2400).containsEntry("break_seconds", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, team.employeeId)).isEqualTo(1);
    }

    @Test
    void correctNightShiftStartingAt2300With11Hours() throws Exception {
        clock.setTo("2026-10-05T17:30:00Z"); // 23:00
        String workdayId = team.applyEvent(team.employeeId, "START");
        at(BREAK_START_0030, team.employeeId, "BREAK");
        at(BREAK_END_0100, team.employeeId, "RESUME");
        at("2026-10-06T04:30:00Z", team.employeeId, "END"); // 10:00, 11 hours
        UUID breakSessionId = jdbcTemplate.queryForObject("SELECT id FROM break_sessions WHERE workday_id = ?::uuid", UUID.class, workdayId);
        UUID lastWorkSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at DESC LIMIT 1",
                UUID.class, workdayId);
        clock.setTo("2026-10-08T04:00:00Z");

        // the break moves to 01:00-01:30 on D+1 (after midnight, inside the window): the work after it starts at 01:30 (the gap
        // 00:30-01:00 belongs to neither), so the break still lasts 30 minutes and the productive time is 10 hours
        team.patchJson(team.managerBId, "/api/workdays/" + workdayId + "/work-sessions/" + lastWorkSessionId,
                "{\"startedAt\":\"2026-10-05T20:00:00Z\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        team.patchJson(team.managerBId, "/api/workdays/" + workdayId + "/break-sessions/" + breakSessionId,
                "{\"startedAt\":\"2026-10-05T19:30:00Z\",\"endedAt\":\"2026-10-05T20:00:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productiveSeconds").value(10 * HOUR))
                .andExpect(jsonPath("$.data.breakSeconds").value(1800)).andExpect(jsonPath("$.data.breakCount").value(1))
                .andExpect(jsonPath("$.data.date").value(DATE));

        // an end after midnight of D+2 is outside the window
        String outOfRange = "Choose a time on " + DATE + " or " + NEXT_DATE + ", in the business time zone. To close a forgotten shift, give the time it really ended.";
        team.patchJson(team.managerBId, "/api/workdays/" + workdayId + "/work-sessions/" + lastWorkSessionId,
                "{\"endedAt\":\"2026-10-06T18:31:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.detail").value(outOfRange));

        // a forgotten shift of another person, closed with the default "now" long after the window: refused with the way out;
        // giving its real end works
        clock.setTo("2026-10-05T08:30:00Z"); // 14:00
        String forgottenId = team.applyEvent(team.employeeOfAId, "START");
        clock.setTo("2026-10-08T04:00:00Z");
        String closePath = "/api/workdays/" + forgottenId + "/status";
        team.putJson(team.managerAId, closePath, "{\"status\":\"DAY_ENDED\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.detail").value(outOfRange));
        team.putJson(team.managerAId, closePath, "{\"status\":\"DAY_ENDED\",\"endedAt\":\"2026-10-05T17:30:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productiveSeconds").value(9 * HOUR));
    }

    @Test
    void consecutiveNightShiftsDoNotOverlap() throws Exception {
        String firstId = nightShift(team.employeeId, END_0630);
        // the next shift: its window opens at 16:30 on D+1; it starts at 16:30 sharp (11:00 UTC) and ends at 01:30 UTC next day
        clock.setTo("2026-10-06T11:00:00Z");
        String secondId = team.applyEvent(team.employeeId, "START");
        at("2026-10-06T20:00:00Z", team.employeeId, "END");
        assertThat(secondId).isNotEqualTo(firstId);
        UUID lastWorkSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at DESC LIMIT 1",
                UUID.class, firstId);
        clock.setTo("2026-10-08T04:00:00Z");
        String path = "/api/workdays/" + firstId + "/work-sessions/" + lastWorkSessionId;

        team.patchJson(team.managerBId, path, "{\"endedAt\":\"2026-10-06T00:30:00Z\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        // touching the next shift's start is fine
        team.patchJson(team.managerBId, path, "{\"endedAt\":\"2026-10-06T11:00:00Z\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        team.patchJson(team.managerBId, path, "{\"endedAt\":\"2026-10-06T11:30:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WORKDAY_OVERLAP"));
    }

    @Test
    void suspensionAndDeactivationCloseNightShiftAfterMidnight() throws Exception {
        String deactivatedId = team.applyEvent(team.employeeId, "START");
        clock.setTo(START_2130);
        String suspendedId = team.applyEvent(team.employeeOfCId, "START");
        clock.setTo("2026-10-05T20:30:00Z"); // 02:00 on D+1
        mockMvc.perform(SignIn.fromWebApp(put("/api/employees/" + team.employeeId + "/status")).cookie(team.cookieOf(team.ownerId))
                .content("{\"isActive\":false,\"reason\":\"Left the organization\"}")).andExpect(status().isOk());
        Map<String, Object> deactivated = jdbcTemplate.queryForMap("SELECT work_date::text AS work_date, status::text AS status, productive_seconds,"
                + " ended_at FROM workdays WHERE id = ?::uuid", deactivatedId);
        assertThat(deactivated).containsEntry("work_date", DATE).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", 4 * HOUR + 1800);

        String superAdminEmail = TestData.uniqueEmail("night.platform");
        new TestData(jdbcTemplate).createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        rateLimiter.clear();
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        clock.setTo("2026-10-05T21:00:00Z"); // 02:30
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + team.businessId + "/status")).cookie(superAdminCookie)
                .content("{\"isSuspended\":true}")).andExpect(status().isOk());
        Map<String, Object> suspended = jdbcTemplate.queryForMap("SELECT work_date::text AS work_date, status::text AS status, productive_seconds"
                + " FROM workdays WHERE id = ?::uuid", suspendedId);
        assertThat(suspended).containsEntry("work_date", DATE).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", 5 * HOUR);
    }

    @Test
    void usNightShiftAcrossSpringForward() throws Exception {
        jdbcTemplate.update("UPDATE businesses SET time_zone = 'America/New_York' WHERE id = ?", team.businessId);
        clock.setTo("2026-03-07T15:00:00Z");
        UUID nightShiftId = createShift("US night", "22:00", "06:00");
        assign(team.employeeOfCId, nightShiftId);
        clock.setTo("2026-03-08T03:00:00Z"); // 22:00 EST on 7 March
        String workdayId = team.applyEvent(team.employeeOfCId, "START");
        at("2026-03-08T06:30:00Z", team.employeeOfCId, "BREAK"); // 01:30 EST
        at("2026-03-08T07:00:00Z", team.employeeOfCId, "RESUME"); // 03:00 EDT: the wall clock jumped, 30 real minutes passed
        String running = me(team.employeeOfCId);
        assertThat((String) JsonPath.read(running, "$.data.today")).isEqualTo("2026-03-08");
        assertThat((String) JsonPath.read(running, "$.data.status")).isEqualTo("WORKING");
        assertThat((String) JsonPath.read(running, "$.data.workday.date")).isEqualTo("2026-03-07");
        at("2026-03-08T12:00:00Z", team.employeeOfCId, "END"); // 08:00 EDT: 9 real hours after 22:00 EST (10 on the wall clock)
        String ended = me(team.employeeOfCId);
        assertThat((String) JsonPath.read(ended, "$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(ended, "$.data.workday.date")).isEqualTo("2026-03-07");
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.spanSeconds")).isEqualTo(9 * HOUR);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.productiveSeconds")).isEqualTo(8 * HOUR + 1800);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.breakSeconds")).isEqualTo(1800);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.shift.scheduledSeconds")).as("the night loses an hour").isEqualTo(7 * HOUR);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.varianceSeconds")).isEqualTo(HOUR + 1800);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.extraSeconds")).as("06:00 to 08:00 EDT").isEqualTo(2 * HOUR);
        assertThat(jdbcTemplate.queryForObject("SELECT work_date::text FROM workdays WHERE id = ?::uuid", String.class, workdayId)).isEqualTo("2026-03-07");

        // a business in India with New York as a display zone: the date and the figures follow the business zone, not the display zone
        jdbcTemplate.update("UPDATE businesses SET time_zone = 'Asia/Kolkata', display_time_zones = ARRAY['America/New_York']::TEXT[] WHERE id = ?",
                team.businessId);
        clock.setTo(START_2130);
        String indianWorkdayId = team.applyEvent(team.managerCId, "START"); // 12:00 in New York, 21:30 in India
        clock.setTo(NEXT_0830);
        String state = me(team.managerCId);
        assertThat((String) JsonPath.read(state, "$.data.businessTimeZone")).isEqualTo("Asia/Kolkata");
        assertThat((List<String>) JsonPath.read(state, "$.data.displayTimeZones")).containsExactly("America/New_York");
        assertThat((String) JsonPath.read(state, "$.data.workday.id")).isEqualTo(indianWorkdayId);
        assertThat((String) JsonPath.read(state, "$.data.workday.date")).isEqualTo(DATE);
        assertThat((String) JsonPath.read(state, "$.data.status")).isEqualTo("WORKING");
        assertThat((Integer) JsonPath.read(state, "$.data.workday.productiveSeconds")).isEqualTo(11 * HOUR);
    }
}

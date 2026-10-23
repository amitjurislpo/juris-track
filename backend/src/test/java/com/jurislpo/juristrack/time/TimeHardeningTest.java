package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The time logic holds under the conditions the review found: a clock that steps back, a day reopened late, times echoed by a
 * millisecond client, corrections far outside the workday or across another workday, and a suspended business. Business time zone
 * Asia/Kolkata (UTC+05:30), stale after 14 hours.
 */
class TimeHardeningTest extends IntegrationTest {

    private static final String REASON = "Confirmed with the employee";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    UUID businessId;
    UUID managerId;
    UUID employeeId;
    Cookie managerCookie;
    TimeTracker employee;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Hardening Co " + UUID.randomUUID());
        String managerEmail = TestData.uniqueEmail("hardening.manager");
        managerId = testData.createUser(businessId, "MANAGER", managerEmail, null);
        String employeeEmail = TestData.uniqueEmail("hardening.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, managerId);
        managerCookie = SignIn.session(mockMvc, managerEmail).accessCookie();
        employee = new TimeTracker(mockMvc, SignIn.session(mockMvc, employeeEmail).accessCookie());
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private ResultActions correct(String path, String body) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(patch(path)).cookie(managerCookie).content(body));
    }

    private ResultActions closeWorkday(String workdayId, String body) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(put("/api/workdays/" + workdayId + "/status")).cookie(managerCookie).content(body));
    }

    private List<UUID> workSessionIds(String workdayId) {
        return jdbcTemplate.queryForList("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at", UUID.class, workdayId);
    }

    private int correctionEntries() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.session_adjusted'", Integer.class,
                employeeId);
    }

    /** A whole day through the API: START at {@code startedAt}, END {@code hours} later; answers the workday id. */
    private String workedDay(String startedAt, int hours) throws Exception {
        clock.setTo(startedAt);
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.advance(Duration.ofHours(hours));
        employee.apply("END");
        return workdayId;
    }

    // ── M1: monotonic stamping ──

    @Test
    void aClockThatStepsBackNeverStampsAnIntervalBeforeThePreviousOne() throws Exception {
        clock.setTo("2026-10-20T04:00:00Z");
        employee.apply("START");
        clock.setTo("2026-10-20T05:00:00Z");
        employee.apply("BREAK");
        clock.setTo("2026-10-20T04:30:00Z"); // the clock stepped back half an hour (NTP, or another instance behind)
        employee.send("RESUME").andExpect(status().isCreated());
        clock.setTo("2026-10-20T04:40:00Z");
        employee.send("END").andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at < started_at", Integer.class, employeeId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at < started_at", Integer.class, employeeId))
                .isZero();
        assertThat(jdbcTemplate.queryForList("SELECT started_at FROM work_sessions WHERE employee_id = ? ORDER BY started_at", Timestamp.class, employeeId)
                .get(1).toInstant()).as("the resume is stamped at the latest recorded time").isEqualTo(Instant.parse("2026-10-20T05:00:00Z"));
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE employee_id = ?", Integer.class, employeeId)).isEqualTo(3600);
    }

    // ── M2: "not ended" after a reopen ──

    @Test
    void aReopenedDayIsMeasuredFromTheReopenForNotEnded() throws Exception {
        clock.setTo("2026-10-20T02:30:00Z"); // 08:00 in India
        employee.apply("START");
        clock.setTo("2026-10-20T11:30:00Z"); // 17:00
        employee.apply("END");
        clock.setTo("2026-10-20T17:00:00Z"); // 22:30: starts again
        employee.apply("START");
        clock.advance(Duration.ofSeconds(1));
        assertThat((Boolean) employee.stateValue("$.data.workday.isStale")).as("14 h 30 m since the morning, but just reopened").isFalse();
        assertThat((Integer) employee.stateValue("$.data.workday.spanSeconds")).as("the span still runs from the morning").isEqualTo(52_201);
        clock.setTo("2026-10-21T07:01:00Z"); // 14 h 01 m after the reopen: forgotten after all
        assertThat((Boolean) employee.stateValue("$.data.workday.isStale")).isTrue();
    }

    @Test
    void aGenuinelyForgottenDayIsStillNotEnded() throws Exception {
        clock.setTo("2026-10-20T02:30:00Z");
        employee.apply("START");
        clock.advance(Duration.ofMinutes(30));
        employee.apply("BREAK");
        clock.advance(Duration.ofMinutes(15));
        employee.apply("RESUME");
        clock.setTo("2026-10-20T16:31:00Z"); // 14 h 01 m after the start, breaks included
        assertThat((Boolean) employee.stateValue("$.data.workday.isStale")).isTrue();
    }

    // ── M5: millisecond precision ──

    @Test
    void eventsAreStampedToTheMillisecond() throws Exception {
        clock.setTo("2026-10-20T04:00:00.123456789Z");
        employee.apply("START");
        assertThat(jdbcTemplate.queryForObject("SELECT started_at FROM work_sessions WHERE employee_id = ?", Timestamp.class, employeeId).toInstant())
                .isEqualTo(Instant.parse("2026-10-20T04:00:00.123Z"));
    }

    @Test
    void aTimeSentBackAsReadChangesNothingAndEndingAtTheLastActivityWorks() throws Exception {
        clock.setTo("2026-10-20T12:00:00Z");
        // a day recorded with microseconds (as the API stored them before), read by a client that keeps milliseconds
        UUID completedWorkdayId = insertWorkday("2026-10-19", "2026-10-19T03:30:00.999500Z", "2026-10-19T04:30:00.999200Z", true);
        UUID workSessionId = insertWorkSession(completedWorkdayId, "2026-10-19T03:30:00.999500Z", "2026-10-19T04:30:00.999200Z");
        correct("/api/workdays/" + completedWorkdayId + "/work-sessions/" + workSessionId,
                "{\"startedAt\":\"2026-10-19T03:30:00.999Z\",\"endedAt\":\"2026-10-19T04:30:00.999Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Nothing changed."));
        assertThat(correctionEntries()).isZero();

        // a forgotten day on a break that started at .123456: "end at the last activity" as the client read it
        UUID openWorkdayId = insertWorkday("2026-10-20", "2026-10-20T03:30:00Z", null, false);
        insertWorkSession(openWorkdayId, "2026-10-20T03:30:00Z", "2026-10-20T05:00:00.123456Z");
        jdbcTemplate.update("INSERT INTO break_sessions (business_id, employee_id, workday_id, started_at, open_workday_id) VALUES (?, ?, ?, ?, ?)",
                businessId, employeeId, openWorkdayId, Timestamp.from(Instant.parse("2026-10-20T05:00:00.123456Z")), openWorkdayId);
        jdbcTemplate.update("UPDATE workdays SET status = 'ON_BREAK' WHERE id = ?", openWorkdayId);
        closeWorkday(openWorkdayId.toString(), "{\"status\":\"DAY_ENDED\",\"endedAt\":\"2026-10-20T05:00:00.123Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Workday ended."))
                .andExpect(jsonPath("$.data.productiveSeconds").value(5400));
    }

    // ── M3, M-4, L4: bounded corrections ──

    @Test
    void aCorrectionFarOutsideTheWorkdayIsRefusedWithACodeNeverA500() throws Exception {
        String workdayId = workedDay("2026-10-20T03:30:00Z", 8);
        clock.setTo("2026-10-22T12:00:00Z");
        String path = "/api/workdays/" + workdayId + "/work-sessions/" + workSessionIds(workdayId).get(0);
        // a wrong year, year 1 (used to overflow the totals: 500), and the evening before the workday's date in India
        for (String startedAt : List.of("2001-01-01T00:00:00Z", "0001-01-01T00:00:00Z", "2026-10-19T12:00:00Z")) {
            correct(path, "{\"startedAt\":\"" + startedAt + "\",\"reason\":\"" + REASON + "\"}").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"));
        }
        // the first interval moved to the next date, and an end past the end of the next day in India
        correct(path, "{\"startedAt\":\"2026-10-20T19:00:00Z\",\"endedAt\":\"2026-10-20T20:00:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"));
        correct(path, "{\"endedAt\":\"2026-10-21T19:00:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"));
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId)).isEqualTo(8 * 3600);
        // inside the window it works: a night-shift tail into the next morning
        correct(path, "{\"endedAt\":\"2026-10-21T01:30:00Z\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId)).isEqualTo(22 * 3600);
    }

    @Test
    void aCorrectionCannotOverlapAnotherWorkdayOfThePerson() throws Exception {
        String firstWorkdayId = workedDay("2026-10-20T03:30:00Z", 8);
        String secondWorkdayId = workedDay("2026-10-21T03:30:00Z", 8);
        clock.setTo("2026-10-22T12:00:00Z");
        // the first day's work stretched into the second day's hours: the same hours would count twice
        correct("/api/workdays/" + firstWorkdayId + "/work-sessions/" + workSessionIds(firstWorkdayId).get(0),
                "{\"endedAt\":\"2026-10-21T06:00:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WORKDAY_OVERLAP"));
        // touching is fine
        correct("/api/workdays/" + firstWorkdayId + "/work-sessions/" + workSessionIds(firstWorkdayId).get(0),
                "{\"endedAt\":\"2026-10-21T03:30:00Z\",\"reason\":\"" + REASON + "\"}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'time.correction_refused' AND reason = 'WORKDAY_OVERLAP' "
                + "AND subject_id = ?", Integer.class, employeeId)).isEqualTo(1);
        assertThat(secondWorkdayId).isNotEqualTo(firstWorkdayId);
    }

    @Test
    void anOldForgottenDayIsClosedOnlyWithAnEndInsideItsWindow() throws Exception {
        clock.setTo("2026-10-15T03:30:00Z");
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.setTo("2026-10-20T12:00:00Z");
        closeWorkday(workdayId, "{\"status\":\"DAY_ENDED\",\"reason\":\"" + REASON + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_OUT_OF_RANGE"));
        closeWorkday(workdayId, "{\"status\":\"DAY_ENDED\",\"endedAt\":\"2026-10-15T12:30:00Z\",\"reason\":\"" + REASON + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productiveSeconds").value(9 * 3600));
    }

    // ── extra cases ──

    @Test
    void aNewYorkNightShiftAcrossTheFallBackNightHasItsRealLength() throws Exception {
        jdbcTemplate.update("UPDATE businesses SET time_zone = 'America/New_York' WHERE id = ?", businessId);
        clock.setTo("2026-11-01T02:00:00Z"); // 22:00 EDT on 31 October
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.setTo("2026-11-01T11:00:00Z"); // 06:00 EST on 1 November: 9 hours later (the clock went back at 02:00)
        employee.apply("END");
        assertThat(jdbcTemplate.queryForMap("SELECT work_date::text AS work_date, productive_seconds FROM workdays WHERE id = ?::uuid", workdayId))
                .containsEntry("work_date", "2026-10-31").containsEntry("productive_seconds", 9 * 3600);
        employee.workday(workdayId).andExpect(status().isOk()).andExpect(jsonPath("$.data.spanSeconds").value(9 * 3600));
    }

    @Test
    void aLeapDayIsInTheMonthAndTheWeekThatStartsOnIt() throws Exception {
        clock.setTo("2016-02-29T03:30:00Z"); // Monday 29 February 2016, 09:00 in India
        employee.apply("START");
        clock.advance(Duration.ofHours(8));
        employee.apply("END");
        clock.setTo("2016-03-02T06:00:00Z"); // Wednesday
        employee.history("range=week").andExpect(status().isOk()).andExpect(jsonPath("$.data.from").value("2016-02-29"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.dailySeries.length()").value(3));
        employee.history("range=custom&from=2016-02-28&to=2016-03-01").andExpect(jsonPath("$.data.dailySeries.length()").value(3))
                .andExpect(jsonPath("$.data.dailySeries[1].date").value("2016-02-29"))
                .andExpect(jsonPath("$.data.dailySeries[1].productiveSeconds").value(8 * 3600));
        clock.setTo("2016-02-29T13:00:00Z");
        employee.history("range=month").andExpect(jsonPath("$.data.from").value("2016-02-01")).andExpect(jsonPath("$.data.to").value("2016-02-29"))
                .andExpect(jsonPath("$.data.dailySeries.length()").value(29));
    }

    @Test
    void historyTotalsCarryTheOneAverageDefinition() throws Exception {
        workedDay("2026-10-19T03:30:00Z", 1);
        clock.setTo("2026-10-20T03:30:00Z");
        employee.apply("START");
        clock.advance(Duration.ofSeconds(3601));
        employee.apply("END");
        clock.setTo("2026-10-21T12:00:00Z");
        // (3600 + 3601) / 2 = 3600.5, rounded half up
        employee.history("range=custom&from=2026-10-19&to=2026-10-21").andExpect(jsonPath("$.data.totals.averageProductiveSeconds").value(3601));
    }

    private UUID insertWorkday(String date, String startedAt, String endedAt, boolean isCompleted) {
        UUID workdayId = UUID.randomUUID();
        if (isCompleted) {
            jdbcTemplate.update("INSERT INTO workdays (id, business_id, employee_id, manager_id, work_date, time_zone, started_at, ended_at, status, "
                    + "productive_seconds, break_seconds, break_count) VALUES (?, ?, ?, ?, ?, 'Asia/Kolkata', ?, ?, 'COMPLETED', 3599, 0, 0)",
                    workdayId, businessId, employeeId, managerId, LocalDate.parse(date), Timestamp.from(Instant.parse(startedAt)),
                    Timestamp.from(Instant.parse(endedAt)));
        } else {
            jdbcTemplate.update("INSERT INTO workdays (id, business_id, employee_id, manager_id, work_date, time_zone, started_at, status, active_employee_id) "
                    + "VALUES (?, ?, ?, ?, ?, 'Asia/Kolkata', ?, 'WORKING', ?)", workdayId, businessId, employeeId, managerId, LocalDate.parse(date),
                    Timestamp.from(Instant.parse(startedAt)), employeeId);
        }
        return workdayId;
    }

    private UUID insertWorkSession(UUID workdayId, String startedAt, String endedAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO work_sessions (id, business_id, employee_id, workday_id, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, businessId, employeeId, workdayId, Timestamp.from(Instant.parse(startedAt)), Timestamp.from(Instant.parse(endedAt)));
        return sessionId;
    }
}

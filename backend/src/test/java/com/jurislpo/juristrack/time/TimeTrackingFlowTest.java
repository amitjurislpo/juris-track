package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The workday flow of the old {@code scripts/verify-flows.ts} ("Workday flow" and "Independent timers"), through the API with a
 * controlled clock: invalid events before starting, start, break (productive time frozen), end while on a break, resume, a second
 * break, end (totals = sum of the intervals), repeats, reopening after the end (owner decision D5, replacing the old check "starting
 * again the same day is rejected"), reading the state again, and one employee never touching another's records.
 */
class TimeTrackingFlowTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    UUID businessId;
    UUID employeeId;
    TimeTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Flow Co " + UUID.randomUUID()); // Asia/Kolkata
        String email = TestData.uniqueEmail("flow.alice");
        employeeId = testData.createUser(businessId, "EMPLOYEE", email, null);
        tracker = new TimeTracker(mockMvc, SignIn.session(mockMvc, email).accessCookie());
        clock.setTo("2026-10-01T03:30:00Z"); // 09:00 in India
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private void advance(int minutes) {
        clock.advance(Duration.ofMinutes(minutes));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, employeeId);
    }

    @Test
    void invalidEventsBeforeStartingAreRejected() throws Exception {
        tracker.send("BREAK").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WORKDAY_NOT_STARTED"))
                .andExpect(jsonPath("$.detail").value("Start your workday before taking a break."));
        tracker.send("RESUME").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ON_BREAK"))
                .andExpect(jsonPath("$.detail").value("You are not on a break."));
        tracker.send("END").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKDAY_NOT_STARTED"))
                .andExpect(jsonPath("$.detail").value("You have not started a workday."));
        assertThat((String) tracker.stateValue("$.data.status")).isEqualTo("NOT_STARTED");
        assertThat((Object) tracker.stateValue("$.data.workday")).isNull();
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ?")).isZero();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action LIKE 'time.%'")).isZero();
    }

    @Test
    void startOpensTheWorkdayWithOneOpenWorkSession() throws Exception {
        tracker.send("start").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Workday started. Have a productive day."))
                .andExpect(jsonPath("$.data.type").value("START"))
                .andExpect(jsonPath("$.data.alreadyApplied").value(false))
                .andExpect(jsonPath("$.data.state.status").value("WORKING"))
                .andExpect(jsonPath("$.data.workdayId").isString())
                .andExpect(jsonPath("$.data.state.employeeId").value(employeeId.toString()))
                .andExpect(jsonPath("$.data.state.today").value("2026-10-01"))
                .andExpect(jsonPath("$.data.state.businessTimeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.state.staleWorkdayHours").value(14))
                .andExpect(jsonPath("$.data.state.displayTimeZones", contains("Asia/Kolkata", "America/New_York")))
                .andExpect(jsonPath("$.data.state.serverTime").value("2026-10-01T03:30:00Z"))
                .andExpect(jsonPath("$.data.state.workday.date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.state.workday.startedAt").value("2026-10-01T03:30:00Z"))
                .andExpect(jsonPath("$.data.state.workday.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.state.workday.activeWorkStartedAt").value("2026-10-01T03:30:00Z"))
                .andExpect(jsonPath("$.data.state.workday.activeBreakStartedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.state.workday.isStale").value(false))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.type").value("STARTED_WORK"))
                .andExpect(jsonPath("$.data.state.workday.segments", hasSize(1)))
                .andExpect(jsonPath("$.data.state.workday.segments[0].type").value("WORK"))
                .andExpect(jsonPath("$.data.state.workday.segments[0].endedAt").value(nullValue()));
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ? AND status = 'WORKING' AND active_employee_id = employee_id")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL AND open_workday_id = workday_id")).isEqualTo(1);
        Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT business_id, actor_id, entity_type, \"after\"->>'status' AS status_after "
                + "FROM audit_logs WHERE subject_id = ? AND action = 'time.workday_started'", employeeId);
        assertThat(audit).containsEntry("business_id", businessId).containsEntry("actor_id", employeeId).containsEntry("entity_type", "workday")
                .containsEntry("status_after", "WORKING");
    }

    @Test
    void productiveTimeIsFrozenDuringABreakAndEndingWhileOnABreakIsRejected() throws Exception {
        tracker.apply("START");
        advance(60);
        tracker.send("BREAK").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Break started. Your productive timer is paused."))
                .andExpect(jsonPath("$.data.state.status").value("ON_BREAK"))
                .andExpect(jsonPath("$.data.state.workday.productiveSeconds").value(3600))
                .andExpect(jsonPath("$.data.state.workday.closedProductiveSeconds").value(3600))
                .andExpect(jsonPath("$.data.state.workday.activeWorkStartedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.state.workday.activeBreakStartedAt").value("2026-10-01T04:30:00Z"))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.type").value("STARTED_BREAK"));
        advance(30);
        String state = tracker.state();
        assertThat((Integer) JsonPath.read(state, "$.data.workday.productiveSeconds")).isEqualTo(3600);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.breakSeconds")).isEqualTo(1800);
        assertThat((Integer) JsonPath.read(state, "$.data.workday.breakCount")).isEqualTo(1);
        assertThat((String) JsonPath.read(state, "$.data.workday.lastBreak.startedAt")).isEqualTo("2026-10-01T04:30:00Z");
        assertThat((Object) JsonPath.read(state, "$.data.workday.lastBreak.endedAt")).isNull();

        tracker.send("END").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BREAK_IN_PROGRESS"))
                .andExpect(jsonPath("$.detail").value("You are on a break. Select Back to Work before ending your workday."));
        assertThat((String) tracker.stateValue("$.data.status")).isEqualTo("ON_BREAK");
    }

    @Test
    void resumeOpensANewWorkSessionAndASecondBreakIsRecordedSeparately() throws Exception {
        tracker.apply("START");
        advance(60);
        tracker.apply("BREAK");
        advance(15);
        tracker.send("RESUME").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Welcome back. Your productive timer is running."))
                .andExpect(jsonPath("$.data.state.status").value("WORKING"))
                .andExpect(jsonPath("$.data.state.workday.segments[*].type", contains("WORK", "BREAK", "WORK")))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.type").value("RESUMED_WORK"))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.at").value("2026-10-01T04:45:00Z"));
        advance(60);
        tracker.apply("BREAK");
        advance(10);
        tracker.send("RESUME").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.workday.breakCount").value(2))
                .andExpect(jsonPath("$.data.state.workday.lastBreak.startedAt").value("2026-10-01T05:45:00Z"))
                .andExpect(jsonPath("$.data.state.workday.lastBreak.endedAt").value("2026-10-01T05:55:00Z"))
                .andExpect(jsonPath("$.data.state.workday.breakSeconds").value(25 * 60))
                .andExpect(jsonPath("$.data.state.workday.productiveSeconds").value(120 * 60));
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ?")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at IS NOT NULL")).isEqualTo(2);
    }

    /** The 8-hour example of the old checks, recorded through the API: 09:00-18:00 with breaks 11:15-11:30 and 13:00-13:45. */
    @Test
    void endingTheDayStoresTotalsEqualToTheSumOfItsIntervals() throws Exception {
        tracker.apply("START");          // 09:00
        advance(135);
        tracker.apply("BREAK");          // 11:15
        advance(15);
        tracker.apply("RESUME");         // 11:30
        advance(90);
        tracker.apply("BREAK");          // 13:00
        advance(45);
        tracker.apply("RESUME");         // 13:45
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE employee_id = ?", Integer.class, employeeId))
                .as("no cached totals while the day is open").isNull();
        advance(255);
        String workdayId = JsonPath.read(tracker.send("END").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Workday ended. Your summary is ready."))
                .andExpect(jsonPath("$.data.state.status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.state.workday.endedAt").value("2026-10-01T12:30:00Z"))
                .andExpect(jsonPath("$.data.state.workday.productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.state.workday.breakSeconds").value(3600))
                .andExpect(jsonPath("$.data.state.workday.spanSeconds").value(9 * 3600))
                .andExpect(jsonPath("$.data.state.workday.breakCount").value(2))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.type").value("ENDED_DAY"))
                .andReturn().getResponse().getContentAsString(), "$.data.state.workday.id");

        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT status::text AS status, active_employee_id, productive_seconds, break_seconds, "
                + "break_count FROM workdays WHERE id = ?::uuid", workdayId);
        int workSum = jdbcTemplate.queryForObject("SELECT sum(floor(extract(epoch FROM ended_at - started_at)))::int FROM work_sessions "
                + "WHERE workday_id = ?::uuid", Integer.class, workdayId);
        int breakSum = jdbcTemplate.queryForObject("SELECT sum(floor(extract(epoch FROM ended_at - started_at)))::int FROM break_sessions "
                + "WHERE workday_id = ?::uuid", Integer.class, workdayId);
        assertThat(workday).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", workSum)
                .containsEntry("break_seconds", breakSum).containsEntry("break_count", 2);
        assertThat(workday.get("active_employee_id")).isNull();
        assertThat(workSum).isEqualTo(8 * 3600);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL")
                + count("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at IS NULL")).isZero();
    }

    @Test
    void repeatedEventsChangeNothingAndAnswerTheCurrentState() throws Exception {
        tracker.apply("START");
        tracker.send("START").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.alreadyApplied").value(true))
                .andExpect(jsonPath("$.data.state.status").value("WORKING"));
        advance(30);
        tracker.apply("BREAK");
        tracker.send("BREAK").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));
        tracker.send("START").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true))
                .andExpect(jsonPath("$.data.state.status").value("ON_BREAK"));
        advance(5);
        tracker.apply("RESUME");
        tracker.send("RESUME").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));
        advance(30);
        tracker.apply("END");
        int auditEntries = count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action LIKE 'time.%'");
        tracker.send("END").andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true))
                .andExpect(jsonPath("$.data.state.status").value("DAY_ENDED"));
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ?")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM break_sessions WHERE employee_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action LIKE 'time.%'")).as("a repeat writes no audit entry").isEqualTo(auditEntries);
        assertThat(auditEntries).isEqualTo(4);
    }

    @Test
    void startingAgainAfterTheEndReopensTheSameWorkdayAndTheGapCountsAsNeitherWorkNorBreak() throws Exception {
        tracker.apply("START");                                     // 09:00
        advance(240);
        tracker.apply("BREAK");                                     // 13:00
        advance(60);
        tracker.apply("RESUME");                                    // 14:00
        advance(240);
        String workdayId = JsonPath.read(tracker.apply("END"), "$.data.state.workday.id"); // 18:00: 8 h work, 1 h break
        tracker.send("BREAK").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKDAY_NOT_STARTED"));
        tracker.send("RESUME").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ON_BREAK"));

        advance(60);
        tracker.send("START").andExpect(status().isCreated())                         // 19:00: reopened
                .andExpect(jsonPath("$.data.alreadyApplied").value(false))
                .andExpect(jsonPath("$.data.state.status").value("WORKING"))
                .andExpect(jsonPath("$.data.state.workday.id").value(workdayId))
                .andExpect(jsonPath("$.data.state.workday.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.state.workday.productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.state.workday.segments[*].type", contains("WORK", "BREAK", "WORK", "WORK")))
                .andExpect(jsonPath("$.data.state.workday.lastActivity.type").value("RESUMED_WORK"));
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId)).isNull();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.workday_reopened'")).isEqualTo(1);

        advance(60);
        tracker.send("END").andExpect(status().isCreated())                           // 20:00
                .andExpect(jsonPath("$.data.state.workday.productiveSeconds").value(9 * 3600))
                .andExpect(jsonPath("$.data.state.workday.breakSeconds").value(3600))
                .andExpect(jsonPath("$.data.state.workday.breakCount").value(1))
                .andExpect(jsonPath("$.data.state.workday.spanSeconds").value(11 * 3600));
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ?")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId))
                .isEqualTo(9 * 3600);
    }

    @Test
    void theStateIsIdenticalWhenReadAgain() throws Exception {
        tracker.apply("START");
        advance(90);
        tracker.apply("BREAK");
        advance(10);
        String first = tracker.state();
        String second = tracker.state();
        assertThat(second).isEqualTo(first);
        advance(20);
        tracker.apply("RESUME");
        advance(30);
        tracker.apply("END");
        assertThat(tracker.state()).isEqualTo(tracker.state());
        assertThat((Integer) tracker.stateValue("$.data.workday.productiveSeconds")).isEqualTo(120 * 60);
    }

    @Test
    void oneEmployeesEventsNeverTouchAnotherEmployeesRecords() throws Exception {
        String carolEmail = TestData.uniqueEmail("flow.carol");
        UUID carolId = testData.createUser(businessId, "EMPLOYEE", carolEmail, null);
        TimeTracker carol = new TimeTracker(mockMvc, SignIn.session(mockMvc, carolEmail).accessCookie());
        carol.apply("START");
        String aliceBefore = tracker.state();
        List<Map<String, Object>> carolSessionsBefore = jdbcTemplate.queryForList(
                "SELECT id, started_at, ended_at FROM work_sessions WHERE employee_id = ? ORDER BY started_at", carolId);

        tracker.apply("START");
        advance(30);
        tracker.apply("BREAK");
        advance(5);
        tracker.apply("RESUME");
        advance(30);
        tracker.apply("END");

        assertThat(jdbcTemplate.queryForList("SELECT id, started_at, ended_at FROM work_sessions WHERE employee_id = ? ORDER BY started_at", carolId))
                .isEqualTo(carolSessionsBefore);
        assertThat((String) carol.stateValue("$.data.status")).isEqualTo("WORKING");
        assertThat((String) tracker.stateValue("$.data.status")).isEqualTo("DAY_ENDED");
        assertThat((String) JsonPath.read(aliceBefore, "$.data.status")).isEqualTo("NOT_STARTED");
    }
}

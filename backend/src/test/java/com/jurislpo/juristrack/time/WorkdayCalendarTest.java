package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Which date a workday belongs to (the calendar date in the business time zone when it started, kept when the day runs past
 * midnight), in India and in New York across the spring-forward day; the manager snapshot on the workday; the "not ended" flag.
 */
class WorkdayCalendarTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    UUID businessId;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Calendar Co " + UUID.randomUUID()); // Asia/Kolkata, stale after 14 hours
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private UUID employeeId;

    private TimeTracker signInEmployee(UUID managerId) throws Exception {
        String email = TestData.uniqueEmail("calendar.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", email, managerId);
        return new TimeTracker(mockMvc, SignIn.session(mockMvc, email).accessCookie());
    }

    private void useTimeZone(String timeZone) {
        jdbcTemplate.update("UPDATE businesses SET time_zone = ? WHERE id = ?", timeZone, businessId);
    }

    private List<Map<String, Object>> workdaysOfEmployee() {
        return jdbcTemplate.queryForList("SELECT work_date::text AS work_date, time_zone, manager_id, status::text AS status FROM workdays "
                + "WHERE employee_id = ? ORDER BY started_at", employeeId);
    }

    @Test
    void anIndianWorkdayStartedAfterMidnightBelongsToTheIndianDate() throws Exception {
        TimeTracker tracker = signInEmployee(null);
        clock.setTo("2026-10-22T19:00:00Z"); // 00:30 on 23 October in India, still the 22nd in UTC
        tracker.send("START").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.today").value("2026-10-23"))
                .andExpect(jsonPath("$.data.state.workday.date").value("2026-10-23"));
        assertThat(workdaysOfEmployee()).singleElement().satisfies(workday -> assertThat(workday)
                .containsEntry("work_date", "2026-10-23").containsEntry("time_zone", "Asia/Kolkata"));
    }

    @Test
    void aNightShiftCrossingMidnightKeepsTheDateItStartedOn() throws Exception {
        TimeTracker tracker = signInEmployee(null);
        clock.setTo("2026-10-22T16:30:00Z"); // 22:00 on 22 October in India
        tracker.apply("START");
        clock.advance(Duration.ofHours(4));  // 02:00 on the 23rd
        tracker.apply("BREAK");
        clock.advance(Duration.ofMinutes(30));
        tracker.apply("RESUME");
        clock.advance(Duration.ofHours(4));  // 06:30 on the 23rd
        String state = tracker.state();
        assertThat((String) JsonPath.read(state, "$.data.today")).isEqualTo("2026-10-23");
        assertThat((String) JsonPath.read(state, "$.data.status")).as("the open day from yesterday is still the current one").isEqualTo("WORKING");
        assertThat((String) JsonPath.read(state, "$.data.workday.date")).isEqualTo("2026-10-22");
        // the business has only the default shift (the calendar day): once ended, today (the 23rd) has no workday yet, so the state
        // says NOT_STARTED; the answer names the ended day. A night shift is a shift of its own (see NightShiftTest).
        String endAnswer = tracker.send("END").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.state.workday").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        tracker.workday(JsonPath.read(endAnswer, "$.data.workdayId")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-10-22"))
                .andExpect(jsonPath("$.data.status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.spanSeconds").value(8 * 3600 + 1800));

        // starting again opens a new workday for the 23rd (not a reopen of the 22nd)
        clock.advance(Duration.ofHours(3));
        tracker.send("START").andExpect(status().isCreated()).andExpect(jsonPath("$.data.state.workday.date").value("2026-10-23"));
        assertThat(workdaysOfEmployee()).extracting(workday -> workday.get("work_date")).containsExactly("2026-10-22", "2026-10-23");
    }

    @Test
    void newYorkDatesFollowTheBusinessTimeZoneAcrossTheSpringForwardNight() throws Exception {
        useTimeZone("America/New_York");
        TimeTracker tracker = signInEmployee(null);
        clock.setTo("2026-03-08T04:30:00Z"); // 23:30 EST on 7 March; clocks jump from 02:00 EST to 03:00 EDT on the 8th
        tracker.send("START").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.today").value("2026-03-07"))
                .andExpect(jsonPath("$.data.state.businessTimeZone").value("America/New_York"))
                .andExpect(jsonPath("$.data.state.workday.date").value("2026-03-07"));
        clock.setTo("2026-03-08T12:00:00Z"); // 08:00 EDT: 7.5 real hours later, although the wall clock moved 8.5 hours
        String endAnswer = tracker.send("END").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.today").value("2026-03-08"))
                .andReturn().getResponse().getContentAsString();
        tracker.workday(JsonPath.read(endAnswer, "$.data.workdayId"))
                .andExpect(jsonPath("$.data.date").value("2026-03-07"))
                .andExpect(jsonPath("$.data.productiveSeconds").value(7 * 3600 + 1800));
        clock.setTo("2026-03-08T13:00:00Z"); // 09:00 EDT on the 8th
        tracker.send("START").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state.today").value("2026-03-08"))
                .andExpect(jsonPath("$.data.state.workday.date").value("2026-03-08"));

        TimeTracker earlyBird = signInEmployee(null);
        clock.setTo("2026-03-08T05:30:00Z"); // 00:30 EST on the 8th (before the jump)
        earlyBird.send("START").andExpect(status().isCreated()).andExpect(jsonPath("$.data.state.workday.date").value("2026-03-08"));
        assertThat(workdaysOfEmployee()).singleElement().satisfies(workday -> assertThat(workday).containsEntry("time_zone", "America/New_York"));
    }

    @Test
    void aWorkdayKeepsTheManagerTheEmployeeHadWhenItStarted() throws Exception {
        UUID firstManagerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("calendar.manager.one"), null);
        UUID secondManagerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("calendar.manager.two"), null);
        TimeTracker tracker = signInEmployee(firstManagerId);
        clock.setTo("2026-10-01T03:30:00Z");
        tracker.apply("START");
        jdbcTemplate.update("UPDATE users SET manager_id = ? WHERE id = ?", secondManagerId, employeeId);
        clock.advance(Duration.ofHours(8));
        tracker.apply("END");
        clock.advance(Duration.ofDays(1));
        tracker.apply("START");
        assertThat(workdaysOfEmployee()).extracting(workday -> workday.get("manager_id")).containsExactly(firstManagerId, secondManagerId);
    }

    @Test
    void anOpenWorkdayLongerThanTheStaleHoursIsFlaggedNotEnded() throws Exception {
        TimeTracker tracker = signInEmployee(null);
        clock.setTo("2026-10-01T03:30:00Z");
        String workdayId = JsonPath.read(tracker.apply("START"), "$.data.state.workday.id");
        clock.advance(Duration.ofHours(14));
        assertThat((Boolean) tracker.stateValue("$.data.workday.isStale")).as("exactly 14 hours is not yet stale").isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat((Boolean) tracker.stateValue("$.data.workday.isStale")).isTrue();
        tracker.history("range=custom&from=2026-10-01&to=2026-10-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].isStale").value(true));
        tracker.workday(workdayId).andExpect(jsonPath("$.data.isStale").value(true));

        jdbcTemplate.update("UPDATE businesses SET stale_workday_hours = 20 WHERE id = ?", businessId);
        assertThat((Boolean) tracker.stateValue("$.data.workday.isStale")).as("read-time flag follows the setting").isFalse();
        jdbcTemplate.update("UPDATE businesses SET stale_workday_hours = 14 WHERE id = ?", businessId);
        tracker.apply("END");
        assertThat((Boolean) tracker.stateValue("$.data.workday.isStale")).as("an ended day is never stale").isFalse();
    }
}

package com.jurislpo.juristrack.time;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/workdays/mine} and {@code GET /api/workdays/{workdayId}}: the date range presets, paging and sorting, the totals over
 * the whole range and the zero-filled daily series, live figures for the open day. Business in India; "today" is Wednesday
 * 21 October 2026, 11:00, and the workdays are recorded through the API on their days.
 */
class WorkdayHistoryTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TimeTracker tracker;
    String workdayOfTwentiethId;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        UUID businessId = testData.createBusiness("History Co " + UUID.randomUUID());
        String email = TestData.uniqueEmail("history.employee");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        tracker = new TimeTracker(mockMvc, SignIn.session(mockMvc, email).accessCookie());

        recordDay("2026-09-30", 240, 0, 0);    // last month: 4 h
        recordDay("2026-10-05", 240, 60, 180);  // 7 h work, 1 h break
        recordDay("2026-10-19", 240, 0, 0);     // Monday: 4 h
        workdayOfTwentiethId = recordDay("2026-10-20", 180, 30, 150); // 5.5 h work, 30 min break
        clock.setTo("2026-10-21T03:30:00Z");    // today, 09:00: started, still working at 11:00
        tracker.apply("START");
        clock.advance(Duration.ofHours(2));
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    /** A day starting at 09:00 India time: work, an optional break, more work, then the end of the day. Returns the workday id. */
    private String recordDay(String date, int workMinutes, int breakMinutes, int moreWorkMinutes) throws Exception {
        clock.setTo(Instant.parse(date + "T03:30:00Z"));
        String workdayId = JsonPath.read(tracker.apply("START"), "$.data.state.workday.id");
        clock.advance(Duration.ofMinutes(workMinutes));
        if (breakMinutes > 0) {
            tracker.apply("BREAK");
            clock.advance(Duration.ofMinutes(breakMinutes));
            tracker.apply("RESUME");
            clock.advance(Duration.ofMinutes(moreWorkMinutes));
        }
        tracker.apply("END");
        return workdayId;
    }

    @Test
    void theDefaultIsThisMonthNewestFirstWithTotalsAndADailySeries() throws Exception {
        tracker.history("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("month"))
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.to").value("2026-10-21"))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(25))
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-21", "2026-10-20", "2026-10-19", "2026-10-05")))
                .andExpect(jsonPath("$.data.rows[0].status").value("WORKING"))
                .andExpect(jsonPath("$.data.rows[0].endedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(7200))
                .andExpect(jsonPath("$.data.rows[0].spanSeconds").value(7200))
                .andExpect(jsonPath("$.data.rows[0].isStale").value(false))
                .andExpect(jsonPath("$.data.rows[1].status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.rows[1].startedAt").value("2026-10-20T03:30:00Z"))
                .andExpect(jsonPath("$.data.rows[1].endedAt").value("2026-10-20T09:30:00Z"))
                .andExpect(jsonPath("$.data.rows[1].productiveSeconds").value(5 * 3600 + 1800))
                .andExpect(jsonPath("$.data.rows[1].breakSeconds").value(1800))
                .andExpect(jsonPath("$.data.rows[1].spanSeconds").value(6 * 3600))
                .andExpect(jsonPath("$.data.rows[1].breakCount").value(1))
                // the break of a finished day comes from its break session, a day without a break has none
                .andExpect(jsonPath("$.data.rows[1].lastBreak.startedAt").value("2026-10-20T06:30:00Z"))
                .andExpect(jsonPath("$.data.rows[1].lastBreak.endedAt").value("2026-10-20T07:00:00Z"))
                .andExpect(jsonPath("$.data.rows[0].lastBreak").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].lastBreak").value(nullValue()))
                .andExpect(jsonPath("$.data.totals.workdays").value(4))
                .andExpect(jsonPath("$.data.totals.productiveSeconds").value(18 * 3600 + 1800))
                .andExpect(jsonPath("$.data.totals.breakSeconds").value(5400))
                .andExpect(jsonPath("$.data.totals.spanSeconds").value(20 * 3600))
                .andExpect(jsonPath("$.data.totals.breakCount").value(2))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(21)))
                .andExpect(jsonPath("$.data.dailySeries[0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.data.dailySeries[0].productiveSeconds").value(0))
                .andExpect(jsonPath("$.data.dailySeries[0].workdays").value(0))
                .andExpect(jsonPath("$.data.dailySeries[4].date").value("2026-10-05"))
                .andExpect(jsonPath("$.data.dailySeries[4].productiveSeconds").value(7 * 3600))
                .andExpect(jsonPath("$.data.dailySeries[4].breakSeconds").value(3600))
                .andExpect(jsonPath("$.data.dailySeries[4].workdays").value(1))
                .andExpect(jsonPath("$.data.dailySeries[20].date").value("2026-10-21"))
                .andExpect(jsonPath("$.data.dailySeries[20].productiveSeconds").value(7200));
    }

    @Test
    void thePresetsResolveAgainstTodayInTheBusinessTimeZone() throws Exception {
        tracker.history("range=today").andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-21")))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(1)));
        tracker.history("range=yesterday").andExpect(jsonPath("$.data.from").value("2026-10-20"))
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-20")));
        tracker.history("range=week").andExpect(jsonPath("$.data.from").value("2026-10-19"))
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-21", "2026-10-20", "2026-10-19")));
        tracker.history("range=custom&from=2026-09-30&to=2026-10-05").andExpect(jsonPath("$.data.range").value("custom"))
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-05", "2026-09-30")))
                .andExpect(jsonPath("$.data.totals.productiveSeconds").value(11 * 3600))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(6)));
        // reversed ends are swapped
        tracker.history("range=custom&from=2026-10-05&to=2026-09-30")
                .andExpect(jsonPath("$.data.from").value("2026-09-30")).andExpect(jsonPath("$.data.to").value("2026-10-05"));
        tracker.history("range=custom&from=2026-10-20").andExpect(jsonPath("$.data.to").value("2026-10-21"))
                .andExpect(jsonPath("$.data.total").value(2));
        // at most 366 days: the end is kept, the start is moved
        tracker.history("range=custom&from=2024-01-01&to=2026-10-21")
                .andExpect(jsonPath("$.data.from").value("2025-10-21")).andExpect(jsonPath("$.data.total").value(5));
    }

    @Test
    void rowsArePagedAndSortedButTotalsCoverTheWholeRange() throws Exception {
        tracker.history("pageSize=2&page=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-19", "2026-10-05")))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totals.workdays").value(4));
        tracker.history("pageSize=2&page=3").andExpect(jsonPath("$.data.rows", hasSize(0))).andExpect(jsonPath("$.data.total").value(4));
        tracker.history("sort=date&sortDirection=asc").andExpect(jsonPath("$.data.rows[*].date",
                contains("2026-10-05", "2026-10-19", "2026-10-20", "2026-10-21")));
        tracker.history("sort=productiveSeconds&sortDirection=asc").andExpect(jsonPath("$.data.rows[*].productiveSeconds",
                contains(7200, 4 * 3600, 5 * 3600 + 1800, 7 * 3600)));
        tracker.history("sort=breakCount").andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-20", "2026-10-05", "2026-10-21",
                "2026-10-19")));
        tracker.history("sort=endedAt&sortDirection=asc").andExpect(jsonPath("$.data.rows[3].date").value("2026-10-21"));
    }

    @Test
    void badParametersAre400() throws Exception {
        tracker.history("range=decade").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("range"));
        tracker.history("range=custom&from=2026-13-01&to=yesterday").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("from", "to")));
        tracker.history("sort=employee").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("sort"));
        tracker.history("pageSize=0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("pageSize"));
        tracker.history("sortDirection=up").andExpect(status().isBadRequest());
    }

    @Test
    void oneWorkdayShowsEveryWorkAndBreakInterval() throws Exception {
        tracker.workday(workdayOfTwentiethId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(workdayOfTwentiethId))
                .andExpect(jsonPath("$.data.date").value("2026-10-20"))
                .andExpect(jsonPath("$.data.status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.segments[*].type", contains("WORK", "BREAK", "WORK")))
                .andExpect(jsonPath("$.data.segments[1].startedAt").value("2026-10-20T06:30:00Z"))
                .andExpect(jsonPath("$.data.segments[1].endedAt").value("2026-10-20T07:00:00Z"))
                .andExpect(jsonPath("$.data.productiveSeconds").value(5 * 3600 + 1800))
                .andExpect(jsonPath("$.data.breakSeconds").value(1800))
                .andExpect(jsonPath("$.data.closedProductiveSeconds").value(5 * 3600 + 1800))
                .andExpect(jsonPath("$.data.activeWorkStartedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.lastActivity.type").value("ENDED_DAY"));
    }
}

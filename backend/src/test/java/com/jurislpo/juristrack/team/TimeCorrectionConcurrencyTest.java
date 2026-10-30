package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.service.RateLimiter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A manager's correction racing with the employee's own breaks and resumes: both lock the employee's row, so they run one after the
 * other. Nothing fails, the workday always has exactly one running interval matching its status, its intervals never overlap, and
 * every applied correction has its audit entry.
 */
class TimeCorrectionConcurrencyTest extends IntegrationTest {

    private static final int ROUNDS = 4;
    private static final int REQUESTS_PER_ROUND = 10;
    private static final String REASON = "Started earlier than recorded";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;
    ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        // sign everybody in before the race
        team.cookieOf(team.employeeId);
        team.cookieOf(team.managerBId);
        executor = Executors.newFixedThreadPool(REQUESTS_PER_ROUND);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.followSystemTime();
    }

    @Test
    void correctionsAndTimeEventsOfTheSamePersonNeverCorruptTheWorkday() throws Exception {
        clock.setTo("2026-10-05T03:30:00Z");
        String workdayId = team.applyEvent(team.employeeId, "START");
        String firstSessionPath = "/api/v1/workdays/" + workdayId + "/work-sessions/"
                + jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid", String.class, workdayId);
        List<Integer> correctionStatuses = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            clock.setTo(Instant.parse("2026-10-05T04:00:00Z").plusSeconds(600L * round));
            CountDownLatch startSignal = new CountDownLatch(1);
            List<Future<Integer>> corrections = new ArrayList<>();
            List<Future<Integer>> events = new ArrayList<>();
            for (int i = 0; i < REQUESTS_PER_ROUND; i++) {
                if (i % 2 == 0) {
                    String start = Instant.parse("2026-10-05T03:00:00Z").minusSeconds(60L * (round * REQUESTS_PER_ROUND + i)).toString();
                    Callable<Integer> correction = () -> {
                        startSignal.await();
                        return team.patchJson(team.managerBId, firstSessionPath, "{\"startedAt\":\"" + start + "\",\"reason\":\"" + REASON + "\"}")
                                .andReturn().getResponse().getStatus();
                    };
                    corrections.add(executor.submit(correction));
                } else {
                    String type = i % 4 == 1 ? "BREAK" : "RESUME";
                    Callable<Integer> event = () -> {
                        startSignal.await();
                        return team.sendEvent(team.employeeId, type).andReturn().getResponse().getStatus();
                    };
                    events.add(executor.submit(event));
                }
            }
            startSignal.countDown();
            for (Future<Integer> correction : corrections) correctionStatuses.add(correction.get(60, TimeUnit.SECONDS));
            for (Future<Integer> event : events) assertThat(event.get(60, TimeUnit.SECONDS)).isIn(200, 201);
            assertConsistent(workdayId);
        }
        assertThat(correctionStatuses).containsOnly(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'time.session_adjusted' AND subject_id = ?",
                Integer.class, team.employeeId)).isEqualTo(ROUNDS * REQUESTS_PER_ROUND / 2);
    }

    private void assertConsistent(String workdayId) {
        int openWorkSessions = jdbcTemplate.queryForObject("SELECT count(*) FROM work_sessions WHERE workday_id = ?::uuid AND ended_at IS NULL",
                Integer.class, workdayId);
        int openBreakSessions = jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE workday_id = ?::uuid AND ended_at IS NULL",
                Integer.class, workdayId);
        assertThat(openWorkSessions + openBreakSessions).as("exactly one interval runs").isEqualTo(1);
        String status = jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE id = ?::uuid", String.class, workdayId);
        assertThat(status).isEqualTo(openWorkSessions == 1 ? "WORKING" : "ON_BREAK");
        List<Map<String, Object>> overlaps = jdbcTemplate.queryForList("""
                WITH intervals AS (
                    SELECT id, started_at, COALESCE(ended_at, 'infinity'::timestamptz) AS ended_at FROM work_sessions WHERE workday_id = ?::uuid
                    UNION ALL
                    SELECT id, started_at, COALESCE(ended_at, 'infinity'::timestamptz) FROM break_sessions WHERE workday_id = ?::uuid)
                SELECT first_interval.id FROM intervals first_interval JOIN intervals second_interval ON first_interval.id <> second_interval.id
                WHERE first_interval.started_at < second_interval.ended_at AND second_interval.started_at < first_interval.ended_at""",
                workdayId, workdayId);
        assertThat(overlaps).as("no two intervals overlap").isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT started_at = (SELECT min(started_at) FROM work_sessions WHERE workday_id = ?::uuid)"
                + " FROM workdays WHERE id = ?::uuid", Boolean.class, workdayId, workdayId)).as("the day starts with its first interval").isTrue();
    }
}

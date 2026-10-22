package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Double clicks and parallel requests ("Concurrency" of the old verify-flows checks): the employee's row lock serializes them, so
 * exactly one request changes the state and the others answer {@code alreadyApplied}; nothing is ever duplicated and the workday
 * never has both or neither interval open.
 */
class TimeTrackingConcurrencyTest extends IntegrationTest {

    private static final int PARALLEL_REQUESTS = 10;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    UUID employeeId;
    TimeTracker tracker;
    ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        UUID businessId = testData.createBusiness("Parallel Co " + UUID.randomUUID());
        String email = TestData.uniqueEmail("parallel.bob");
        employeeId = testData.createUser(businessId, "EMPLOYEE", email, null);
        tracker = new TimeTracker(mockMvc, SignIn.session(mockMvc, email).accessCookie());
        clock.setTo("2026-10-05T04:00:00Z");
        executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.followSystemTime();
    }

    /** Sends {@code count} events at the same moment; returns each answer's HTTP status. */
    private List<Integer> sendInParallel(int count, IntFunction<String> typeOfRequest) throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String type = typeOfRequest.apply(i);
            Callable<Integer> request = () -> {
                startSignal.await();
                return tracker.send(type).andReturn().getResponse().getStatus();
            };
            answers.add(executor.submit(request));
        }
        startSignal.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> answer : answers) statuses.add(answer.get(60, TimeUnit.SECONDS));
        return statuses;
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, employeeId);
    }

    private static void assertExactlyOneApplied(List<Integer> statuses) {
        assertThat(statuses).as("one request applied, the others are repeats").containsOnly(200, 201);
        assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
    }

    @Test
    void tenParallelStartsCreateExactlyOneWorkdayAndOneOpenSession() throws Exception {
        assertExactlyOneApplied(sendInParallel(PARALLEL_REQUESTS, i -> "START"));
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.workday_started'")).isEqualTo(1);
    }

    @Test
    void tenParallelBreaksCreateExactlyOneBreak() throws Exception {
        tracker.apply("START");
        clock.advance(Duration.ofMinutes(30));
        assertExactlyOneApplied(sendInParallel(PARALLEL_REQUESTS, i -> "BREAK"));
        assertThat(count("SELECT count(*) FROM break_sessions WHERE employee_id = ?")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL")).isZero();
    }

    @Test
    void tenParallelResumesCreateExactlyOneNewWorkSession() throws Exception {
        tracker.apply("START");
        clock.advance(Duration.ofMinutes(30));
        tracker.apply("BREAK");
        clock.advance(Duration.ofMinutes(10));
        assertExactlyOneApplied(sendInParallel(PARALLEL_REQUESTS, i -> "RESUME"));
        assertThat(count("SELECT count(*) FROM work_sessions WHERE employee_id = ?")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at IS NULL")).isZero();
    }

    @Test
    void mixedParallelBreaksAndResumesNeverLeaveBothOrNeitherIntervalOpen() throws Exception {
        tracker.apply("START");
        for (int round = 0; round < 3; round++) {
            clock.advance(Duration.ofMinutes(10));
            assertThat(sendInParallel(12, i -> i % 2 == 1 ? "BREAK" : "RESUME")).containsOnly(200, 201);
            int openWorkSessions = count("SELECT count(*) FROM work_sessions WHERE employee_id = ? AND ended_at IS NULL");
            int openBreakSessions = count("SELECT count(*) FROM break_sessions WHERE employee_id = ? AND ended_at IS NULL");
            assertThat(openWorkSessions + openBreakSessions).as("exactly one interval open").isEqualTo(1);
            String status = jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE employee_id = ?", String.class, employeeId);
            assertThat(status).isEqualTo(openWorkSessions == 1 ? "WORKING" : "ON_BREAK");
            assertThat((String) tracker.stateValue("$.data.status")).isEqualTo(status);
        }
    }

    @Test
    void tenParallelEndsCompleteTheDayOnce() throws Exception {
        tracker.apply("START");
        clock.advance(Duration.ofMinutes(30));
        assertExactlyOneApplied(sendInParallel(PARALLEL_REQUESTS, i -> "END"));
        assertThat(count("SELECT count(*) FROM workdays WHERE employee_id = ? AND status = 'COMPLETED' AND productive_seconds = 1800")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND action = 'time.workday_ended'")).isEqualTo(1);
    }
}

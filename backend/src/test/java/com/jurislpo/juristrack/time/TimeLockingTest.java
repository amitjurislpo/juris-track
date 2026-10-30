package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.service.RateLimiter;
import com.jurislpo.juristrack.service.TeamService;
import com.jurislpo.juristrack.service.TimeTrackingService;
import com.jurislpo.juristrack.service.WorkdayHistoryService;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who waits for whom, and what is read and written under the employee's lock: a status change locks first and writes only what
 * changed (no lost update, no double deactivation), a person's event never waits for their manager's lock, a collision over
 * inconsistent records is not hidden as a repeat, a suspension closes every open workday, and the snapshots are read consistently.
 */
class TimeLockingTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EmployeeLockRepository employeeLockRepository;
    @Autowired TimeTrackingService timeTrackingService;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID managerId;
    UUID employeeId;
    Cookie ownerCookie;
    TimeTracker employee;
    ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Locking Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("locking.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("locking.manager"), ownerId);
        String employeeEmail = TestData.uniqueEmail("locking.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, managerId);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        employee = new TimeTracker(mockMvc, SignIn.session(mockMvc, employeeEmail).accessCookie());
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.followSystemTime();
    }

    private Future<Integer> deactivateInBackground() {
        return executor.submit(() -> mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie)
                .content("{\"isActive\":false,\"reason\":\"Left\"}")).andReturn().getResponse().getStatus());
    }

    /** Waits until {@code count} other database sessions wait for a row lock (at most 10 seconds). */
    private void awaitLockWaiters(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + count + " sessions waiting for a lock");
    }

    // ── M7 ──

    @Test
    void aDeactivationWaitingForTheLockKeepsAConcurrentEditOfOtherFields() throws Exception {
        UUID newManagerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("locking.newmanager"), ownerId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, employeeId);
                lock.executeQuery();
            }
            Future<Integer> deactivation = deactivateInBackground();
            awaitLockWaiters(1);
            // meanwhile another tab gives the employee a new manager and commits
            try (PreparedStatement edit = connection.prepareStatement("UPDATE users SET manager_id = ? WHERE id = ?")) {
                edit.setObject(1, newManagerId);
                edit.setObject(2, employeeId);
                edit.executeUpdate();
            }
            connection.commit();
            assertThat(deactivation.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbcTemplate.queryForMap("SELECT manager_id, is_active FROM users WHERE id = ?", employeeId))
                .containsEntry("manager_id", newManagerId).containsEntry("is_active", false);
    }

    @Test
    void twoConcurrentDeactivationsApplyOnce() throws Exception {
        List<Future<Integer>> deactivations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, employeeId);
                lock.executeQuery();
            }
            deactivations.add(deactivateInBackground());
            deactivations.add(deactivateInBackground());
            awaitLockWaiters(2);
            connection.commit();
        }
        for (Future<Integer> deactivation : deactivations) assertThat(deactivation.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'employee.deactivated' AND subject_id = ?", Integer.class,
                employeeId)).isEqualTo(1);
    }

    // ── L2 ──

    @Test
    void aPersonsEventNeverWaitsForTheirManagersLock() throws Exception {
        CountDownLatch managerLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate managerTransaction = new TransactionTemplate(transactionManager);
        Future<?> managerHoldsTheLock = executor.submit(() -> managerTransaction.executeWithoutResult(transactionStatus -> {
            employeeLockRepository.lockEmployee(managerId); // the manager's own event, a correction of the manager, ...
            managerLocked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(managerLocked.await(10, TimeUnit.SECONDS)).isTrue();
            // the new workday references the manager (manager_id): its foreign-key check must not wait for the manager's lock
            Future<Integer> start = executor.submit(() -> employee.send("START").andReturn().getResponse().getStatus());
            assertThat(start.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            release.countDown();
            managerHoldsTheLock.get(20, TimeUnit.SECONDS);
        }
    }

    // ── L3 ──

    @Test
    void aCollisionOverInconsistentRecordsIsReportedNotHiddenAsARepeat() throws Exception {
        clock.setTo("2026-10-20T04:00:00Z");
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.setTo("2026-10-20T05:00:00Z");
        employee.apply("END");
        // a leftover open work session on the ended day (inconsistent records)
        jdbcTemplate.update("INSERT INTO work_sessions (business_id, employee_id, workday_id, started_at, open_workday_id) VALUES (?, ?, ?::uuid, ?, ?::uuid)",
                businessId, employeeId, workdayId, java.sql.Timestamp.from(java.time.Instant.parse("2026-10-20T05:00:00Z")), workdayId);
        clock.setTo("2026-10-20T06:00:00Z");
        employee.send("START").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("TIME_RECORD_INCONSISTENT"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'time.event_failed' AND subject_id = ?", Integer.class,
                employeeId)).isEqualTo(1);
    }

    // ── M4 ──

    @Test
    void suspendingABusinessClosesItsOpenWorkdaysAndReactivatingReopensNothing() throws Exception {
        String superAdminEmail = TestData.uniqueEmail("locking.platform");
        UUID superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        clock.setTo("2026-10-20T04:30:00Z");
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.advance(Duration.ofHours(1));
        employee.apply("BREAK");
        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/status")).cookie(superAdminCookie)
                .content("{\"isSuspended\":true,\"reason\":\"Unpaid\"}")).andExpect(status().isOk());
        Map<String, Object> workday = jdbcTemplate.queryForMap("SELECT status::text AS status, active_employee_id, productive_seconds, break_seconds, "
                + "break_count FROM workdays WHERE id = ?::uuid", workdayId);
        assertThat(workday).containsEntry("status", "COMPLETED").containsEntry("active_employee_id", null).containsEntry("productive_seconds", 3600)
                .containsEntry("break_seconds", 900).containsEntry("break_count", 1);
        assertThat(jdbcTemplate.queryForMap("SELECT actor_id, reason FROM audit_logs WHERE action = 'workday.closed' AND subject_id = ?", employeeId))
                .containsEntry("actor_id", superAdminId).containsEntry("reason", "business_suspended");

        clock.advance(Duration.ofHours(2));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":false}"))
                .andExpect(status().isOk());
        assertThat((String) employee.stateValue("$.data.status")).isEqualTo("DAY_ENDED");
        assertThat(jdbcTemplate.queryForObject("SELECT productive_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId)).isEqualTo(3600);
    }

    // ── deactivation while on a break ──

    @Test
    void deactivatingSomebodyOnABreakClosesTheBreakAndTheDay() throws Exception {
        clock.setTo("2026-10-20T04:30:00Z");
        String workdayId = JsonPath.read(employee.apply("START"), "$.data.workdayId");
        clock.advance(Duration.ofHours(2));
        employee.apply("BREAK");
        clock.advance(Duration.ofMinutes(20));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForMap("SELECT status::text AS status, productive_seconds, break_seconds, break_count FROM workdays WHERE id = ?::uuid",
                workdayId)).containsEntry("status", "COMPLETED").containsEntry("productive_seconds", 7200).containsEntry("break_seconds", 1200)
                .containsEntry("break_count", 1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM break_sessions WHERE workday_id = ?::uuid AND ended_at IS NULL", Integer.class,
                workdayId)).isZero();
    }

    // ── L1 ──

    @Test
    void snapshotsAreReadInOneRepeatableReadTransaction() throws Exception {
        Object timeTrackingServiceTarget = AopTestUtils.getTargetObject(timeTrackingService); // not inlined: the generic result would pick getField(Class, ...)
        TransactionTemplate readTransaction = (TransactionTemplate) ReflectionTestUtils.getField(timeTrackingServiceTarget, "readTransaction");
        assertThat(readTransaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(readTransaction.isReadOnly()).isTrue();
        List<Callable<Method>> readers = List.of(
                () -> WorkdayHistoryService.class.getMethod("listOwnWorkdays", com.jurislpo.juristrack.security.CurrentUser.class, Map.class),
                () -> WorkdayHistoryService.class.getMethod("findWorkday", com.jurislpo.juristrack.security.CurrentUser.class, UUID.class),
                () -> TeamService.class.getMethod("listMembers", com.jurislpo.juristrack.security.CurrentUser.class, Map.class),
                () -> TeamService.class.getMethod("findMember", com.jurislpo.juristrack.security.CurrentUser.class, UUID.class));
        for (Callable<Method> reader : readers) {
            Method method = reader.call();
            assertThat(method.getAnnotation(Transactional.class).isolation()).as(method.getName()).isEqualTo(Isolation.REPEATABLE_READ);
        }
    }
}

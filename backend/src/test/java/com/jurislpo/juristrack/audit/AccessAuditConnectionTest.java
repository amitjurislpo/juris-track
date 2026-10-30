package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An access audit entry is written in the read's own transaction: many more parallel reads of other people's data than the pool has
 * connections all succeed quickly, and every one of them has its entry (a second connection per read would drain the pool, stall
 * every tenant and serve data without its entry).
 */
class AccessAuditConnectionTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired DataSource dataSource;

    @Test
    void moreParallelAuditedReadsThanPoolConnectionsNeitherStallNorLoseEntries() throws Exception {
        rateLimiter.clear();
        TestData testData = new TestData(jdbcTemplate);
        UUID businessId = testData.createBusiness("Pool Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("pool.owner");
        UUID ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("pool.employee"), ownerId);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        int parallelReads = poolSize * 3;

        ExecutorService executor = Executors.newFixedThreadPool(parallelReads);
        CountDownLatch startTogether = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        long startedAtNanos = System.nanoTime();
        try {
            for (int i = 0; i < parallelReads; i++) {
                answers.add(executor.submit(() -> {
                    startTogether.await();
                    return mockMvc.perform(get("/api/v1/employees/" + employeeId).cookie(ownerCookie)).andReturn().getResponse().getStatus();
                }));
            }
            startTogether.countDown();
            for (Future<Integer> answer : answers) assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAtNanos);
        assertThat(elapsed).as("no read waited for a second connection").isLessThan(Duration.ofSeconds(4));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'access.employee_viewed' AND actor_id = ? AND subject_id = ?",
                Integer.class, ownerId, employeeId)).as("one entry per read, none lost").isEqualTo(parallelReads);
    }
}

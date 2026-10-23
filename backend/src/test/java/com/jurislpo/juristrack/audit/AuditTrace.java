package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One traced request of a test: its own request id and IP address (sent as {@code X-Request-Id} and {@code X-Forwarded-For}), and the
 * audit entries written for it. A new IP address per test also keeps the in-memory rate limits and aggregation windows of different
 * tests apart.
 */
final class AuditTrace {

    final String requestId = "test-" + UUID.randomUUID();
    final String ipAddress;
    private final JdbcTemplate jdbcTemplate;

    AuditTrace(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, newIpAddress());
    }

    AuditTrace(JdbcTemplate jdbcTemplate, String ipAddress) {
        this.jdbcTemplate = jdbcTemplate;
        this.ipAddress = ipAddress;
    }

    /** A new IP address from 10.0.0.0/8. */
    static String newIpAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    /** The request with this trace's request id and IP address. */
    MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request) {
        return request.header("X-Request-Id", requestId).header("X-Forwarded-For", ipAddress);
    }

    /** Every entry of this request, oldest first. */
    List<Map<String, Object>> entries() {
        return jdbcTemplate.queryForList(SELECT + " WHERE request_id = ? ORDER BY created_at, action", requestId);
    }

    /** The one entry of this request with {@code action}; fails unless there is exactly one, with this request's IP address. */
    Map<String, Object> entry(String action) {
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(SELECT + " WHERE request_id = ? AND action = ?", requestId, action);
        assertThat(entries).as("entries %s of request %s", action, requestId).hasSize(1);
        assertThat(entries.get(0)).containsEntry("ip_address", ipAddress);
        return entries.get(0);
    }

    /** How many entries with {@code action} this request wrote. */
    int count(String action) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE request_id = ? AND action = ?", Integer.class, requestId, action);
    }

    static final String SELECT = "SELECT id, business_id, actor_id, actor_role, subject_id, action, category, outcome, source, entity_type, entity_id, "
            + "reason, \"before\"::text AS before_json, \"after\"::text AS after_json, ip_address, user_agent, request_id, http_method, request_path "
            + "FROM audit_logs";
}

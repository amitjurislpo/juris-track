package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.AuditLogService;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /api/audit-logs} and {@code /{auditLogId}}: the owner reads their business's log, the super admin every entry; filters,
 * sorting, paging, the full entry, and the CSV export with its own audit entry and its row cap.
 */
class AuditLogApiTest extends IntegrationTest {

    /** Every entry of the tests is dated inside this range; the entries written by signing in are not. */
    private static final String MARCH = "from=2026-03-01&to=2026-03-31";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    TestData testData;
    UUID businessId;
    UUID otherBusinessId;
    UUID ownerId;
    UUID employeeId;
    UUID superAdminId;
    Cookie ownerCookie;
    Cookie otherOwnerCookie;
    Cookie superAdminCookie;
    UUID startedEntryId;
    UUID breakEntryId;
    UUID refusedEntryId;
    UUID otherBusinessEntryId;
    UUID platformEntryId;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Audit Log Co " + UUID.randomUUID()); // Asia/Kolkata
        otherBusinessId = testData.createBusiness("Other Audit Log Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("auditlog.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", null);
        employeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("auditlog.employee"), ownerId, "Asha", "Rao", null);
        String otherOwnerEmail = TestData.uniqueEmail("auditlog.other");
        UUID otherOwnerId = testData.createUser(otherBusinessId, "ADMIN", otherOwnerEmail, null);
        String superAdminEmail = TestData.uniqueEmail("auditlog.platform");
        superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null, "Pat", "Platform", null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        otherOwnerCookie = SignIn.session(mockMvc, otherOwnerEmail).accessCookie();
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();

        // 2026-03-01T20:00Z is 2 March 01:30 in Asia/Kolkata; 2026-03-01T10:00Z is 1 March 15:30 there
        startedEntryId = insertEntry(businessId, employeeId, employeeId, "time.workday_started", "time", "SUCCESS", "STANDALONE", "2026-03-01T20:00:00Z",
                "req-alpha", "workday", "workday-1", "{\"status\": \"NOT_STARTED\"}", "{\"status\": \"WORKING\", \"at\": \"2026-03-01T20:00:00Z\"}", null);
        breakEntryId = insertEntry(businessId, ownerId, employeeId, "time.break_started", "time", "SUCCESS", "INTEGRATION", "2026-03-01T10:00:00Z",
                "req-beta", "workday", "workday-2", null, null, null);
        refusedEntryId = insertEntry(businessId, superAdminId, null, "employee.change_refused", "people", "DENIED", "STANDALONE", "2026-03-03T10:00:00Z",
                "req-gamma", "user", "=HYPERLINK(\"http://evil.test\")", null, "{\"code\": \"MANAGER_CYCLE\"}", "MANAGER_CYCLE");
        otherBusinessEntryId = insertEntry(otherBusinessId, otherOwnerId, null, "business.updated", "business", "SUCCESS", "STANDALONE",
                "2026-03-02T10:00:00Z", "req-other", "business", otherBusinessId.toString(), null, null, null);
        platformEntryId = insertEntry(null, null, null, "auth.sign_in_failed", "auth", "DENIED", "STANDALONE", "2026-03-02T10:00:00Z", "req-platform-"
                + UUID.randomUUID(), "session", null, null, "{\"reason\": \"unknown_email\"}", "unknown_email");
    }

    private UUID insertEntry(UUID entryBusinessId, UUID actorId, UUID subjectId, String action, String category, String outcome, String source,
            String createdAt, String requestId, String entityType, String entityId, String beforeJson, String afterJson, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO audit_logs (id, business_id, actor_id, actor_role, subject_id, action, category, outcome, source, created_at, request_id, "
                + "entity_type, entity_id, \"before\", \"after\", reason, ip_address, user_agent, http_method, request_path) "
                + "VALUES (?, ?, ?, (SELECT role::text FROM users WHERE id = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, '203.0.113.9', 'Test Agent/2.0', "
                + "'POST', '/api/test')", id, entryBusinessId, actorId, actorId, subjectId, action, category, outcome, source,
                Timestamp.from(Instant.parse(createdAt)), requestId, entityType, entityId, beforeJson, afterJson, reason);
        return id;
    }

    private ResultActions list(Cookie cookie, String query) throws Exception {
        return mockMvc.perform(get("/api/audit-logs?" + query).cookie(cookie));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE"})
    void onlyTheOwnerAndTheSuperAdminReadTheLog(String role) throws Exception {
        String email = TestData.uniqueEmail("auditlog." + role.toLowerCase());
        testData.createUser(businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/audit-logs").cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/audit-logs/" + startedEntryId).cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-logs?format=csv").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void theOwnerSeesOnlyTheirBusinessAndTheSuperAdminEverything() throws Exception {
        list(ownerCookie, MARCH).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows[*].id", containsInAnyOrder(startedEntryId.toString(), breakEntryId.toString(), refusedEntryId.toString())));
        list(otherOwnerCookie, MARCH).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].id").value(otherBusinessEntryId.toString()));
        // an owner's businessId is ignored: they never see another business
        list(ownerCookie, MARCH + "&businessId=" + otherBusinessId).andExpect(jsonPath("$.data.total").value(3));
        mockMvc.perform(get("/api/audit-logs/" + otherBusinessEntryId).cookie(ownerCookie)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/audit-logs/" + platformEntryId).cookie(ownerCookie)).andExpect(status().isNotFound());

        list(superAdminCookie, MARCH + "&businessId=" + businessId).andExpect(jsonPath("$.data.total").value(3));
        list(superAdminCookie, MARCH + "&businessId=" + otherBusinessId).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].businessName").value(startsWith("Other Audit Log Co")));
        list(superAdminCookie, "businessId=none&search=" + jdbcTemplate.queryForObject("SELECT request_id FROM audit_logs WHERE id = ?", String.class,
                platformEntryId)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].businessId").value(nullValue()));
        mockMvc.perform(get("/api/audit-logs/" + otherBusinessEntryId).cookie(superAdminCookie)).andExpect(status().isOk());
        mockMvc.perform(get("/api/audit-logs/" + UUID.randomUUID()).cookie(superAdminCookie)).andExpect(status().isNotFound());
    }

    @Test
    void rowsCarryTheEntryAndNamesResolvedWhenRead() throws Exception {
        list(ownerCookie, MARCH + "&search=req-alpha").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].id").value(startedEntryId.toString()))
                .andExpect(jsonPath("$.data.rows[0].createdAt").value("2026-03-01T20:00:00Z"))
                .andExpect(jsonPath("$.data.rows[0].actor.id").value(employeeId.toString()))
                .andExpect(jsonPath("$.data.rows[0].actor.name").value("Asha Rao"))
                .andExpect(jsonPath("$.data.rows[0].actor.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.rows[0].subject.name").value("Asha Rao"))
                .andExpect(jsonPath("$.data.rows[0].action").value("time.workday_started"))
                .andExpect(jsonPath("$.data.rows[0].category").value("time"))
                .andExpect(jsonPath("$.data.rows[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rows[0].entityType").value("workday"))
                .andExpect(jsonPath("$.data.rows[0].entityId").value("workday-1"))
                .andExpect(jsonPath("$.data.rows[0].source").value("STANDALONE"))
                .andExpect(jsonPath("$.data.rows[0].ipAddress").value("203.0.113.9"))
                .andExpect(jsonPath("$.data.rows[0].requestId").value("req-alpha"))
                .andExpect(jsonPath("$.data.rows[0].summary").value("changed: status, at"))
                .andExpect(jsonPath("$.data.rows[0].before").doesNotExist());
        jdbcTemplate.update("UPDATE users SET last_name = 'Menon' WHERE id = ?", employeeId);
        list(ownerCookie, MARCH + "&search=req-alpha").andExpect(jsonPath("$.data.rows[0].actor.name").value("Asha Menon"));
        // the platform super admin is outside the owner's business: role only, no name
        list(ownerCookie, MARCH + "&search=req-gamma").andExpect(jsonPath("$.data.rows[0].actor.name").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[0].actor.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.rows[0].subject").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[0].summary").value("MANAGER_CYCLE"));
        list(superAdminCookie, MARCH + "&search=req-gamma").andExpect(jsonPath("$.data.rows[0].actor.name").value("Pat Platform"));
    }

    @Test
    void theFullEntryHasBeforeAfterAndTheRequest() throws Exception {
        mockMvc.perform(get("/api/audit-logs/" + startedEntryId).cookie(ownerCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.before.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.after.status").value("WORKING"))
                .andExpect(jsonPath("$.data.userAgent").value("Test Agent/2.0"))
                .andExpect(jsonPath("$.data.httpMethod").value("POST"))
                .andExpect(jsonPath("$.data.requestPath").value("/api/test"))
                .andExpect(jsonPath("$.data.businessId").value(businessId.toString()))
                .andExpect(jsonPath("$.data.reason").value(nullValue()));
        mockMvc.perform(get("/api/audit-logs/not-an-id").cookie(ownerCookie)).andExpect(status().isBadRequest());
    }

    @Test
    void everyFilterNarrowsTheList() throws Exception {
        // dates are calendar dates in the business time zone for the owner, UTC for the super admin
        list(ownerCookie, "from=2026-03-02&to=2026-03-02").andExpect(jsonPath("$.data.rows[*].id", contains(startedEntryId.toString())));
        list(ownerCookie, "from=2026-03-01&to=2026-03-01").andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString())));
        list(superAdminCookie, "businessId=" + businessId + "&from=2026-03-01&to=2026-03-01")
                .andExpect(jsonPath("$.data.rows[*].id", contains(startedEntryId.toString(), breakEntryId.toString())));
        list(ownerCookie, "from=2026-03-03&to=2026-03-01").andExpect(jsonPath("$.data.total").value(3)); // reversed ends are swapped

        list(ownerCookie, MARCH + "&action=time.").andExpect(jsonPath("$.data.total").value(2));
        list(ownerCookie, MARCH + "&action=time.break_started").andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString())));
        list(ownerCookie, MARCH + "&category=people").andExpect(jsonPath("$.data.rows[*].id", contains(refusedEntryId.toString())));
        list(ownerCookie, MARCH + "&outcome=denied").andExpect(jsonPath("$.data.rows[*].id", contains(refusedEntryId.toString())));
        list(ownerCookie, MARCH + "&source=INTEGRATION").andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString())));
        list(ownerCookie, MARCH + "&actorId=" + ownerId).andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString())));
        list(ownerCookie, MARCH + "&subjectId=" + employeeId).andExpect(jsonPath("$.data.total").value(2));
        list(ownerCookie, MARCH + "&entityType=workday&entityId=workday-1").andExpect(jsonPath("$.data.rows[*].id", contains(startedEntryId.toString())));
        list(ownerCookie, MARCH + "&search=rao").andExpect(jsonPath("$.data.total").value(2));
        list(ownerCookie, MARCH + "&search=req-beta").andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString())));

        list(ownerCookie, "from=2026-13-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("from"));
        list(ownerCookie, "outcome=MAYBE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("outcome"));
        list(ownerCookie, "source=EMAIL").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("source"));
        list(ownerCookie, "category=nope").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("category"));
        list(ownerCookie, "actorId=someone").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("actorId"));
        list(ownerCookie, "action=DROP TABLE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("action"));
        list(ownerCookie, "sort=nope").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("sort"));
        list(ownerCookie, "format=xml").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("format"));
        list(superAdminCookie, "businessId=somewhere").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("businessId"));
    }

    @Test
    void newestFirstByDefaultAndSortedAndPaged() throws Exception {
        list(ownerCookie, MARCH).andExpect(jsonPath("$.data.rows[*].id", contains(refusedEntryId.toString(), startedEntryId.toString(), breakEntryId.toString())));
        list(ownerCookie, MARCH + "&sortDirection=asc")
                .andExpect(jsonPath("$.data.rows[*].id", contains(breakEntryId.toString(), startedEntryId.toString(), refusedEntryId.toString())));
        list(ownerCookie, MARCH + "&sort=action&sortDirection=asc").andExpect(jsonPath("$.data.rows[*].action",
                contains("employee.change_refused", "time.break_started", "time.workday_started")));
        list(ownerCookie, MARCH + "&sort=outcome&sortDirection=asc").andExpect(jsonPath("$.data.rows[0].outcome").value("DENIED"));
        list(superAdminCookie, MARCH + "&businessId=" + businessId + "&sort=actor&sortDirection=asc")
                .andExpect(jsonPath("$.data.rows[*].actor.name", contains("Asha Rao", "Olivia Owner", "Pat Platform")));
        list(ownerCookie, MARCH + "&pageSize=1&page=2").andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1)).andExpect(jsonPath("$.data.rows[*].id", contains(startedEntryId.toString())));
    }

    @Test
    void theCsvExportHasTheFilteredRowsAndIsAuditedItself() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        MockHttpServletResponse response = mockMvc.perform(trace.on(get("/api/audit-logs?format=csv&" + MARCH)).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"audit-log-")))
                .andReturn().getResponse();
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"Time (UTC)\",\"Business\",\"Actor\"");
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).contains("\"2026-03-03T10:00:00Z\"").contains("\"employee.change_refused\"").contains("\"DENIED\"")
                .contains("\"'=HYPERLINK(\"\"http://evil.test\"\")\"");
        assertThat(csv).contains("\"Asha Rao\"").contains("\"req-alpha\"");

        Map<String, Object> entry = trace.entry("audit.exported");
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("business_id", businessId).containsEntry("category", "audit")
                .containsEntry("outcome", "SUCCESS");
        assertThat((String) entry.get("after_json")).contains("\"from\": \"2026-03-01\"").contains("\"to\": \"2026-03-31\"").contains("\"rowCount\": 3");

        // the super admin's export is a platform entry
        AuditTrace platformTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(platformTrace.on(get("/api/audit-logs?format=csv&" + MARCH + "&businessId=" + otherBusinessId)).cookie(superAdminCookie))
                .andExpect(status().isOk());
        Map<String, Object> platformEntry = platformTrace.entry("audit.exported");
        assertThat(platformEntry.get("business_id")).isNull();
        assertThat((String) platformEntry.get("after_json")).contains("\"businessId\": \"" + otherBusinessId + "\"").contains("\"rowCount\": 1");
    }

    @Test
    void anExportOverTheCapIsRefusedAndNotAudited() throws Exception {
        UUID bigBusinessId = testData.createBusiness("Big Log Co " + UUID.randomUUID());
        String bigOwnerEmail = TestData.uniqueEmail("auditlog.big");
        testData.createUser(bigBusinessId, "ADMIN", bigOwnerEmail, null);
        Cookie bigOwnerCookie = SignIn.session(mockMvc, bigOwnerEmail).accessCookie();
        jdbcTemplate.update("INSERT INTO audit_logs (business_id, action, entity_type, category, outcome, source, created_at) "
                + "SELECT ?, CASE WHEN n = 1 THEN 'test.first' ELSE 'test.bulk' END, 'test', 'audit', 'SUCCESS', 'SYSTEM', "
                + "TIMESTAMPTZ '2026-03-10T00:00:00Z' + n * interval '1 second' "
                + "FROM generate_series(1, ?) AS n", bigBusinessId, AuditLogService.MAXIMUM_EXPORT_ROWS + 1);
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/audit-logs?format=csv&" + MARCH)).cookie(bigOwnerCookie)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"));
        assertThat(trace.entries()).isEmpty();
        list(bigOwnerCookie, MARCH + "&pageSize=100&page=101").andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(10_001))
                .andExpect(jsonPath("$.data.rows.length()").value(1));
        // exactly at the cap it works
        AuditTrace capTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(capTrace.on(get("/api/audit-logs?format=csv&from=2026-03-10&to=2026-03-10&action=test.bulk")).cookie(bigOwnerCookie))
                .andExpect(status().isOk());
        assertThat((String) capTrace.entry("audit.exported").get("after_json")).contains("\"rowCount\": 10000");
    }
}

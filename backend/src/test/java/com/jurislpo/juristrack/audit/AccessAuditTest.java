package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Reading somebody else's personal data is audited (who looked at whom, with the filters), once per request; reading one's own data,
 * polling the own time state and viewing the audit log are not.
 */
class AccessAuditTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID employeeId;
    Cookie ownerCookie;
    Cookie employeeCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Access Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("access.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", null);
        String employeeEmail = TestData.uniqueEmail("access.employee");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, ownerId, "Asha", "Rao", null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        employeeCookie = SignIn.session(mockMvc, employeeEmail).accessCookie();
    }

    @Test
    void theOwnerListingPeopleIsAuditedOncePerRequestWithTheFilters() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/v1/employees?status=all&search=Rao&sort=email&page=1")).cookie(ownerCookie)).andExpect(status().isOk());
        Map<String, Object> entry = trace.entry("access.employee_list_viewed");
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("business_id", businessId).containsEntry("outcome", "SUCCESS")
                .containsEntry("category", "access").containsEntry("http_method", "GET").containsEntry("request_path", "/api/v1/employees");
        assertThat(entry.get("subject_id")).isNull();
        assertThat((String) entry.get("after_json")).contains("\"status\": \"all\"").contains("\"search\": \"Rao\"").contains("\"total\": 1");
        AuditTrace secondTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(secondTrace.on(get("/api/v1/employees")).cookie(ownerCookie)).andExpect(status().isOk());
        assertThat(secondTrace.count("access.employee_list_viewed")).isEqualTo(1);
    }

    @Test
    void theOwnerOpeningAnotherPersonIsAuditedButNotTheirOwnRecord() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/v1/employees/" + employeeId)).cookie(ownerCookie)).andExpect(status().isOk());
        assertThat(trace.entry("access.employee_viewed")).containsEntry("actor_id", ownerId).containsEntry("subject_id", employeeId)
                .containsEntry("entity_type", "user").containsEntry("entity_id", employeeId.toString());
        AuditTrace ownTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(ownTrace.on(get("/api/v1/employees/" + ownerId)).cookie(ownerCookie)).andExpect(status().isOk());
        assertThat(ownTrace.entries()).isEmpty();
        // another business's person is 404 and nothing was read: the only trace is the probe itself (security.out_of_scope_access, once per user and window)
        UUID strangerId = testData.createUser(testData.createBusiness("Elsewhere " + UUID.randomUUID()), "EMPLOYEE", TestData.uniqueEmail("stranger"), null);
        AuditTrace strangerTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(strangerTrace.on(get("/api/v1/employees/" + strangerId)).cookie(ownerCookie)).andExpect(status().isNotFound());
        assertThat(strangerTrace.entries()).singleElement().satisfies(probe -> assertThat(probe).containsEntry("action", "security.out_of_scope_access")
                .containsEntry("actor_id", ownerId).containsEntry("business_id", businessId).containsEntry("entity_type", "user"));
        assertThat(strangerTrace.count("access.employee_viewed")).isZero();
    }

    @Test
    void theSuperAdminReadingBusinessesIsAudited() throws Exception {
        String superAdminEmail = TestData.uniqueEmail("access.platform");
        UUID superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        AuditTrace listTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(listTrace.on(get("/api/v1/businesses?status=active")).cookie(superAdminCookie)).andExpect(status().isOk());
        Map<String, Object> listEntry = listTrace.entry("access.business_list_viewed");
        assertThat(listEntry).containsEntry("actor_id", superAdminId).containsEntry("actor_role", "SUPER_ADMIN");
        assertThat(listEntry.get("business_id")).isNull();
        assertThat((String) listEntry.get("after_json")).contains("\"status\": \"active\"");

        AuditTrace detailTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(detailTrace.on(get("/api/v1/businesses/" + businessId)).cookie(superAdminCookie)).andExpect(status().isOk());
        // in the business's own log, so its owner sees that the platform looked
        assertThat(detailTrace.entry("access.business_viewed")).containsEntry("business_id", businessId).containsEntry("subject_id", ownerId)
                .containsEntry("entity_id", businessId.toString());
    }

    @Test
    void readingOwnDataAndPollingIsNotAudited() throws Exception {
        String workdayId = JsonPath.read(mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(employeeCookie).content("{\"type\":\"START\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.workdayId");
        List<String> ownReads = List.of("/api/v1/me", "/api/v1/me/time", "/api/v1/me/time", "/api/v1/workdays/mine", "/api/v1/workdays/mine?range=week",
                "/api/v1/workdays/" + workdayId);
        for (String path : ownReads) {
            AuditTrace trace = new AuditTrace(jdbcTemplate);
            mockMvc.perform(trace.on(get(path)).cookie(employeeCookie)).andExpect(status().isOk());
            assertThat(trace.entries()).as(path).isEmpty();
        }
        for (String path : List.of("/api/v1/me", "/api/v1/business", "/api/v1/audit-logs")) {
            AuditTrace trace = new AuditTrace(jdbcTemplate);
            mockMvc.perform(trace.on(get(path)).cookie(ownerCookie)).andExpect(status().isOk());
            assertThat(trace.entries()).as(path).isEmpty();
        }
        String auditLogId = jdbcTemplate.queryForObject("SELECT id::text FROM audit_logs WHERE business_id = ? ORDER BY created_at DESC LIMIT 1", String.class,
                businessId);
        AuditTrace detailTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(detailTrace.on(get("/api/v1/audit-logs/" + auditLogId)).cookie(ownerCookie)).andExpect(status().isOk());
        assertThat(detailTrace.entries()).isEmpty();
    }

    /** One read of somebody else's time data and the one access entry it must write. */
    private record AccessRead(String path, String accept, String action, UUID subjectId, String entityType, String entityId) {}

    @Test
    void aManagerOrTheOwnerReadingSomebodysTimeWritesExactlyOneAccessEntryPerRequest() throws Exception {
        String managerEmail = TestData.uniqueEmail("access.manager");
        UUID managerId = testData.createUser(businessId, "MANAGER", managerEmail, ownerId, "Mona", "Manager", null);
        String reportEmail = TestData.uniqueEmail("access.report");
        UUID reportId = testData.createUser(businessId, "EMPLOYEE", reportEmail, managerId, "Ravi", "Report", null);
        Cookie managerCookie = SignIn.session(mockMvc, managerEmail).accessCookie();
        Cookie reportCookie = SignIn.session(mockMvc, reportEmail).accessCookie();
        String workdayId = JsonPath.read(mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(reportCookie).content("{\"type\":\"START\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.data.workdayId");

        List<AccessRead> reads = List.of(
                new AccessRead("/api/v1/team/members/" + reportId, null, "access.team_member_viewed", reportId, "user", reportId.toString()),
                new AccessRead("/api/v1/team/members/" + reportId + "/workdays?range=week", null, "access.team_member_workdays_viewed", reportId, "user",
                        reportId.toString()),
                new AccessRead("/api/v1/workdays/" + workdayId, null, "access.workday_viewed", reportId, "workday", workdayId),
                new AccessRead("/api/v1/reports/workdays", null, "reports.viewed", null, "report", "workdays"),
                new AccessRead("/api/v1/reports/workdays?employeeId=" + reportId, null, "reports.viewed", reportId, "report", "workdays"),
                new AccessRead("/api/v1/reports/summaries?groupBy=date", null, "reports.viewed", null, "report", "summaries"),
                new AccessRead("/api/v1/reports/workdays", "text/csv", "reports.exported", null, "report", "workdays"));
        for (Map.Entry<UUID, Cookie> viewer : Map.of(managerId, managerCookie, ownerId, ownerCookie).entrySet()) {
            for (AccessRead read : reads) {
                AuditTrace trace = new AuditTrace(jdbcTemplate);
                var request = trace.on(get(read.path())).cookie(viewer.getValue());
                if (read.accept() != null) request.header("Accept", read.accept());
                mockMvc.perform(request).andExpect(status().isOk());
                assertThat(trace.entries()).as(read.path()).hasSize(1);
                Map<String, Object> entry = trace.entry(read.action());
                assertThat(entry).as(read.path()).containsEntry("actor_id", viewer.getKey()).containsEntry("subject_id", read.subjectId())
                        .containsEntry("business_id", businessId).containsEntry("category", "access").containsEntry("outcome", "SUCCESS")
                        .containsEntry("entity_type", read.entityType()).containsEntry("entity_id", read.entityId())
                        .containsEntry("http_method", "GET").containsEntry("source", "STANDALONE");
            }
        }
        // the live board refreshes every few seconds and is not recorded
        AuditTrace boardTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(boardTrace.on(get("/api/v1/team/members")).cookie(managerCookie)).andExpect(status().isOk());
        assertThat(boardTrace.entries()).isEmpty();
    }
}
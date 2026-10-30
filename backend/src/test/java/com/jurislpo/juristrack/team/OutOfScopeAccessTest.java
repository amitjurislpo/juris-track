package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.RateLimiter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Asking for somebody outside one's scope (or another business's record) stays a plain 404, but leaves a trace: one
 * {@code security.out_of_scope_access} entry per actor and window, whatever the endpoint (team, workday, correction, report).
 */
class OutOfScopeAccessTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    TeamFixture team;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        auditAggregation.clear();
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
    }

    private int outOfScopeEntries(UUID actorId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'security.out_of_scope_access' AND actor_id = ?", Integer.class,
                actorId);
    }

    @Test
    void probingSomebodyOutsideTheScopeIsAnsweredNotFoundAndRecordedOncePerWindow() throws Exception {
        String workdayOfC = team.applyEvent(team.employeeOfCId, "START");
        // manager B sees only their own report: everybody else is 404
        team.read(team.managerBId, "/api/v1/team/members/" + team.employeeOfCId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT business_id, actor_role, subject_id, category, outcome, entity_type, reason, request_path, "
                + "\"after\"->>'recordType' AS record_type FROM audit_logs WHERE action = 'security.out_of_scope_access' AND actor_id = ?", team.managerBId);
        assertThat(entry).containsEntry("business_id", team.businessId).containsEntry("actor_role", "MANAGER").containsEntry("category", "security")
                .containsEntry("outcome", "DENIED").containsEntry("entity_type", "user").containsEntry("record_type", "user")
                .containsEntry("reason", "NOT_FOUND").containsEntry("request_path", "/api/v1/team/members/" + team.employeeOfCId);
        assertThat(entry.get("subject_id")).as("the probed id is not trusted as a subject").isNull();

        // further probes in the same window: still 404, no new entry
        team.read(team.managerBId, "/api/v1/workdays/" + workdayOfC).andExpect(status().isNotFound());
        team.read(team.managerBId, "/api/v1/team/members/" + UUID.randomUUID() + "/workdays").andExpect(status().isNotFound());
        team.read(team.managerBId, "/api/v1/reports/workdays?employeeId=" + team.employeeOfCId).andExpect(status().isNotFound());
        team.putJson(team.managerBId, "/api/v1/workdays/" + workdayOfC + "/status", "{\"status\":\"DAY_ENDED\",\"reason\":\"Probing\"}")
                .andExpect(status().isNotFound());
        assertThat(outOfScopeEntries(team.managerBId)).isEqualTo(1);
    }

    @Test
    void eachEndpointRecordsItsFirstProbe() throws Exception {
        String workdayOfC = team.applyEvent(team.employeeOfCId, "START");
        UUID otherBusinessEmployeeId = new TestData(jdbcTemplate).createUser(new TestData(jdbcTemplate).createBusiness("Other " + UUID.randomUUID()),
                "EMPLOYEE", TestData.uniqueEmail("elsewhere"), null);
        String[] probes = {"/api/v1/workdays/" + workdayOfC, "/api/v1/reports/summaries?employeeId=" + otherBusinessEmployeeId,
                "/api/v1/team/members/" + otherBusinessEmployeeId + "/workdays"};
        for (String probe : probes) {
            auditAggregation.clear();
            int before = outOfScopeEntries(team.managerAId);
            team.read(team.managerAId, probe).andExpect(status().isNotFound());
            assertThat(outOfScopeEntries(team.managerAId)).as(probe).isEqualTo(before + 1);
        }
        auditAggregation.clear();
        team.patchJson(team.managerAId, "/api/v1/workdays/" + workdayOfC + "/work-sessions/" + UUID.randomUUID(),
                "{\"startedAt\":\"2026-01-01T00:00:00Z\",\"reason\":\"Probing\"}").andExpect(status().isNotFound());
        assertThat(outOfScopeEntries(team.managerAId)).isEqualTo(probes.length + 1);
        // reading inside the scope records no refusal
        auditAggregation.clear();
        team.read(team.managerAId, "/api/v1/team/members/" + team.employeeId).andExpect(status().isOk());
        assertThat(outOfScopeEntries(team.managerAId)).isEqualTo(probes.length + 1);
    }
}

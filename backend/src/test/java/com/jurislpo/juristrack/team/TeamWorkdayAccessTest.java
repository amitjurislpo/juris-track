package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
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
 * One person inside the caller's scope ("manager A cannot view an employee of team B", "employees can only view themselves" of the
 * old verify-flows checks): {@code GET /api/team/members/{employeeId}}, {@code .../workdays} and {@code GET /api/workdays/{workdayId}}
 * of somebody else. A manager opens their direct and indirect reports, the owner anybody of the business; everything else (also the
 * caller themselves on the Team endpoints) is 404. Every such read is audited as access.
 */
class TeamWorkdayAccessTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;

    @BeforeEach
    void setUp() {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        clock.setTo("2026-10-05T03:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private int accessEntries(String action, UUID actorId, UUID subjectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND actor_id = ? AND subject_id = ?", Integer.class,
                action, actorId, subjectId);
    }

    @Test
    void aManagerOpensADirectOrIndirectReportsLiveState() throws Exception {
        String workdayId = team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofMinutes(45));
        team.applyEvent(team.employeeId, "BREAK");
        clock.advance(Duration.ofMinutes(5));
        String answer = team.read(team.managerAId, "/api/team/members/" + team.employeeId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.data.employee.id")).isEqualTo(team.employeeId.toString());
        assertThat((String) JsonPath.read(answer, "$.data.employee.managerName")).isEqualTo("Bala Bose");
        assertThat((String) JsonPath.read(answer, "$.data.status")).isEqualTo("ON_BREAK");
        assertThat((String) JsonPath.read(answer, "$.data.today")).isEqualTo("2026-10-05");
        assertThat((String) JsonPath.read(answer, "$.data.serverTime")).isEqualTo("2026-10-05T04:20:00Z");
        assertThat((String) JsonPath.read(answer, "$.data.workday.id")).isEqualTo(workdayId);
        assertThat((Integer) JsonPath.read(answer, "$.data.workday.productiveSeconds")).isEqualTo(2700);
        assertThat((Integer) JsonPath.read(answer, "$.data.workday.breakSeconds")).isEqualTo(300);
        List<String> segmentTypes = JsonPath.read(answer, "$.data.workday.segments[*].type");
        assertThat(segmentTypes).containsExactly("WORK", "BREAK");
        assertThat(accessEntries("access.team_member_viewed", team.managerAId, team.employeeId)).isEqualTo(1);

        team.read(team.managerBId, "/api/team/members/" + team.employeeOfCId).andExpect(status().isNotFound());
        team.read(team.managerBId, "/api/team/members/" + team.employeeOfAId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Employee not found."));
        String directManagerView = team.read(team.managerBId, "/api/team/members/" + team.employeeId).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat((String) JsonPath.read(directManagerView, "$.data.status")).isEqualTo("ON_BREAK");
    }

    @Test
    void outOfScopeUnknownAndOwnIdsAre404() throws Exception {
        for (UUID notVisibleId : List.of(team.ownerId, team.managerCId, team.employeeOfCId, team.managerAId, UUID.randomUUID())) {
            team.read(team.managerAId, "/api/team/members/" + notVisibleId).andExpect(status().isNotFound());
            team.read(team.managerAId, "/api/team/members/" + notVisibleId + "/workdays").andExpect(status().isNotFound());
        }
        team.read(team.ownerId, "/api/team/members/" + team.ownerId).andExpect(status().isNotFound());
        team.read(team.ownerId, "/api/team/members/not-a-uuid").andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id = ? AND action LIKE 'access.%'", Integer.class,
                team.managerAId)).isZero();
    }

    @Test
    void theOwnerOpensAnybodyOfTheBusinessIncludingADeactivatedPerson() throws Exception {
        team.applyEvent(team.employeeOfCId, "START");
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", team.employeeOfAId);
        for (UUID personId : List.of(team.managerAId, team.managerBId, team.employeeId, team.employeeOfAId, team.managerCId, team.employeeOfCId)) {
            team.read(team.ownerId, "/api/team/members/" + personId).andExpect(status().isOk());
        }
        team.read(team.ownerId, "/api/team/members/" + team.employeeOfCId).andExpect(jsonPath("$.data.status").value("WORKING"));
        team.read(team.ownerId, "/api/team/members/" + team.employeeOfAId).andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.workday").isEmpty()).andExpect(jsonPath("$.data.employee.isActive").value(false));
    }

    @Test
    void aReportsHistoryHasTheShapeOfMyHistoryAndIsAuditedWithItsRange() throws Exception {
        team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofHours(8));
        team.applyEvent(team.employeeId, "END");
        clock.setTo("2026-10-06T03:30:00Z");
        team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofHours(1));

        String answer = team.read(team.managerAId, "/api/team/members/" + team.employeeId + "/workdays?range=week").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.data.range")).isEqualTo("week");
        assertThat((String) JsonPath.read(answer, "$.data.from")).isEqualTo("2026-10-05");
        assertThat((String) JsonPath.read(answer, "$.data.to")).isEqualTo("2026-10-06");
        assertThat((Integer) JsonPath.read(answer, "$.data.total")).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(answer, "$.data.rows[*].date")).containsExactly("2026-10-06", "2026-10-05");
        assertThat((Integer) JsonPath.read(answer, "$.data.totals.productiveSeconds")).isEqualTo(9 * 3600);
        assertThat((List<Object>) JsonPath.read(answer, "$.data.dailySeries")).hasSize(2);
        Map<String, Object> after = JsonPath.read(jdbcTemplate.queryForObject(
                "SELECT after::text FROM audit_logs WHERE action = 'access.team_member_workdays_viewed' AND actor_id = ? AND subject_id = ?",
                String.class, team.managerAId, team.employeeId), "$");
        assertThat(after).containsEntry("range", "week").containsEntry("from", "2026-10-05").containsEntry("to", "2026-10-06");

        team.read(team.ownerId, "/api/team/members/" + team.employeeId + "/workdays?range=custom&from=2026-10-05&to=2026-10-05&sort=productiveSeconds")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        team.read(team.ownerId, "/api/team/members/" + team.employeeId + "/workdays?range=decade").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("range"));
    }

    @Test
    void aManagerOrTheOwnerReadsAWorkdayInTheirScopeAndOthersGet404() throws Exception {
        String workdayId = team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofHours(1));
        String path = "/api/workdays/" + workdayId;
        team.read(team.managerBId, path).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(workdayId))
                .andExpect(jsonPath("$.data.productiveSeconds").value(3600));
        team.read(team.managerAId, path).andExpect(status().isOk());
        team.read(team.ownerId, path).andExpect(status().isOk());
        team.read(team.managerCId, path).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Workday not found."));
        team.read(team.employeeOfAId, path).andExpect(status().isNotFound());
        assertThat(accessEntries("access.workday_viewed", team.managerBId, team.employeeId)).isEqualTo(1);
        assertThat(accessEntries("access.workday_viewed", team.ownerId, team.employeeId)).isEqualTo(1);
        assertThat(accessEntries("access.workday_viewed", team.managerCId, team.employeeId)).isZero();

        team.read(team.employeeId, path).andExpect(status().isOk());
        assertThat(accessEntries("access.workday_viewed", team.employeeId, team.employeeId)).as("reading one's own is not access").isZero();

        TeamFixture otherBusiness = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        otherBusiness.read(otherBusiness.ownerId, path).andExpect(status().isNotFound());
    }

    @Test
    void aManagerFollowsTheCurrentReportingLine() throws Exception {
        String workdayId = team.applyEvent(team.employeeId, "START");
        // the employee moves to manager C's branch: manager A and B lose sight of them, manager C gains it (including old workdays)
        jdbcTemplate.update("UPDATE users SET manager_id = ? WHERE id = ?", team.managerCId, team.employeeId);
        team.read(team.managerBId, "/api/workdays/" + workdayId).andExpect(status().isNotFound());
        team.read(team.managerAId, "/api/team/members/" + team.employeeId).andExpect(status().isNotFound());
        team.read(team.managerCId, "/api/workdays/" + workdayId).andExpect(status().isOk());
        team.read(team.managerCId, "/api/team/members/" + team.employeeId).andExpect(status().isOk());
    }
}

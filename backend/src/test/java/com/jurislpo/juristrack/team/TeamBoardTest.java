package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/team/members}: the live board ("Authorization scope" of the old verify-flows checks: manager A sees only their own
 * people). Scope direct / indirect / all over a three-level reporting line, roles, today's live status (a night shift from yesterday
 * is WORKING), metrics, status filter, search, sorting and paging, deactivated people and tenant isolation.
 */
class TeamBoardTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    @Autowired EntityManagerFactory entityManagerFactory;
    TeamFixture team;

    @BeforeEach
    void setUp() {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        // Monday 5 October 2026, 09:00 in Kolkata
        clock.setTo("2026-10-05T03:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private String board(UUID viewerId, String query) throws Exception {
        return team.read(viewerId, "/api/team/members" + (query.isEmpty() ? "" : "?" + query)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    private List<String> memberIds(UUID viewerId, String query) throws Exception {
        return JsonPath.read(board(viewerId, query), "$.data.rows[*].employee.id");
    }

    private static List<String> idsOf(UUID... userIds) {
        return java.util.Arrays.stream(userIds).map(UUID::toString).toList();
    }

    @Test
    void aManagerSeesDirectReportsByDefaultAndOneLevelMoreWithIndirect() throws Exception {
        assertThat(memberIds(team.managerAId, "")).containsExactlyInAnyOrderElementsOf(idsOf(team.managerBId, team.employeeOfAId));
        assertThat(memberIds(team.managerAId, "scope=direct")).containsExactlyInAnyOrderElementsOf(idsOf(team.managerBId, team.employeeOfAId));
        assertThat(memberIds(team.managerAId, "scope=indirect"))
                .containsExactlyInAnyOrderElementsOf(idsOf(team.managerBId, team.employeeOfAId, team.employeeId));
        assertThat(memberIds(team.managerBId, "scope=indirect")).containsExactlyElementsOf(idsOf(team.employeeId));
        String answer = board(team.managerAId, "scope=INDIRECT");
        assertThat((String) JsonPath.read(answer, "$.data.scope")).isEqualTo("indirect");
        assertThat((Integer) JsonPath.read(answer, "$.data.total")).isEqualTo(3);
    }

    @Test
    void theOwnerSeesEverybodyButThemselvesByDefaultOrTheirOwnReportingLine() throws Exception {
        String answer = board(team.ownerId, "");
        assertThat((String) JsonPath.read(answer, "$.data.scope")).isEqualTo("all");
        List<String> everybody = JsonPath.read(answer, "$.data.rows[*].employee.id");
        assertThat(everybody).containsExactlyInAnyOrderElementsOf(idsOf(team.managerAId, team.managerBId, team.employeeId, team.employeeOfAId,
                team.managerCId, team.employeeOfCId));
        assertThat(memberIds(team.ownerId, "scope=direct")).containsExactlyInAnyOrderElementsOf(idsOf(team.managerAId, team.managerCId));
        assertThat(memberIds(team.ownerId, "scope=indirect")).containsExactlyInAnyOrderElementsOf(idsOf(team.managerAId, team.managerCId,
                team.managerBId, team.employeeOfAId, team.employeeOfCId));
    }

    @Test
    void aManagerOfAnotherBranchSeesNoneOfThisBranchAndAManagerWithoutReportsSeesNobody() throws Exception {
        assertThat(memberIds(team.managerCId, "scope=indirect")).containsExactlyElementsOf(idsOf(team.employeeOfCId));
        UUID lonelyManagerId = new TestData(jdbcTemplate).createUser(team.businessId, "MANAGER", TestData.uniqueEmail("lonely"), team.ownerId);
        rateLimiter.clear();
        Cookie cookie = SignIn.session(mockMvc, emailOfNewUser(lonelyManagerId)).accessCookie();
        mockMvc.perform(get("/api/team/members?scope=indirect").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.rows").isEmpty())
                .andExpect(jsonPath("$.data.metrics.total").value(0)).andExpect(jsonPath("$.data.metrics.averageProductiveSeconds").value(0));
    }

    private String emailOfNewUser(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void employeesAndTheSuperAdminAreRefusedAndSignedOutIs401() throws Exception {
        team.read(team.employeeId, "/api/team/members").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        team.read(team.employeeId, "/api/team/members/" + team.employeeOfAId).andExpect(status().isForbidden());
        team.read(team.employeeId, "/api/team/members/" + team.employeeOfAId + "/workdays").andExpect(status().isForbidden());
        String email = TestData.uniqueEmail("team.platform");
        new TestData(jdbcTemplate).createUser(null, "SUPER_ADMIN", email, null);
        rateLimiter.clear();
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/team/members").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/team/members/" + team.employeeId).cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/team/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownParameterValuesAre400() throws Exception {
        team.read(team.managerAId, "/api/team/members?scope=all").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scope")).andExpect(jsonPath("$.detail").value("The scope is direct or indirect."));
        team.read(team.ownerId, "/api/team/members?scope=everyone").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("scope"));
        team.read(team.managerAId, "/api/team/members?status=LUNCH").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        team.read(team.managerAId, "/api/team/members?sort=salary").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
        team.read(team.managerAId, "/api/team/members?pageSize=0").andExpect(status().isBadRequest());
    }

    @Test
    void rowsShowTodaysLiveStateAndTheMetricsAddUp() throws Exception {
        team.applyEvent(team.employeeId, "START");
        team.applyEvent(team.employeeOfAId, "START");
        team.applyEvent(team.managerBId, "START");
        clock.advance(Duration.ofHours(1));
        team.applyEvent(team.employeeOfAId, "BREAK");
        team.applyEvent(team.managerBId, "END");
        clock.advance(Duration.ofMinutes(30));

        String answer = board(team.managerAId, "scope=indirect");
        assertThat((String) JsonPath.read(answer, "$.data.serverTime")).isEqualTo("2026-10-05T05:00:00Z");
        assertThat((String) JsonPath.read(answer, "$.data.today")).isEqualTo("2026-10-05");
        assertThat((String) JsonPath.read(answer, "$.data.businessTimeZone")).isEqualTo("Asia/Kolkata");
        Map<String, Object> working = row(answer, team.employeeId);
        assertThat(working.get("status")).isEqualTo("WORKING");
        assertThat(working.get("productiveSeconds")).isEqualTo(5400);
        assertThat(working.get("closedProductiveSeconds")).isEqualTo(0);
        assertThat(working.get("activeWorkStartedAt")).isEqualTo("2026-10-05T03:30:00Z");
        assertThat(working.get("activeBreakStartedAt")).isNull();
        assertThat(working.get("startedAt")).isEqualTo("2026-10-05T03:30:00Z");
        assertThat(working.get("date")).isEqualTo("2026-10-05");
        assertThat(working.get("isStale")).isEqualTo(false);
        assertThat(working.get("lastActivity")).isEqualTo(Map.of("type", "STARTED_WORK", "at", "2026-10-05T03:30:00Z"));
        Map<String, Object> onBreak = row(answer, team.employeeOfAId);
        assertThat(onBreak.get("status")).isEqualTo("ON_BREAK");
        assertThat(onBreak.get("productiveSeconds")).isEqualTo(3600);
        assertThat(onBreak.get("closedProductiveSeconds")).isEqualTo(3600);
        assertThat(onBreak.get("breakSeconds")).isEqualTo(1800);
        assertThat(onBreak.get("activeBreakStartedAt")).isEqualTo("2026-10-05T04:30:00Z");
        assertThat(onBreak.get("breakCount")).isEqualTo(1);
        Map<String, Object> runningBreak = (Map<String, Object>) onBreak.get("lastBreak");
        assertThat(runningBreak).containsEntry("startedAt", "2026-10-05T04:30:00Z").containsEntry("endedAt", null);
        assertThat(working).containsEntry("lastBreak", null);
        Map<String, Object> ended = row(answer, team.managerBId);
        assertThat(ended.get("status")).isEqualTo("DAY_ENDED");
        assertThat(ended.get("productiveSeconds")).isEqualTo(3600);
        assertThat(ended.get("endedAt")).isEqualTo("2026-10-05T04:30:00Z");

        Map<String, Object> metrics = JsonPath.read(answer, "$.data.metrics");
        assertThat(metrics).containsEntry("total", 3).containsEntry("working", 1).containsEntry("onBreak", 1).containsEntry("notStarted", 0)
                .containsEntry("dayEnded", 1).containsEntry("stale", 0).containsEntry("totalProductiveSeconds", 12600)
                .containsEntry("totalBreakSeconds", 1800).containsEntry("averageProductiveSeconds", 4200);

        String direct = board(team.managerAId, "");
        assertThat(row(direct, team.employeeOfAId).get("status")).isEqualTo("ON_BREAK");
        Map<String, Object> directMetrics = JsonPath.read(direct, "$.data.metrics");
        assertThat(directMetrics).containsEntry("total", 2).containsEntry("onBreak", 1).containsEntry("dayEnded", 1);

        String notStarted = board(team.managerCId, "");
        assertThat(row(notStarted, team.employeeOfCId)).containsEntry("status", "NOT_STARTED").containsEntry("workdayId", null)
                .containsEntry("productiveSeconds", 0).containsEntry("lastActivity", null).containsEntry("lastBreak", null).containsEntry("startedAt", null);
    }

    private static Map<String, Object> row(String answer, UUID employeeId) {
        List<Map<String, Object>> rows = JsonPath.read(answer, "$.data.rows[?(@.employee.id == '" + employeeId + "')]");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void aNightShiftFromYesterdayCountsAsWorkingToday() throws Exception {
        // Sunday 4 October, 22:00 in Kolkata
        clock.setTo("2026-10-04T16:30:00Z");
        String workdayId = team.applyEvent(team.employeeId, "START");
        // Monday 5 October, 02:00 in Kolkata
        clock.setTo("2026-10-04T20:30:00Z");
        String answer = board(team.managerBId, "");
        assertThat((String) JsonPath.read(answer, "$.data.today")).isEqualTo("2026-10-05");
        Map<String, Object> nightShift = row(answer, team.employeeId);
        assertThat(nightShift).containsEntry("status", "WORKING").containsEntry("workdayId", workdayId).containsEntry("date", "2026-10-04")
                .containsEntry("productiveSeconds", 14400);
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.working")).isEqualTo(1);
        assertThat(memberIds(team.managerBId, "status=working")).containsExactlyElementsOf(idsOf(team.employeeId));

        // a day ended yesterday is not today's state
        team.applyEvent(team.employeeId, "END");
        assertThat(row(board(team.managerBId, ""), team.employeeId)).containsEntry("status", "NOT_STARTED");
    }

    @Test
    void anOpenWorkdayLongerThanTheStaleHoursIsFlagged() throws Exception {
        team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofHours(15));
        String answer = board(team.managerBId, "");
        assertThat(row(answer, team.employeeId)).containsEntry("isStale", true).containsEntry("status", "WORKING");
        assertThat((Integer) JsonPath.read(answer, "$.data.metrics.stale")).isEqualTo(1);
    }

    @Test
    void statusFilterSearchSortingAndPaging() throws Exception {
        team.applyEvent(team.employeeId, "START");
        clock.advance(Duration.ofMinutes(10));
        team.applyEvent(team.employeeOfAId, "START");
        clock.advance(Duration.ofMinutes(50));

        assertThat(memberIds(team.ownerId, "status=WORKING")).containsExactlyInAnyOrderElementsOf(idsOf(team.employeeId, team.employeeOfAId));
        assertThat(memberIds(team.ownerId, "status=not_started")).hasSize(4);
        assertThat(memberIds(team.ownerId, "status=all")).hasSize(6);
        String filtered = board(team.ownerId, "status=WORKING");
        assertThat((Integer) JsonPath.read(filtered, "$.data.metrics.total")).as("metrics ignore the filters").isEqualTo(6);

        assertThat(memberIds(team.ownerId, "search=emp-7")).containsExactlyElementsOf(idsOf(team.employeeId));
        assertThat(memberIds(team.ownerId, "search=" + team.emailOf(team.employeeOfCId).substring(0, 12)))
                .containsExactlyElementsOf(idsOf(team.employeeOfCId));
        assertThat(memberIds(team.ownerId, "search=chitra rao")).containsExactlyElementsOf(idsOf(team.managerCId));
        assertThat(memberIds(team.managerAId, "search=chitra")).as("search stays inside the scope").isEmpty();

        // name ascending: Arun, Bala, Chitra, Dev, Esha, Mira
        assertThat(memberIds(team.ownerId, "")).containsExactlyElementsOf(idsOf(team.employeeOfAId, team.managerBId, team.managerCId,
                team.employeeOfCId, team.employeeId, team.managerAId));
        assertThat(memberIds(team.ownerId, "sort=name&sortDirection=desc").get(0)).isEqualTo(team.managerAId.toString());
        assertThat(memberIds(team.ownerId, "sort=productiveSeconds&sortDirection=desc").subList(0, 2))
                .containsExactlyElementsOf(idsOf(team.employeeId, team.employeeOfAId));
        assertThat(memberIds(team.ownerId, "sort=status").subList(0, 2)).containsExactlyElementsOf(idsOf(team.employeeOfAId, team.employeeId));
        assertThat(memberIds(team.ownerId, "sort=lastActivity&sortDirection=desc").subList(0, 2))
                .containsExactlyElementsOf(idsOf(team.employeeOfAId, team.employeeId));

        String secondPage = board(team.ownerId, "pageSize=4&page=2");
        assertThat((Integer) JsonPath.read(secondPage, "$.data.total")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(secondPage, "$.data.page")).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(secondPage, "$.data.rows[*].employee.id"))
                .containsExactlyElementsOf(idsOf(team.employeeId, team.managerAId));
    }

    /** Workdays and intervals are read in batches: the statements of one board request do not grow with the people on it. */
    @Test
    void theBoardReadsInBatchesNotOncePerPerson() throws Exception {
        team.applyEvent(team.employeeId, "START");
        team.cookieOf(team.ownerId);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            board(team.ownerId, "");
            long statementsWithOneWorkday = statistics.getPrepareStatementCount();
            for (UUID personId : List.of(team.managerAId, team.managerBId, team.employeeOfAId, team.managerCId, team.employeeOfCId)) {
                team.applyEvent(personId, "START");
            }
            clock.advance(Duration.ofMinutes(5));
            team.applyEvent(team.employeeOfAId, "BREAK");
            team.applyEvent(team.managerCId, "END");
            statistics.clear();
            String answer = board(team.ownerId, "");
            assertThat((Integer) JsonPath.read(answer, "$.data.metrics.notStarted")).isZero();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(statementsWithOneWorkday);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void rowsCarryTheEmployeeAndTheirManager() throws Exception {
        Map<String, Object> employee = JsonPath.read(board(team.managerBId, ""), "$.data.rows[0].employee");
        assertThat(employee).containsEntry("id", team.employeeId.toString()).containsEntry("employeeCode", "EMP-7")
                .containsEntry("firstName", "Esha").containsEntry("lastName", "Iyer").containsEntry("managerId", team.managerBId.toString())
                .containsEntry("managerName", "Bala Bose").containsEntry("email", team.emailOf(team.employeeId))
                .containsEntry("role", "EMPLOYEE").containsEntry("isActive", true);
    }

    @Test
    void deactivatedPeopleAreLeftOutUnlessTheirWorkdayIsStillOpen() throws Exception {
        TestData testData = new TestData(jdbcTemplate);
        testData.deactivate(team.employeeOfAId);
        assertThat(memberIds(team.managerAId, "")).containsExactlyElementsOf(idsOf(team.managerBId));

        team.applyEvent(team.employeeId, "START");
        // deactivated behind the API's back (the API itself closes the open workday): the open day keeps them on the board
        testData.deactivate(team.employeeId);
        assertThat(memberIds(team.managerBId, "")).containsExactlyElementsOf(idsOf(team.employeeId));
    }

    @Test
    void anotherBusinessNeverAppears() throws Exception {
        TeamFixture otherTeam = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        otherTeam.applyEvent(otherTeam.employeeId, "START");
        assertThat(memberIds(team.managerBId, "scope=indirect")).containsExactlyElementsOf(idsOf(team.employeeId));
        List<String> everybody = memberIds(team.ownerId, "");
        assertThat(everybody).hasSize(6).doesNotContain(otherTeam.employeeId.toString(), otherTeam.employeeOfCId.toString());
        team.read(team.ownerId, "/api/team/members/" + otherTeam.employeeId).andExpect(status().isNotFound());
        team.read(team.ownerId, "/api/team/members/" + otherTeam.employeeId + "/workdays").andExpect(status().isNotFound());
    }
}

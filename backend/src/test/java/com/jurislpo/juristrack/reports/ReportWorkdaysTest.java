package com.jurislpo.juristrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/reports/workdays}: who may call it and whom they see (scope direct / indirect / all), every filter, sorting and paging,
 * the totals and the daily series against the rows, live figures and "Not ended" for open days, the manager of the day, the audit
 * entry of every request, and the business boundary. Data: {@link ReportTestBase}.
 */
class ReportWorkdaysTest extends ReportTestBase {

    @Test
    void theOwnerSeesEverybodyInTheBusinessByDefault() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.range").value("week"))
                .andExpect(jsonPath("$.data.from").value("2026-10-19"))
                .andExpect(jsonPath("$.data.to").value("2026-10-21"))
                .andExpect(jsonPath("$.data.scope").value("all"))
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(25))
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-21", "2026-10-21", "2026-10-20", "2026-10-20", "2026-10-20",
                        "2026-10-19", "2026-10-19", "2026-10-19")))
                // same date: by employee name
                .andExpect(jsonPath("$.data.rows[0].employee.name").value("Esha Employee"))
                .andExpect(jsonPath("$.data.rows[1].employee.name").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.totals.workdays").value(8))
                .andExpect(jsonPath("$.data.totals.employees").value(5))
                .andExpect(jsonPath("$.data.totals.productiveSeconds").value(216000))
                .andExpect(jsonPath("$.data.totals.breakSeconds").value(5400))
                .andExpect(jsonPath("$.data.totals.spanSeconds").value(221400))
                .andExpect(jsonPath("$.data.totals.breakCount").value(2))
                .andExpect(jsonPath("$.data.totals.averageProductiveSeconds").value(27000))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(3)));
    }

    @Test
    void aRowCarriesTheEmployeeTheManagerOfTheDayAndLiveFigures() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "employeeId=" + employeeId + "&sortDirection=asc").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].date", contains("2026-10-19", "2026-10-20", "2026-10-21")))
                .andExpect(jsonPath("$.data.rows[0].employee.id").value(employeeId.toString()))
                .andExpect(jsonPath("$.data.rows[0].employee.employeeCode").value("E1"))
                .andExpect(jsonPath("$.data.rows[0].employee.name").value("Esha Employee"))
                // Esha reported to Olivia on the 19th and to Maya since: the row keeps the manager of that day
                .andExpect(jsonPath("$.data.rows[0].manager.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.data.rows[0].manager.name").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.rows[1].manager.name").value("Maya Manager"))
                .andExpect(jsonPath("$.data.rows[0].status").value("DAY_ENDED"))
                .andExpect(jsonPath("$.data.rows[0].startedAt").value("2026-10-19T03:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].endedAt").value("2026-10-19T12:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.rows[0].breakSeconds").value(3600))
                .andExpect(jsonPath("$.data.rows[0].spanSeconds").value(9 * 3600))
                .andExpect(jsonPath("$.data.rows[0].breakCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].lastBreak.startedAt").value("2026-10-19T07:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].lastBreak.endedAt").value("2026-10-19T08:30:00Z"))
                .andExpect(jsonPath("$.data.rows[1].lastBreak").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].id").value(employeeOpenWorkdayId.toString()))
                .andExpect(jsonPath("$.data.rows[2].status").value("WORKING"))
                .andExpect(jsonPath("$.data.rows[2].isStale").value(false))
                .andExpect(jsonPath("$.data.rows[2].endedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[2].productiveSeconds").value(7200))
                .andExpect(jsonPath("$.data.rows[2].spanSeconds").value(7200));
        // the owner's own day had no manager
        workdays(owner, "employeeId=" + ownerId).andExpect(jsonPath("$.data.rows[0].manager").value(nullValue()));
        // a day still open after the stale workday hours is "Not ended"
        workdays(owner, "employeeId=" + otherEmployeeId + "&range=custom&from=2026-10-20&to=2026-10-20")
                .andExpect(jsonPath("$.data.rows[0].id").value(otherStaleWorkdayId.toString()))
                .andExpect(jsonPath("$.data.rows[0].isStale").value(true))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(26 * 3600));
    }

    @Test
    void aManagerSeesDirectReportsByDefaultAndIndirectOnesWithTheSwitch() throws Exception {
        Cookie manager = signIn(managerEmail);
        workdays(manager, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("direct"))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.rows[*].employee.name", containsInAnyOrder("Esha Employee", "Esha Employee", "Esha Employee",
                        "Mark Lead")))
                .andExpect(jsonPath("$.data.totals.employees").value(2));
        workdays(manager, "scope=indirect").andExpect(jsonPath("$.data.scope").value("indirect"))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows[*].employee.name", hasItems("Ravi Indirect")));
        workdays(manager, "scope=DIRECT").andExpect(jsonPath("$.data.total").value(4));
        // never everybody, never the people beside or above
        workdays(manager, "scope=all").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        workdays(manager, "employeeId=" + otherEmployeeId).andExpect(status().isNotFound());
        workdays(manager, "employeeId=" + ownerId).andExpect(status().isNotFound());
        workdays(manager, "employeeId=" + managerId).andExpect(status().isNotFound());
        // an indirect report only with the switch
        workdays(manager, "employeeId=" + indirectEmployeeId).andExpect(status().isNotFound());
        workdays(manager, "scope=indirect&employeeId=" + indirectEmployeeId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void theOwnerCanNarrowToTheirOwnReportingLine() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "scope=direct").andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.rows[*].employee.name", everyItem(is("Zed Other"))));
        workdays(owner, "scope=indirect").andExpect(jsonPath("$.data.total").value(6));
    }

    @Test
    void employeesAndTheSuperAdminMayNotReadReports() throws Exception {
        Cookie employee = signIn(employeeEmail);
        workdays(employee, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        summaries(employee, "").andExpect(status().isForbidden());
        csv(employee, "").andExpect(status().isForbidden());
        String superAdminEmail = TestData.uniqueEmail("report.platform");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdmin = signIn(superAdminEmail);
        workdays(superAdmin, "").andExpect(status().isForbidden());
        summaries(superAdmin, "").andExpect(status().isForbidden());
        mockMvc.perform(request("/api/reports/workdays", "")).andExpect(status().isUnauthorized());
        mockMvc.perform(request("/api/reports/summaries", "")).andExpect(status().isUnauthorized());
    }

    @Test
    void theManagerFilterUsesTheManagerOfTheDay() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "managerId=" + ownerId).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows[*].manager.name", everyItem(is("Olivia Owner"))));
        Cookie manager = signIn(managerEmail);
        // a manager may filter by themselves or by someone in their scope
        workdays(manager, "managerId=" + managerId).andExpect(jsonPath("$.data.total").value(3));
        workdays(manager, "scope=indirect&managerId=" + leadId).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].employee.name").value("Ravi Indirect"));
        workdays(manager, "managerId=" + ownerId).andExpect(status().isNotFound());
        workdays(owner, "managerId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void theStatusFilterKnowsNotEnded() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "status=WORKING").andExpect(jsonPath("$.data.total").value(2));
        workdays(owner, "status=not_ended").andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].isStale").value(true));
        workdays(owner, "status=DAY_ENDED").andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.rows[*].status", everyItem(is("DAY_ENDED"))));
        workdays(owner, "status=NOT_ENDED,DAY_ENDED").andExpect(jsonPath("$.data.total").value(7));
        workdays(owner, "status=ON_BREAK").andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.totals.averageProductiveSeconds").value(0));
    }

    @Test
    void searchMatchesTheEmployeesNameAndCode() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "search=esha").andExpect(jsonPath("$.data.total").value(3));
        workdays(owner, "search=empthree").andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].employee.name").value("Ravi Indirect"));
        mockMvc.perform(request("/api/reports/workdays", "").param("search", "zed other").cookie(owner)).andExpect(jsonPath("$.data.total").value(2));
        workdays(owner, "search=nobody").andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.totals.workdays").value(0));
    }

    @Test
    void rangesResolveAgainstTodayInTheBusinessTimeZone() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "range=today").andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.dailySeries", hasSize(1)));
        workdays(owner, "range=yesterday").andExpect(jsonPath("$.data.from").value("2026-10-20"))
                .andExpect(jsonPath("$.data.total").value(3));
        workdays(owner, "range=month").andExpect(jsonPath("$.data.from").value("2026-10-01")).andExpect(jsonPath("$.data.total").value(9))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(21)));
        workdays(owner, "range=custom&from=2026-10-16&to=2026-10-16").andExpect(jsonPath("$.data.total").value(1));
        // reversed ends are swapped; more than 366 days keeps the end and moves the start
        workdays(owner, "range=custom&from=2026-10-20&to=2026-10-16").andExpect(jsonPath("$.data.from").value("2026-10-16"))
                .andExpect(jsonPath("$.data.to").value("2026-10-20"));
        workdays(owner, "range=custom&from=2020-01-01&to=2026-10-21").andExpect(jsonPath("$.data.from").value("2025-10-21"));
    }

    @Test
    void rowsAreSortedAndPagedWhileTotalsCoverTheWholeRange() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "sort=employee&sortDirection=asc").andExpect(jsonPath("$.data.rows[*].employee.name", contains("Esha Employee",
                "Esha Employee", "Esha Employee", "Mark Lead", "Olivia Owner", "Ravi Indirect", "Zed Other", "Zed Other")));
        workdays(owner, "sort=productiveSeconds").andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(26 * 3600))
                .andExpect(jsonPath("$.data.rows[7].productiveSeconds").value(7200));
        workdays(owner, "sort=startedAt&sortDirection=asc").andExpect(jsonPath("$.data.rows[0].startedAt").value("2026-10-19T03:30:00Z"))
                .andExpect(jsonPath("$.data.rows[7].startedAt").value("2026-10-21T03:30:00Z"));
        workdays(owner, "sort=breakSeconds").andExpect(jsonPath("$.data.rows[0].breakSeconds").value(3600));
        workdays(owner, "sort=spanSeconds&sortDirection=asc").andExpect(jsonPath("$.data.rows[0].spanSeconds").value(7200));
        workdays(owner, "sort=breakCount").andExpect(jsonPath("$.data.rows[0].breakCount").value(1));
        workdays(owner, "sort=date&sortDirection=asc").andExpect(jsonPath("$.data.rows[0].date").value("2026-10-19"));
        workdays(owner, "pageSize=3&page=3").andExpect(jsonPath("$.data.rows", hasSize(2))).andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.page").value(3)).andExpect(jsonPath("$.data.totals.workdays").value(8));
        workdays(owner, "pageSize=3&page=9").andExpect(jsonPath("$.data.rows", hasSize(0)));
    }

    @Test
    void totalsAndTheDailySeriesAddUpToTheRows() throws Exception {
        Cookie owner = signIn(ownerEmail);
        for (String query : List.of("", "scope=direct", "status=DAY_ENDED", "range=month")) {
            String body = workdays(owner, query + (query.isEmpty() ? "" : "&") + "pageSize=100").andReturn().getResponse().getContentAsString();
            List<Integer> productive = JsonPath.read(body, "$.data.rows[*].productiveSeconds");
            List<Integer> breaks = JsonPath.read(body, "$.data.rows[*].breakSeconds");
            List<Integer> spans = JsonPath.read(body, "$.data.rows[*].spanSeconds");
            List<Integer> breakCounts = JsonPath.read(body, "$.data.rows[*].breakCount");
            List<String> employeeIds = JsonPath.read(body, "$.data.rows[*].employee.id");
            assertThat(((Number) JsonPath.read(body, "$.data.totals.productiveSeconds")).longValue()).isEqualTo(sum(productive));
            assertThat(((Number) JsonPath.read(body, "$.data.totals.breakSeconds")).longValue()).isEqualTo(sum(breaks));
            assertThat(((Number) JsonPath.read(body, "$.data.totals.spanSeconds")).longValue()).isEqualTo(sum(spans));
            assertThat(((Number) JsonPath.read(body, "$.data.totals.breakCount")).longValue()).isEqualTo(sum(breakCounts));
            assertThat((Integer) JsonPath.read(body, "$.data.totals.workdays")).isEqualTo(productive.size());
            assertThat((Integer) JsonPath.read(body, "$.data.totals.employees")).isEqualTo((int) employeeIds.stream().distinct().count());
            List<Integer> seriesProductive = JsonPath.read(body, "$.data.dailySeries[*].productiveSeconds");
            List<Integer> seriesBreaks = JsonPath.read(body, "$.data.dailySeries[*].breakSeconds");
            List<Integer> seriesWorkdays = JsonPath.read(body, "$.data.dailySeries[*].workdays");
            assertThat(sum(seriesProductive)).isEqualTo(sum(productive));
            assertThat(sum(seriesBreaks)).isEqualTo(sum(breaks));
            assertThat(sum(seriesWorkdays)).isEqualTo(productive.size());
        }
        workdays(owner, "").andExpect(jsonPath("$.data.dailySeries[0].date").value("2026-10-19"))
                .andExpect(jsonPath("$.data.dailySeries[0].productiveSeconds").value(8 * 3600 + 5 * 3600 + 4 * 3600))
                .andExpect(jsonPath("$.data.dailySeries[0].workdays").value(3))
                .andExpect(jsonPath("$.data.dailySeries[2].date").value("2026-10-21"))
                .andExpect(jsonPath("$.data.dailySeries[2].productiveSeconds").value(4 * 3600));
    }

    @Test
    void badParametersAre400() throws Exception {
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "range=decade").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("range"));
        workdays(owner, "range=custom&from=2026-02-30&to=soon").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItems("from", "to")));
        workdays(owner, "scope=everyone").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("scope"));
        workdays(owner, "employeeId=42").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("employeeId"));
        workdays(owner, "managerId=nobody").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("managerId"));
        workdays(owner, "status=SLEEPING").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        workdays(owner, "sort=manager").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("sort"));
        workdays(owner, "sortDirection=sideways").andExpect(status().isBadRequest());
        workdays(owner, "pageSize=101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("pageSize"));
        workdays(owner, "employeeId=" + UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anotherBusinessSeesNothingOfThisOne() throws Exception {
        UUID otherBusinessId = testData.createBusiness("Elsewhere " + UUID.randomUUID());
        String otherOwnerEmail = TestData.uniqueEmail("report.elsewhere");
        UUID otherOwnerId = testData.createUser(otherBusinessId, "ADMIN", otherOwnerEmail, null, "Ola", "Elsewhere", null);
        reportData.endedWorkday(otherBusinessId, otherOwnerId, null, "2026-10-20", "2026-10-20T03:30:00Z", "2026-10-20T05:30:00Z", 7200, 0, 0);
        Cookie otherOwner = signIn(otherOwnerEmail);
        workdays(otherOwner, "").andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].employee.name").value("Ola Elsewhere"));
        workdays(otherOwner, "employeeId=" + employeeId).andExpect(status().isNotFound());
        workdays(otherOwner, "managerId=" + ownerId).andExpect(status().isNotFound());
        summaries(otherOwner, "groupBy=employee").andExpect(jsonPath("$.data.total").value(1));
        Cookie owner = signIn(ownerEmail);
        workdays(owner, "employeeId=" + otherOwnerId).andExpect(status().isNotFound());
        workdays(owner, "").andExpect(jsonPath("$.data.rows[*].employee.name", everyItem(not("Ola Elsewhere"))));
    }

    @Test
    void everyRequestWritesOneAuditEntry() throws Exception {
        Cookie manager = signIn(managerEmail);
        workdays(manager, "scope=indirect&status=DAY_ENDED&search=esha").andExpect(status().isOk());
        workdays(manager, "employeeId=" + employeeId + "&pageSize=1").andExpect(status().isOk());
        workdays(manager, "scope=all").andExpect(status().isForbidden());
        List<String> entries = reportData.reportAuditEntries(managerId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).startsWith("reports.viewed|null|report|workdays|access|").doesNotContain("\"category\"")
                .contains("\"report\": \"workdays\"", "\"scope\": \"indirect\"", "\"status\": [\"DAY_ENDED\"]",
                        "\"search\": \"esha\"", "\"from\": \"2026-10-19\"", "\"to\": \"2026-10-21\"", "\"rowCount\": 2");
        // one entry for the request, whatever the page: the subject is the employee filtered on
        assertThat(entries.get(1)).startsWith("reports.viewed|" + employeeId + "|report|workdays|").contains("\"rowCount\": 3");
    }

    @Test
    void aSignedOutCallerWithTheWebAppOriginIsStill401() throws Exception {
        mockMvc.perform(request("/api/reports/workdays", "").header("origin", SignIn.ORIGIN)).andExpect(status().isUnauthorized());
    }

    private static long sum(List<Integer> values) {
        return values.stream().mapToLong(Integer::longValue).sum();
    }
}

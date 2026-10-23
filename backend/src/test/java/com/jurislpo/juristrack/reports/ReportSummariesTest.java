package com.jurislpo.juristrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/reports/summaries}: the workdays added up per employee, per manager of the day and per date, with the same scope,
 * filters, sorting and paging as the workday report; the groups add up to the totals. Data: {@link ReportTestBase}.
 */
class ReportSummariesTest extends ReportTestBase {

    @Test
    void groupedByEmployeeSortedByName() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupBy").value("employee"))
                .andExpect(jsonPath("$.data.range").value("week"))
                .andExpect(jsonPath("$.data.scope").value("all"))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.rows[*].label", contains("Esha Employee", "Mark Lead", "Olivia Owner", "Ravi Indirect", "Zed Other")))
                .andExpect(jsonPath("$.data.rows[0].key").value(employeeId.toString()))
                .andExpect(jsonPath("$.data.rows[0].employeeCode").value("E1"))
                .andExpect(jsonPath("$.data.rows[0].workdays").value(3))
                .andExpect(jsonPath("$.data.rows[0].employees").value(1))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(16 * 3600))
                .andExpect(jsonPath("$.data.rows[0].breakSeconds").value(3600))
                .andExpect(jsonPath("$.data.rows[0].spanSeconds").value(17 * 3600))
                .andExpect(jsonPath("$.data.rows[0].breakCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].averageProductiveSeconds").value(16 * 3600 / 3))
                .andExpect(jsonPath("$.data.rows[0].firstStartedAt").value("2026-10-19T03:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].lastEndedAt").value("2026-10-20T09:30:00Z"))
                .andExpect(jsonPath("$.data.rows[4].workdays").value(2))
                .andExpect(jsonPath("$.data.rows[4].averageProductiveSeconds").value(15 * 3600))
                .andExpect(jsonPath("$.data.totals.workdays").value(8))
                .andExpect(jsonPath("$.data.totals.productiveSeconds").value(216000))
                .andExpect(jsonPath("$.data.dailySeries", hasSize(3)));
    }

    @Test
    void groupedByTheManagerOfTheDay() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "groupBy=manager").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                // the "no manager" group is last whichever way the names are sorted
                .andExpect(jsonPath("$.data.rows[*].key", contains(leadId.toString(), managerId.toString(), ownerId.toString(), "none")))
                .andExpect(jsonPath("$.data.rows[3].label").value(nullValue()))
                .andExpect(jsonPath("$.data.rows[3].workdays").value(1))
                .andExpect(jsonPath("$.data.rows[2].label").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.rows[2].workdays").value(3))
                .andExpect(jsonPath("$.data.rows[2].employees").value(2))
                .andExpect(jsonPath("$.data.rows[2].employeeCode").value(nullValue()));
        summaries(owner, "groupBy=manager&sortDirection=desc").andExpect(jsonPath("$.data.rows[*].label",
                contains("Olivia Owner", "Maya Manager", "Mark Lead", null)));
        // a manager's report shows who managed their people on each day
        Cookie manager = signIn(managerEmail);
        summaries(manager, "groupBy=manager").andExpect(jsonPath("$.data.scope").value("direct"))
                .andExpect(jsonPath("$.data.rows[*].label", contains("Maya Manager", "Olivia Owner")))
                .andExpect(jsonPath("$.data.rows[0].workdays").value(3));
    }

    @Test
    void groupedByDateNewestFirst() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "groupBy=date").andExpect(jsonPath("$.data.rows[*].key", contains("2026-10-21", "2026-10-20", "2026-10-19")))
                .andExpect(jsonPath("$.data.rows[0].label").value("2026-10-21"))
                .andExpect(jsonPath("$.data.rows[0].workdays").value(2))
                .andExpect(jsonPath("$.data.rows[0].employees").value(2))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(4 * 3600))
                .andExpect(jsonPath("$.data.rows[0].firstStartedAt").value("2026-10-21T02:30:00Z"));
        summaries(owner, "groupBy=date&sortDirection=asc").andExpect(jsonPath("$.data.rows[*].key", contains("2026-10-19", "2026-10-20",
                "2026-10-21")));
    }

    @Test
    void groupsAreSortedByTheirFiguresAndPaged() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "sort=productiveSeconds&sortDirection=desc").andExpect(jsonPath("$.data.rows[*].label",
                contains("Zed Other", "Esha Employee", "Ravi Indirect", "Mark Lead", "Olivia Owner")));
        summaries(owner, "sort=workdays&sortDirection=desc").andExpect(jsonPath("$.data.rows[*].label",
                contains("Esha Employee", "Zed Other", "Mark Lead", "Olivia Owner", "Ravi Indirect")));
        summaries(owner, "sort=averageProductiveSeconds&sortDirection=desc").andExpect(jsonPath("$.data.rows[0].label").value("Zed Other"));
        summaries(owner, "sort=breakSeconds&sortDirection=desc").andExpect(jsonPath("$.data.rows[0].label").value("Esha Employee"));
        summaries(owner, "sort=employees&groupBy=manager&sortDirection=desc").andExpect(jsonPath("$.data.rows[0].employees").value(2));
        summaries(owner, "pageSize=2&page=3").andExpect(jsonPath("$.data.rows[*].label", contains("Zed Other")))
                .andExpect(jsonPath("$.data.total").value(5)).andExpect(jsonPath("$.data.totals.workdays").value(8));
    }

    @Test
    void theSameFiltersApply() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "status=NOT_ENDED").andExpect(jsonPath("$.data.rows[*].label", contains("Zed Other")))
                .andExpect(jsonPath("$.data.rows[0].workdays").value(1));
        summaries(owner, "search=esha&groupBy=date").andExpect(jsonPath("$.data.total").value(3));
        summaries(owner, "employeeId=" + employeeId + "&groupBy=manager").andExpect(jsonPath("$.data.rows[*].label",
                contains("Maya Manager", "Olivia Owner")));
        summaries(owner, "range=today").andExpect(jsonPath("$.data.rows[*].label", contains("Esha Employee", "Olivia Owner")));
        Cookie manager = signIn(managerEmail);
        summaries(manager, "").andExpect(jsonPath("$.data.rows[*].label", contains("Esha Employee", "Mark Lead")));
        summaries(manager, "scope=indirect").andExpect(jsonPath("$.data.rows[*].label", contains("Esha Employee", "Mark Lead", "Ravi Indirect")));
        summaries(manager, "scope=all").andExpect(status().isForbidden());
        summaries(manager, "employeeId=" + otherEmployeeId).andExpect(status().isNotFound());
    }

    @Test
    void groupsAddUpToTheTotals() throws Exception {
        Cookie owner = signIn(ownerEmail);
        for (String groupBy : List.of("employee", "manager", "date")) {
            String body = summaries(owner, "groupBy=" + groupBy + "&pageSize=100").andReturn().getResponse().getContentAsString();
            for (String figure : List.of("workdays", "productiveSeconds", "breakSeconds", "spanSeconds", "breakCount")) {
                List<Integer> values = JsonPath.read(body, "$.data.rows[*]." + figure);
                assertThat(values.stream().mapToLong(Integer::longValue).sum()).as(groupBy + " " + figure)
                        .isEqualTo(((Number) JsonPath.read(body, "$.data.totals." + figure)).longValue());
            }
        }
    }

    @Test
    void badParametersAre400() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "groupBy=team").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("groupBy"));
        summaries(owner, "sort=date").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("sort"));
        summaries(owner, "groupBy=team&range=never").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("groupBy", "range")));
    }

    @Test
    void eachRequestIsAuditedWithItsGrouping() throws Exception {
        Cookie owner = signIn(ownerEmail);
        summaries(owner, "groupBy=manager").andExpect(status().isOk());
        List<String> entries = reportData.reportAuditEntries(ownerId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).startsWith("reports.viewed|null|report|summaries|access|").doesNotContain("\"category\"")
                .contains("\"groupBy\": \"manager\"", "\"scope\": \"all\"", "\"rowCount\": 4");
    }
}

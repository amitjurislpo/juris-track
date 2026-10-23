package com.jurislpo.juristrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The row cap of the CSV export ({@code juristrack.reports.maximum-export-rows}, 50000 in production; 7 here so the week's 8
 * workdays are one too many): 422 {@code EXPORT_TOO_LARGE}, nothing is exported or audited; narrower filters fit again.
 */
@TestPropertySource(properties = "juristrack.reports.maximum-export-rows=7")
class ReportExportLimitTest extends ReportTestBase {

    @Test
    void anExportAboveTheCapIsRefused() throws Exception {
        Cookie owner = signIn(ownerEmail);
        csv(owner, "").andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value("This export has 8 rows; at most 7 fit in one file. Choose a shorter date range or fewer people."));
        assertThat(reportData.reportAuditEntries(ownerId)).isEmpty();
        // the JSON report is paged and has no cap
        workdays(owner, "").andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(8));
        csv(owner, "status=DAY_ENDED").andExpect(status().isOk());
        assertThat(reportData.reportAuditEntries(ownerId)).hasSize(2);
    }
}

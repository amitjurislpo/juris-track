package com.jurislpo.juristrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A night shift recorded through the API (22:00 to 06:00 India time) belongs to the date it started on, in the report, its daily series
 * and its CSV, although it ended on the next calendar day. Data: {@link ReportTestBase} plus Nina, who reports to Maya.
 */
class ReportNightShiftTest extends ReportTestBase {

    @Test
    void aNightShiftKeepsTheDateItStartedOn() throws Exception {
        String nightEmail = TestData.uniqueEmail("report.night");
        UUID nightEmployeeId = testData.createUser(businessId, "EMPLOYEE", nightEmail, managerId, "Nina", "Night", "N1");
        Cookie night = signIn(nightEmail);
        Cookie manager = signIn(managerEmail);
        clock.setTo("2026-10-21T16:30:00Z"); // Wednesday 22:00 in India
        recordEvent(night, "START");
        clock.advance(Duration.ofHours(8)); // Thursday 06:00
        recordEvent(night, "END");
        clock.setTo("2026-10-22T05:00:00Z"); // Thursday 10:30

        workdays(manager, "employeeId=" + nightEmployeeId + "&range=custom&from=2026-10-21&to=2026-10-22")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].date").value("2026-10-21"))
                .andExpect(jsonPath("$.data.rows[0].startedAt").value("2026-10-21T16:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].endedAt").value("2026-10-22T00:30:00Z"))
                .andExpect(jsonPath("$.data.rows[0].productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.rows[0].manager.name").value("Maya Manager"))
                .andExpect(jsonPath("$.data.dailySeries[0].productiveSeconds").value(8 * 3600))
                .andExpect(jsonPath("$.data.dailySeries[1].productiveSeconds").value(0));
        workdays(manager, "employeeId=" + nightEmployeeId + "&range=today").andExpect(jsonPath("$.data.total").value(0));
        summaries(manager, "groupBy=date&employeeId=" + nightEmployeeId + "&range=yesterday")
                .andExpect(jsonPath("$.data.rows[0].key").value("2026-10-21"));
        String csvText = new String(csv(manager, "employeeId=" + nightEmployeeId + "&range=yesterday").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csvText).endsWith("\r\n2026-10-21,Nina Night,N1,Maya Manager,Day Ended,22:00,06:00,8.00,0.00,8.00,0\r\n");
    }

    private void recordEvent(Cookie accessCookie, String type) throws Exception {
        mockMvc.perform(SignIn.fromWebApp(post("/api/me/time-events")).cookie(accessCookie).content("{\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated());
    }
}

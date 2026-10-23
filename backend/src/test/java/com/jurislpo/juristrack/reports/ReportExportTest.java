package com.jurislpo.juristrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The CSV download of the workday report ({@code GET /api/reports/workdays} with {@code Accept: text/csv}): the exact bytes (byte order
 * mark, CRLF after every line, quoting and the formula guard), times and header abbreviation in the display time zone (India, New York,
 * and New York on the day summer time starts), the allowed display zones, errors as problem details, and the audit entry. Data:
 * {@link ReportTestBase}.
 */
class ReportExportTest extends ReportTestBase {

    private static final String HEADER = "Date,Employee,Employee ID,Manager,Status,Work started (%1$s),End (%1$s),Productive hours,Break hours,"
            + "Span hours,Breaks,Last break (%1$s),Extra hours,Shift,Scheduled start (%1$s),Scheduled end (%1$s),Outside shift,Week off";

    @Test
    void theOwnersExportHasEveryRowOfTheRangeByteForByte() throws Exception {
        Cookie owner = signIn(ownerEmail);
        byte[] body = csv(owner, "pageSize=1").andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"juristrack-2026-10-19-to-2026-10-21.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        String expected = "﻿" + String.join("\r\n",
                HEADER.formatted("IST"),
                "2026-10-21,Esha Employee,E1,Maya Manager,Working,09:00,,2.00,0.00,2.00,0,,0.00,,,,,",
                "2026-10-21,Olivia Owner,O1,,Day Ended,08:00,10:00,2.00,0.00,2.00,0,,0.00,,,,,",
                "2026-10-20,Esha Employee,E1,Maya Manager,Day Ended,09:00,15:00,6.00,0.00,6.00,0,,0.00,,,,,",
                "2026-10-20,Ravi Indirect,EMPTHREE,Mark Lead,Day Ended,08:30,16:00,7.00,0.50,7.50,1,11:30,0.00,,,,,",
                "2026-10-20,Zed Other,E4,Olivia Owner,Not ended,09:00,,26.00,0.00,26.00,0,,0.00,,,,,",
                "2026-10-19,Esha Employee,E1,Olivia Owner,Day Ended,09:00,18:00,8.00,1.00,9.00,1,13:00,0.00,,,,,",
                "2026-10-19,Mark Lead,M2,Maya Manager,Day Ended,10:00,15:00,5.00,0.00,5.00,0,,0.00,,,,,",
                "2026-10-19,Zed Other,E4,Olivia Owner,Day Ended,09:00,13:00,4.00,0.00,4.00,0,,0.00,,,,,") + "\r\n";
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(body[body.length - 2]).isEqualTo((byte) '\r');
        assertThat(body[body.length - 1]).isEqualTo((byte) '\n');
    }

    @Test
    void aManagersExportFollowsTheirScopeFiltersAndSorting() throws Exception {
        Cookie manager = signIn(managerEmail);
        String direct = csvText(manager, "sort=employee&sortDirection=asc");
        assertThat(direct.split("\r\n")).hasSize(5);
        assertThat(direct).contains("\r\n2026-10-21,Esha Employee,").doesNotContain("Zed Other", "Ravi Indirect", "Olivia Owner,O1");
        assertThat(direct.split("\r\n")[4]).startsWith("2026-10-19,Mark Lead,M2,");
        assertThat(csvText(manager, "scope=indirect").split("\r\n")).hasSize(6);
        assertThat(csvText(manager, "scope=indirect&status=DAY_ENDED&search=ravi").split("\r\n")).hasSize(2);
        csv(manager, "scope=all").andExpect(status().isForbidden());
        csv(manager, "employeeId=" + otherEmployeeId).andExpect(status().isNotFound());
    }

    @Test
    void timesAndTheHeaderFollowTheDisplayTimeZone() throws Exception {
        Cookie owner = signIn(ownerEmail);
        String newYork = csvText(owner, "timeZone=America/New_York");
        assertThat(newYork).startsWith("﻿" + HEADER.formatted("EDT") + "\r\n")
                .contains("\r\n2026-10-20,Ravi Indirect,EMPTHREE,Mark Lead,Day Ended,23:00,2026-10-20 06:30,7.00,0.50,7.50,1,2026-10-20 02:00,0.00,,,,,\r\n")
                .contains("\r\n2026-10-21,Esha Employee,E1,Maya Manager,Working,23:30,,");
        // the zone is matched in any case; the business zone may be named explicitly
        assertThat(csvText(owner, "timeZone=america/new_york")).startsWith("﻿" + HEADER.formatted("EDT"));
        assertThat(csvText(owner, "timeZone=Asia/Kolkata")).startsWith("﻿" + HEADER.formatted("IST"));
        // in winter New York is EST
        clock.setTo("2026-12-02T06:00:00Z");
        assertThat(csvText(owner, "timeZone=America/New_York&range=custom&from=2026-10-19&to=2026-10-19"))
                .startsWith("﻿" + HEADER.formatted("EST"))
                .contains("\r\n2026-10-19,Esha Employee,E1,Olivia Owner,Day Ended,23:30,2026-10-19 08:30,8.00,1.00,9.00,1,2026-10-19 03:30,0.00,,,,,\r\n");
    }

    @Test
    void theDaySummerTimeStartsInNewYork() throws Exception {
        UUID dstEmployeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("report.dst"), ownerId, "Dana", "Daylight", "D1");
        // 01:00 EST to 04:00 EDT on 8 March 2026: two hours of work across the change
        reportData.endedWorkday(businessId, dstEmployeeId, ownerId, "2026-03-08", "2026-03-08T06:00:00Z", "2026-03-08T08:00:00Z", 7200, 0, 0);
        Cookie owner = signIn(ownerEmail);
        clock.setTo("2026-03-08T12:00:00Z");
        assertThat(csvText(owner, "range=today&timeZone=America/New_York")).isEqualTo("﻿" + HEADER.formatted("EDT") + "\r\n"
                + "2026-03-08,Dana Daylight,D1,Olivia Owner,Day Ended,01:00,04:00,2.00,0.00,2.00,0,,0.00,,,,,\r\n");
        // one second before the change the header still says EST
        clock.setTo("2026-03-08T06:59:59Z");
        assertThat(csvText(owner, "range=yesterday&timeZone=America/New_York")).isEqualTo("﻿" + HEADER.formatted("EST") + "\r\n");
        assertThat(csvText(owner, "range=yesterday")).isEqualTo("﻿" + HEADER.formatted("IST") + "\r\n");
    }

    @Test
    void spreadsheetFormulasInNamesAndCodesAreNeutralised() throws Exception {
        UUID trickyEmployeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("report.tricky"), ownerId, "=cmd",
                "|calc, \"x\"", "-5x");
        reportData.endedWorkday(businessId, trickyEmployeeId, ownerId, "2026-10-21", "2026-10-21T03:30:00Z", "2026-10-21T04:30:00Z", 3600, 0, 0);
        jdbcTemplate.update("UPDATE users SET first_name = '@Olivia' WHERE id = ?", ownerId);
        Cookie owner = signIn(ownerEmail);
        assertThat(csvText(owner, "employeeId=" + trickyEmployeeId)).isEqualTo("﻿" + HEADER.formatted("IST") + "\r\n"
                + "2026-10-21,\"'=cmd |calc, \"\"x\"\"\",'-5x,'@Olivia Owner,Day Ended,09:00,10:00,1.00,0.00,1.00,0,,0.00,,,,,\r\n");
    }

    @Test
    void onlyTheBusinessZoneAndItsDisplayZonesAreAllowed() throws Exception {
        Cookie owner = signIn(ownerEmail);
        csv(owner, "timeZone=Europe/London").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("timeZone"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        csv(owner, "timeZone=Mars/Olympus").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("timeZone"));
        csv(owner, "range=fortnight").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        jdbcTemplate.update("UPDATE businesses SET display_time_zones = ARRAY['Europe/London']::text[] WHERE id = ?", businessId);
        // 21 October: London is still on summer time
        assertThat(csvText(owner, "timeZone=Europe/London")).startsWith("﻿" + HEADER.formatted("BST"));
        csv(owner, "timeZone=America/New_York").andExpect(status().isBadRequest());
    }

    @Test
    void jsonStaysTheDefaultRepresentation() throws Exception {
        Cookie owner = signIn(ownerEmail);
        mockMvc.perform(request("/api/reports/workdays", "").cookie(owner).header("accept", "*/*")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(jsonPath("$.data.total").value(8));
        mockMvc.perform(request("/api/reports/workdays", "").cookie(owner).header("accept", "application/json, text/plain")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(request("/api/reports/workdays", "").cookie(owner).header("accept", "text/csv;q=0.9, application/json"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8"));
    }

    @Test
    void theExportIsAuditedOnce() throws Exception {
        Cookie manager = signIn(managerEmail);
        csv(manager, "scope=indirect&timeZone=America/New_York").andExpect(status().isOk());
        List<String> entries = reportData.reportAuditEntries(managerId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).startsWith("reports.exported|null|report|workdays|access|").doesNotContain("\"category\"")
                .contains("\"scope\": \"indirect\"", "\"timeZone\": \"America/New_York\"",
                        "\"fileName\": \"juristrack-2026-10-19-to-2026-10-21.csv\"", "\"rowCount\": 5");
        csv(manager, "employeeId=" + employeeId).andExpect(status().isOk());
        assertThat(reportData.reportAuditEntries(managerId).get(1)).startsWith("reports.exported|" + employeeId + "|");
    }

    private String csvText(Cookie accessCookie, String query) throws Exception {
        return new String(csv(accessCookie, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
    }
}

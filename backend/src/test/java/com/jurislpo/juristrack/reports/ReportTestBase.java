package com.jurislpo.juristrack.reports;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One business in India for the report tests. "Now" is Wednesday 21 October 2026, 11:00 India time (05:30Z), so {@code range=week}
 * is Monday 19 to Wednesday 21 October. The reporting line and the workdays of that week:
 *
 * <pre>
 * Olivia Owner (ADMIN, O1)                    21st ended 2 h (no manager that day)
 * ├── Maya Manager (MANAGER, M1)              no workdays
 * │   ├── Esha Employee (EMPLOYEE, E1)        19th ended 8 h + 1 h break (manager that day: Olivia), 20th ended 6 h, 21st open 2 h so far
 * │   └── Mark Lead (MANAGER, M2)             19th ended 5 h
 * │       └── Ravi Indirect (EMPLOYEE, EMPTHREE)  20th ended 7 h + 30 min break
 * └── Zed Other (EMPLOYEE, E4)                19th ended 4 h, 20th open since 09:00 (26 h: "Not ended")
 * </pre>
 * Maya sees Esha and Mark (direct), and Ravi with {@code scope=indirect}; never Zed or Olivia.
 */
abstract class ReportTestBase extends IntegrationTest {

    static final String NOW = "2026-10-21T05:30:00Z";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    ReportData reportData;

    UUID businessId;
    UUID ownerId;
    UUID managerId;
    UUID leadId;
    UUID employeeId;
    UUID indirectEmployeeId;
    UUID otherEmployeeId;
    String ownerEmail;
    String managerEmail;
    String employeeEmail;
    UUID employeeOpenWorkdayId;
    UUID otherStaleWorkdayId;

    @BeforeEach
    void createBusiness() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        reportData = new ReportData(jdbcTemplate);
        businessId = testData.createBusiness("Report Co " + UUID.randomUUID());
        ownerEmail = TestData.uniqueEmail("report.owner");
        managerEmail = TestData.uniqueEmail("report.manager");
        employeeEmail = TestData.uniqueEmail("report.employee");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", "O1");
        managerId = testData.createUser(businessId, "MANAGER", managerEmail, ownerId, "Maya", "Manager", "M1");
        leadId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("report.lead"), managerId, "Mark", "Lead", "M2");
        employeeId = testData.createUser(businessId, "EMPLOYEE", employeeEmail, managerId, "Esha", "Employee", "E1");
        indirectEmployeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("report.indirect"), leadId, "Ravi", "Indirect",
                "EMPTHREE");
        otherEmployeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("report.other"), ownerId, "Zed", "Other", "E4");

        UUID eshaBreakWorkdayId = reportData.endedWorkday(businessId, employeeId, ownerId, "2026-10-19", "2026-10-19T03:30:00Z", "2026-10-19T12:30:00Z", 8 * 3600, 3600, 1);
        reportData.breakSession(businessId, employeeId, eshaBreakWorkdayId, "2026-10-19T07:30:00Z", "2026-10-19T08:30:00Z");
        reportData.endedWorkday(businessId, employeeId, managerId, "2026-10-20", "2026-10-20T03:30:00Z", "2026-10-20T09:30:00Z", 6 * 3600, 0, 0);
        employeeOpenWorkdayId = reportData.openWorkday(businessId, employeeId, managerId, "2026-10-21", "2026-10-21T03:30:00Z");
        reportData.endedWorkday(businessId, leadId, managerId, "2026-10-19", "2026-10-19T04:30:00Z", "2026-10-19T09:30:00Z", 5 * 3600, 0, 0);
        UUID raviBreakWorkdayId = reportData.endedWorkday(businessId, indirectEmployeeId, leadId, "2026-10-20", "2026-10-20T03:00:00Z", "2026-10-20T10:30:00Z", 7 * 3600,
                1800, 1);
        reportData.breakSession(businessId, indirectEmployeeId, raviBreakWorkdayId, "2026-10-20T06:00:00Z", "2026-10-20T06:30:00Z");
        reportData.endedWorkday(businessId, otherEmployeeId, ownerId, "2026-10-19", "2026-10-19T03:30:00Z", "2026-10-19T07:30:00Z", 4 * 3600, 0, 0);
        otherStaleWorkdayId = reportData.openWorkday(businessId, otherEmployeeId, ownerId, "2026-10-20", "2026-10-20T03:30:00Z");
        reportData.endedWorkday(businessId, ownerId, null, "2026-10-21", "2026-10-21T02:30:00Z", "2026-10-21T04:30:00Z", 2 * 3600, 0, 0);
        // last week, outside range=week
        reportData.endedWorkday(businessId, employeeId, managerId, "2026-10-16", "2026-10-16T03:30:00Z", "2026-10-16T11:30:00Z", 8 * 3600, 0, 0);
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    /** Signs {@code email} in (at the real time) and sets the clock to {@link #NOW}; returns the access cookie. */
    Cookie signIn(String email) throws Exception {
        clock.followSystemTime();
        Cookie accessCookie = SignIn.session(mockMvc, email).accessCookie();
        clock.setTo(NOW);
        return accessCookie;
    }

    ResultActions workdays(Cookie accessCookie, String query) throws Exception {
        return mockMvc.perform(request("/api/v1/reports/workdays", query).cookie(accessCookie));
    }

    ResultActions summaries(Cookie accessCookie, String query) throws Exception {
        return mockMvc.perform(request("/api/v1/reports/summaries", query).cookie(accessCookie));
    }

    ResultActions csv(Cookie accessCookie, String query) throws Exception {
        return mockMvc.perform(request("/api/v1/reports/workdays", query).cookie(accessCookie).header("accept", "text/csv"));
    }

    static MockHttpServletRequestBuilder request(String path, String query) {
        return get(path + (query.isEmpty() ? "" : "?" + query));
    }
}

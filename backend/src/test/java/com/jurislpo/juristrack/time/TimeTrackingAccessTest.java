package com.jurislpo.juristrack.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may track time and see which workdays ("Authorization scope" of the old verify-flows checks, as far as this module goes):
 * every business role tracks its own time, the platform super admin none; a colleague or a manager outside the reporting line does
 * not see another person's workday (404), their manager and the owner do (Team tab, see TeamWorkdayAccessTest), and nobody sees one
 * of another business; a suspended business is refused.
 */
class TimeTrackingAccessTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TestData testData;
    UUID businessId;
    UUID managerId;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Access Co " + UUID.randomUUID());
        managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("access.manager"), null);
        clock.setTo("2026-10-01T03:30:00Z");
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private TimeTracker signInNew(UUID inBusinessId, String role, UUID withManagerId) throws Exception {
        String email = TestData.uniqueEmail("access." + role.toLowerCase());
        testData.createUser(inBusinessId, role, email, withManagerId);
        return new TimeTracker(mockMvc, SignIn.session(mockMvc, email).accessCookie());
    }

    /** Every role also reads the business's display time zones (for the time-zone switcher) from its time state. */
    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "MANAGER", "ADMIN"})
    void everyBusinessRoleTracksItsOwnTime(String role) throws Exception {
        jdbcTemplate.update("UPDATE businesses SET display_time_zones = ARRAY['Europe/London', 'America/New_York']::text[] WHERE id = ?", businessId);
        TimeTracker tracker = signInNew(businessId, role, role.equals("EMPLOYEE") ? managerId : null);
        List<String> displayTimeZones = tracker.stateValue("$.data.displayTimeZones");
        assertThat(displayTimeZones).containsExactly("Europe/London", "America/New_York");
        String startAnswer = tracker.apply("START");
        List<String> displayTimeZonesAfterStart = JsonPath.read(startAnswer, "$.data.state.displayTimeZones");
        assertThat(displayTimeZonesAfterStart).containsExactly("Europe/London", "America/New_York");
        String workdayId = JsonPath.read(startAnswer, "$.data.state.workday.id");
        tracker.apply("END");
        tracker.history("").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].id").value(workdayId));
        tracker.workday(workdayId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DAY_ENDED"));
    }

    @Test
    void theSuperAdminDoesNotTrackTime() throws Exception {
        String email = TestData.uniqueEmail("access.platform");
        testData.createUser(null, "SUPER_ADMIN", email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/v1/me/time").cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookie).content("{\"type\":\"START\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workdays/mine").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workdays/" + UUID.randomUUID()).cookie(cookie)).andExpect(status().isForbidden());
    }

    @Test
    void signedOutCallsAre401AndAWriteWithoutTheAppsOriginIsBlocked() throws Exception {
        mockMvc.perform(get("/api/v1/me/time")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/workdays/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).content("{\"type\":\"START\"}")).andExpect(status().isUnauthorized());

        String email = TestData.uniqueEmail("access.origin");
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(post("/api/v1/me/time-events").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"START\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, employeeId)).isZero();
    }

    @Test
    void anUnknownOrMissingEventTypeIs400() throws Exception {
        TimeTracker tracker = signInNew(businessId, "EMPLOYEE", null);
        tracker.send("LUNCH").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.detail").value("The type is START, BREAK, RESUME or END."));
        tracker.send("").andExpect(status().isBadRequest());
        String email = TestData.uniqueEmail("access.body");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookie).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookie).content("{\"type\":")).andExpect(status().isBadRequest());
    }

    /** The employee id never comes from the request: an extra field naming somebody else is ignored. */
    @Test
    void eventsAlwaysApplyToTheSignedInUser() throws Exception {
        String email = TestData.uniqueEmail("access.self");
        UUID selfId = testData.createUser(businessId, "EMPLOYEE", email, null);
        UUID otherId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("access.other"), null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookie)
                .content("{\"type\":\"START\",\"employeeId\":\"" + otherId + "\"}")).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, selfId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workdays WHERE employee_id = ?", Integer.class, otherId)).isZero();
    }

    @Test
    void colleaguesAndOtherManagersDoNotSeeAnotherPersonsWorkday() throws Exception {
        TimeTracker report = signInNew(businessId, "EMPLOYEE", managerId);
        String workdayId = JsonPath.read(report.apply("START"), "$.data.state.workday.id");
        TimeTracker colleague = signInNew(businessId, "EMPLOYEE", managerId);
        colleague.workday(workdayId).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Workday not found."));
        TimeTracker otherManager = signInNew(businessId, "MANAGER", null);
        otherManager.workday(workdayId).andExpect(status().isNotFound());
        TimeTracker owner = signInNew(businessId, "ADMIN", null);
        owner.workday(workdayId).andExpect(status().isOk());
        // only the owner's read happened: one access entry, none for the refused ones
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'access.workday_viewed' AND entity_id = ?", Integer.class,
                workdayId)).isEqualTo(1);
        colleague.history("").andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        report.workday(UUID.randomUUID()).andExpect(status().isNotFound());
        report.workday("not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void anotherBusinessesWorkdaysStayInvisible() throws Exception {
        TimeTracker local = signInNew(businessId, "EMPLOYEE", null);
        String workdayId = JsonPath.read(local.apply("START"), "$.data.state.workday.id");
        UUID otherBusinessId = testData.createBusiness("Other Access Co " + UUID.randomUUID());
        TimeTracker foreign = signInNew(otherBusinessId, "ADMIN", null);
        foreign.workday(workdayId).andExpect(status().isNotFound());
        foreign.apply("START");
        foreign.history("").andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[*].id", not(hasItem(workdayId))));
        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT business_id) FROM workdays WHERE business_id IN (?, ?)", Integer.class,
                businessId, otherBusinessId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT business_id FROM workdays WHERE id = ?::uuid", UUID.class, workdayId)).isEqualTo(businessId);
    }

    @Test
    void aSuspendedBusinessCannotTrackTimeAndItsOpenDayWasClosed() throws Exception {
        String superAdminEmail = TestData.uniqueEmail("access.platform.suspend");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        TimeTracker tracker = signInNew(businessId, "EMPLOYEE", null);
        tracker.apply("START");
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":true}"))
                .andExpect(status().isOk());
        tracker.send("END").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        tracker.history("").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":false}"))
                .andExpect(status().isOk());
        // the suspension closed the open day (it does not count as work) and the reactivation does not reopen it
        assertThat((String) tracker.stateValue("$.data.status")).isEqualTo("DAY_ENDED");
    }
}

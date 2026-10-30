package com.jurislpo.juristrack.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The stale workday hours rule: the setting must be at least the longest active shift (whole hours, rounded up) plus 3 hours; the
 * default calendar day and a shift without a fixed window do not count; a shift that would break the rule is refused (422) until the
 * owner raises the hours. Only writes are checked: a business whose stored value is already lower stays readable.
 */
class StaleWorkdayHoursRuleTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Stale Rule Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("stale.owner");
        testData.createUser(businessId, "ADMIN", ownerEmail, null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    private ResultActions createShift(Cookie cookie, String name, String startTime, String endTime) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/v1/shifts")).cookie(cookie)
                .content("{\"name\":\"" + name + "\",\"startTime\":\"" + startTime + "\",\"endTime\":\"" + endTime + "\"}"));
    }

    private UUID createShiftOk(String name, String startTime, String endTime) throws Exception {
        String answer = createShift(ownerCookie, name, startTime, endTime).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(answer, "$.data.id"));
    }

    private ResultActions setStaleHours(Cookie cookie, String json) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/business")).cookie(cookie).content(json));
    }

    private int storedStaleHours() {
        return jdbcTemplate.queryForObject("SELECT stale_workday_hours FROM businesses WHERE id = ?", Integer.class, businessId);
    }

    @Test
    void withoutACustomShiftTheMinimumIsOneAndTheCalendarDayIsNotTwentyFourHours() throws Exception {
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(1));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.data.staleWorkdayHours").value(1));
    }

    @Test
    void belowTheLongestShiftPlusThreeIsRefusedWithTheNumbers() throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(12));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":11}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STALE_WORKDAY_HOURS_TOO_LOW"))
                .andExpect(jsonPath("$.detail").value("Stale workday hours must be at least 12: your longest shift is 9 h plus 3 h."));
        assertThat(storedStaleHours()).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'business.updated'", Integer.class, businessId)).isZero();
    }

    @Test
    void exactlyTheMinimumIsAccepted() throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":12}").andExpect(status().isOk()).andExpect(jsonPath("$.data.staleWorkdayHours").value(12))
                .andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(12));
        assertThat(storedStaleHours()).isEqualTo(12);
    }

    @Test
    void aNightShiftCrossingMidnightCountsItsRealLengthRoundedUp() throws Exception {
        createShiftOk("US night", "19:30", "04:45"); // 9 h 15 min: 10 h
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(13));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":12}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Stale workday hours must be at least 13: your longest shift is 10 h plus 3 h."));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":13}").andExpect(status().isOk());
    }

    @Test
    void theLongestActiveShiftDecidesAndADeactivatedShiftDoesNotCount() throws Exception {
        createShiftOk("Short", "09:00", "15:00");
        UUID longId = createShiftOk("Long", "08:00", "18:00");
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(13));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/shifts/" + longId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(9));
    }

    @Test
    void theDefaultAllDayShiftAndAShiftWithoutAFixedWindowAreNotTwentyFourHours() throws Exception {
        // a shift whose start equals its end has no fixed window (the same shape as the default calendar day)
        createShiftOk("Round the clock", "06:00", "06:00");
        createShiftOk("Eight hours", "10:00", "18:00");
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(11));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":11}").andExpect(status().isOk());
    }

    @Test
    void theLongestPossibleShiftNeedsTwentySevenAndFortyNineIsStillOutOfRange() throws Exception {
        jdbcTemplate.update("UPDATE businesses SET stale_workday_hours = 48 WHERE id = ?", businessId);
        // 23 h 59 min rounds up to 24 h; plus 3 h = 27, so the 48 maximum can never be out of reach
        jdbcTemplate.update("INSERT INTO shifts (id, business_id, name, start_minute, end_minute) VALUES (?, ?, 'Very long', 0, 1439)", UUID.randomUUID(), businessId);
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(27));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":26}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Stale workday hours must be at least 27: your longest shift is 24 h plus 3 h."));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":49}").andExpect(status().isBadRequest());
    }

    @Test
    void aShiftThatNeedsMoreStaleHoursThanTheBusinessHasIsRefusedOnCreateEditAndReactivation() throws Exception {
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":12}").andExpect(status().isOk());
        createShiftOk("Nine", "09:00", "18:00"); // 9 + 3 = 12: fits exactly
        createShift(ownerCookie, "Ten", "09:00", "19:00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHIFT_LONGER_THAN_STALE_WORKDAY_HOURS"))
                .andExpect(jsonPath("$.detail").value("This shift is 10 h long, so the stale workday hours must be at least 13 (the longest shift plus 3 h), but they are 12. "
                        + "Raise the stale workday hours in Settings first."));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE business_id = ? AND name = 'Ten'", Integer.class, businessId)).isZero();

        UUID shiftId = createShiftOk("Edit me", "09:00", "12:00");
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/shifts/" + shiftId)).cookie(ownerCookie).content("{\"endTime\":\"20:00\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_LONGER_THAN_STALE_WORKDAY_HOURS"));
        assertThat(jdbcTemplate.queryForObject("SELECT end_minute FROM shifts WHERE id = ?", Integer.class, shiftId)).isEqualTo(12 * 60);
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/shifts/" + shiftId)).cookie(ownerCookie).content("{\"name\":\"Renamed\"}")).andExpect(status().isOk());

        // reactivating a long inactive shift is the same refusal
        UUID inactiveId = createShiftOk("Sleeper", "09:00", "11:00");
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/shifts/" + inactiveId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}")).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE shifts SET end_minute = ? WHERE id = ?", 21 * 60, inactiveId);
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/shifts/" + inactiveId + "/status")).cookie(ownerCookie).content("{\"isActive\":true}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_LONGER_THAN_STALE_WORKDAY_HOURS"));

        // raising the stale hours first makes the same shift acceptable
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":13}").andExpect(status().isOk());
        createShift(ownerCookie, "Ten", "09:00", "19:00").andExpect(status().isCreated());
    }

    @Test
    void anExistingValueBelowTheMinimumStaysReadableAndOtherSettingsStillSave() throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        jdbcTemplate.update("UPDATE businesses SET stale_workday_hours = 10 WHERE id = ?", businessId);
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staleWorkdayHours").value(10)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(12));
        setStaleHours(ownerCookie, "{\"name\":\"Renamed Stale Co\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.staleWorkdayHours").value(10));
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":10}").andExpect(status().isUnprocessableEntity());
        assertThat(storedStaleHours()).isEqualTo(10);
    }

    @Test
    void theSuperAdminEditIsHeldToTheSameRuleAndShowsTheMinimum() throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        String superAdminEmail = TestData.uniqueEmail("stale.super");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Cookie superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/businesses/" + businessId)).cookie(superAdminCookie).content("{\"staleWorkdayHours\":11}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STALE_WORKDAY_HOURS_TOO_LOW"))
                .andExpect(jsonPath("$.detail").value("Stale workday hours must be at least 12: your longest shift is 9 h plus 3 h."));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/businesses/" + businessId)).cookie(superAdminCookie).content("{\"staleWorkdayHours\":12}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.staleWorkdayHours").value(12)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE"})
    void otherRolesCannotChangeTheHoursOrTheShifts(String role) throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser(businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        setStaleHours(cookie, "{\"staleWorkdayHours\":20}").andExpect(status().isForbidden());
        createShift(cookie, "Sneaky", "09:00", "19:00").andExpect(status().isForbidden());
        assertThat(storedStaleHours()).isEqualTo(14);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "49", "1.5", "\"12\"; DROP TABLE businesses;--", "\"<script>alert(1)</script>\"", "99999999999999999999", "null", "true"})
    void hostileValuesAreRefusedAndNothingIsStored(String value) throws Exception {
        createShiftOk("Nine hours", "09:00", "18:00");
        setStaleHours(ownerCookie, "{\"staleWorkdayHours\":" + value + "}").andExpect(status().is4xxClientError());
        assertThat(storedStaleHours()).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE id = ?", Integer.class, businessId)).isEqualTo(1);
    }

    @Test
    void anotherBusinessShiftsDoNotRaiseTheMinimum() throws Exception {
        UUID otherBusinessId = testData.createBusiness("Other Stale Co " + UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO shifts (id, business_id, name, start_minute, end_minute) VALUES (?, ?, 'Elsewhere', 360, 1200)", UUID.randomUUID(), otherBusinessId);
        mockMvc.perform(get("/api/v1/business").cookie(ownerCookie)).andExpect(jsonPath("$.data.minimumStaleWorkdayHours").value(1));
    }
}

package com.jurislpo.juristrack.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The owner's {@code /api/business}: read and change their own business's settings (ADMIN only), audited, validated. */
class BusinessSettingsTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Settings Owner Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("settings.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    @Test
    void theOwnerReadsTheSettings() throws Exception {
        mockMvc.perform(get("/api/business").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(businessId.toString()))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.staleWorkdayHours").value(14))
                .andExpect(jsonPath("$.data.displayTimeZones[*]").value(hasItems("Asia/Kolkata", "America/New_York")))
                .andExpect(jsonPath("$.data.createdAt").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "SUPER_ADMIN"})
    void nobodyElseReachesTheSettings(String role) throws Exception {
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser("SUPER_ADMIN".equals(role) ? null : businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/business").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(cookie).content("{\"name\":\"Taken over\"}")).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId)).startsWith("Settings Owner Co");
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(get("/api/business")).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void theOwnerChangesTheSettingsOfTheirOwnBusinessOnly() throws Exception {
        UUID otherBusinessId = testData.createBusiness("Other Settings Co " + UUID.randomUUID());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(ownerCookie)
                        .content("{\"name\":\"Renamed Co\",\"timeZone\":\"asia/calcutta\",\"staleWorkdayHours\":48,\"displayTimeZones\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Settings saved."))
                .andExpect(jsonPath("$.data.name").value("Renamed Co"))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Calcutta"))
                .andExpect(jsonPath("$.data.staleWorkdayHours").value(48))
                .andExpect(jsonPath("$.data.displayTimeZones.length()").value(0));
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT actor_id, entity_id, \"before\"->>'timeZone' AS time_zone_before, "
                + "\"after\"->>'timeZone' AS time_zone_after FROM audit_logs WHERE business_id = ? AND action = 'business.updated'", businessId);
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("entity_id", businessId.toString())
                .containsEntry("time_zone_before", "Asia/Kolkata").containsEntry("time_zone_after", "Asia/Calcutta");
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, otherBusinessId)).startsWith("Other Settings Co");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ?", Integer.class, otherBusinessId)).isZero();
    }

    @Test
    void badSettingsAre400AndNothingIsStored() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(ownerCookie)
                        .content("{\"name\":\"\",\"timeZone\":null,\"staleWorkdayHours\":0,\"displayTimeZones\":[\"UTC\",\"Atlantis/Lost\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("name", "timeZone", "staleWorkdayHours", "displayTimeZones[1]")));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(ownerCookie).content("{\"displayTimeZones\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(ownerCookie).content("{\"staleWorkdayHours\":\"many\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/business")).cookie(ownerCookie).content(
                "{\"displayTimeZones\":[\"UTC\",\"Europe/London\",\"Europe/Paris\",\"Europe/Berlin\",\"Asia/Tokyo\",\"Asia/Dubai\",\"Asia/Singapore\",\"Australia/Sydney\",\"America/Chicago\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("displayTimeZones"));
        assertThat(jdbcTemplate.queryForObject("SELECT stale_workday_hours FROM businesses WHERE id = ?", Integer.class, businessId)).isEqualTo(14);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'business.updated'", Integer.class, businessId))
                .isZero();
    }
}

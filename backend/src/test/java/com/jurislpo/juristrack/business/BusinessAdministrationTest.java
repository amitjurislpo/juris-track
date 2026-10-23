package com.jurislpo.juristrack.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The super admin's {@code /api/businesses}: list, create with the owner, detail, settings, suspension; audited; SUPER_ADMIN only. */
class BusinessAdministrationTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired ObjectMapper objectMapper;
    TestData testData;
    UUID superAdminId;
    Cookie superAdminCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        String superAdminEmail = TestData.uniqueEmail("platform");
        superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
    }

    private String createBusiness(String name, String ownerEmail) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"owner\":{\"firstName\":\"Olivia\",\"lastName\":\"Owner\",\"email\":\"" + ownerEmail + "\"}}";
        String response = mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.id");
    }

    private List<Map<String, Object>> auditEntries(String businessId) {
        return jdbcTemplate.queryForList("SELECT action, actor_id, subject_id, entity_type, entity_id, \"before\"::text AS before_json, "
                + "\"after\"::text AS after_json, reason FROM audit_logs WHERE business_id = ?::uuid ORDER BY created_at, action", businessId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MANAGER", "EMPLOYEE"})
    void onlyTheSuperAdminReachesTheBusinesses(String role) throws Exception {
        UUID businessId = testData.createBusiness("Role check " + UUID.randomUUID());
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser(businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(get("/api/businesses").cookie(cookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/businesses/" + businessId).cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(cookie).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/businesses/" + businessId)).cookie(cookie).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(cookie).content("{\"isSuspended\":true}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT is_suspended FROM businesses WHERE id = ?", Boolean.class, businessId)).isFalse();
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(get("/api/businesses")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void creatingABusinessCreatesItsOwnerInOneGoAndAuditsBoth() throws Exception {
        String ownerEmail = TestData.uniqueEmail("new.owner");
        String body = "{\"name\":\"  Acme Tools  \",\"owner\":{\"firstName\":\"Olivia\",\"lastName\":\"Owner\",\"email\":\"" + ownerEmail.toUpperCase()
                + "\",\"employeeCode\":\"OWN-1\"}}";
        String response = mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("Business created."))
                .andReturn().getResponse().getContentAsString();
        String businessId = JsonPath.read(response, "$.data.id");
        String ownerId = JsonPath.read(response, "$.data.ownerId");

        Map<String, Object> business = jdbcTemplate.queryForMap("SELECT name, time_zone, stale_workday_hours, is_suspended FROM businesses WHERE id = ?::uuid", businessId);
        assertThat(business).containsEntry("name", "Acme Tools").containsEntry("time_zone", "Asia/Kolkata").containsEntry("stale_workday_hours", 14)
                .containsEntry("is_suspended", false);
        Map<String, Object> owner = jdbcTemplate.queryForMap("SELECT business_id::text AS business_id, email, role::text AS role, is_active, manager_id, employee_code "
                + "FROM users WHERE id = ?::uuid", ownerId);
        assertThat(owner).containsEntry("business_id", businessId).containsEntry("email", ownerEmail).containsEntry("role", "ADMIN")
                .containsEntry("is_active", true).containsEntry("employee_code", "OWN-1");
        assertThat(owner.get("manager_id")).isNull();

        List<Map<String, Object>> entries = auditEntries(businessId);
        assertThat(entries).extracting(entry -> entry.get("action")).containsExactlyInAnyOrder("business.created", "employee.created");
        Map<String, Object> created = entries.stream().filter(entry -> "business.created".equals(entry.get("action"))).findFirst().orElseThrow();
        assertThat(created.get("actor_id")).isEqualTo(superAdminId);
        assertThat(created.get("entity_id")).isEqualTo(businessId);
        assertThat((String) created.get("after_json")).contains("\"name\": \"Acme Tools\"").contains("\"timeZone\": \"Asia/Kolkata\"");
        assertThat(created.get("before_json")).isNull();
        Map<String, Object> ownerEntry = entries.stream().filter(entry -> "employee.created".equals(entry.get("action"))).findFirst().orElseThrow();
        assertThat(ownerEntry.get("subject_id").toString()).isEqualTo(ownerId);
        assertThat((String) ownerEntry.get("after_json")).contains("\"role\": \"ADMIN\"").contains(ownerEmail);

        // the owner signs in with the emailed code and sees their business
        mockMvc.perform(get("/api/business").cookie(SignIn.session(mockMvc, ownerEmail).accessCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Acme Tools"));
    }

    @Test
    void creatingTakesATimeZoneAndRefusesBadInput() throws Exception {
        String response = mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(
                "{\"name\":\"Zoned\",\"timeZone\":\"europe/london\",\"owner\":{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("zoned") + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(jdbcTemplate.queryForObject("SELECT time_zone FROM businesses WHERE id = ?::uuid", String.class, (String) JsonPath.read(response, "$.data.id")))
                .isEqualTo("Europe/London");

        long businessesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM businesses", Long.class);
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(
                "{\"name\":\" \",\"timeZone\":\"Mars/Base\",\"owner\":{\"firstName\":\"A\",\"email\":\"not-an-email\",\"employeeCode\":\"has space\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Business name is required."))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("name", "timeZone", "owner.lastName", "owner.email", "owner.employeeCode")));
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content("{\"name\":\"No owner\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItems("owner.firstName", "owner.email")));
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content("not json")).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses", Long.class)).isEqualTo(businessesBefore);
    }

    @Test
    void anEmailUsedInAnyBusinessOrByTheSuperAdminIs409() throws Exception {
        String takenEmail = TestData.uniqueEmail("taken");
        testData.createUser(testData.createBusiness("Elsewhere " + UUID.randomUUID()), "EMPLOYEE", takenEmail, null);
        long businessesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM businesses", Long.class);
        for (String email : List.of(takenEmail, takenEmail.toUpperCase())) {
            mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(
                    "{\"name\":\"Duplicate\",\"owner\":{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + email + "\"}}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        }
        String superAdminEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, superAdminId);
        mockMvc.perform(SignIn.fromWebApp(post("/api/businesses")).cookie(superAdminCookie).content(
                "{\"name\":\"Duplicate\",\"owner\":{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + superAdminEmail + "\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses", Long.class)).isEqualTo(businessesBefore);
    }

    @Test
    void theDetailShowsTheOwnerAndTheCounts() throws Exception {
        String ownerEmail = TestData.uniqueEmail("detail.owner");
        String businessId = createBusiness("Detail Co", ownerEmail);
        UUID ownerId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, ownerEmail);
        testData.createUser(UUID.fromString(businessId), "EMPLOYEE", TestData.uniqueEmail("detail.one"), ownerId);
        testData.deactivate(testData.createUser(UUID.fromString(businessId), "EMPLOYEE", TestData.uniqueEmail("detail.two"), null));

        mockMvc.perform(get("/api/businesses/" + businessId).cookie(superAdminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(businessId))
                .andExpect(jsonPath("$.data.name").value("Detail Co"))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.staleWorkdayHours").value(14))
                .andExpect(jsonPath("$.data.displayTimeZones[0]").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.isSuspended").value(false))
                .andExpect(jsonPath("$.data.isLinkedToJurisone").value(false))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.owner.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.data.owner.email").value(ownerEmail))
                .andExpect(jsonPath("$.data.owner.isActive").value(true))
                .andExpect(jsonPath("$.data.owner.lastLoginAt").value(nullValue()))
                .andExpect(jsonPath("$.data.employeeCount").value(3))
                .andExpect(jsonPath("$.data.activeEmployeeCount").value(2));
        mockMvc.perform(get("/api/businesses/" + UUID.randomUUID()).cookie(superAdminCookie)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/businesses/not-a-uuid").cookie(superAdminCookie)).andExpect(status().isBadRequest());
    }

    @Test
    void theSuperAdminChangesTheSettingsWithAnAuditEntry() throws Exception {
        String businessId = createBusiness("Settings Co", TestData.uniqueEmail("settings.owner"));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/businesses/" + businessId)).cookie(superAdminCookie)
                        .content("{\"name\":\"Settings Co Ltd\",\"staleWorkdayHours\":10,\"displayTimeZones\":[\"europe/london\",\"Europe/London\",\"UTC\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Settings Co Ltd"))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.data.staleWorkdayHours").value(10))
                .andExpect(jsonPath("$.data.displayTimeZones.length()").value(2))
                .andExpect(jsonPath("$.data.displayTimeZones[0]").value("Europe/London"))
                .andExpect(jsonPath("$.data.displayTimeZones[1]").value("UTC"));
        Map<String, Object> entry = auditEntries(businessId).stream().filter(row -> "business.updated".equals(row.get("action"))).findFirst().orElseThrow();
        assertThat(entry.get("actor_id")).isEqualTo(superAdminId);
        JsonNode before = objectMapper.readTree((String) entry.get("before_json"));
        JsonNode after = objectMapper.readTree((String) entry.get("after_json"));
        assertThat(before.get("name").asText()).isEqualTo("Settings Co");
        assertThat(before.get("staleWorkdayHours").asInt()).isEqualTo(14);
        assertThat(after.get("name").asText()).isEqualTo("Settings Co Ltd");
        assertThat(after.get("staleWorkdayHours").asInt()).isEqualTo(10);

        // nothing changed: no new audit entry; bad values: 400 and nothing stored
        mockMvc.perform(SignIn.fromWebApp(patch("/api/businesses/" + businessId)).cookie(superAdminCookie).content("{\"name\":\"Settings Co Ltd\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/businesses/" + businessId)).cookie(superAdminCookie)
                        .content("{\"staleWorkdayHours\":49,\"timeZone\":\"Nowhere/City\",\"name\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("staleWorkdayHours", "timeZone", "name")));
        assertThat(auditEntries(businessId)).filteredOn(row -> "business.updated".equals(row.get("action"))).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT stale_workday_hours FROM businesses WHERE id = ?::uuid", Integer.class, businessId)).isEqualTo(10);
        mockMvc.perform(SignIn.fromWebApp(patch("/api/businesses/" + UUID.randomUUID())).cookie(superAdminCookie).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void suspendingAndReactivatingAreAuditedWithTheReason() throws Exception {
        String businessId = createBusiness("Suspend Co", TestData.uniqueEmail("suspend.owner"));
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(superAdminCookie)
                        .content("{\"isSuspended\":true,\"reason\":\"Invoice unpaid\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Business suspended."))
                .andExpect(jsonPath("$.data.isSuspended").value(true));
        // the same state again: idempotent, no second entry
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isSuspended").value(false));
        List<Map<String, Object>> entries = auditEntries(businessId).stream()
                .filter(row -> row.get("action").toString().startsWith("business.s") || row.get("action").toString().startsWith("business.r")).toList();
        assertThat(entries).extracting(row -> row.get("action")).containsExactly("business.suspended", "business.reactivated");
        assertThat(entries.get(0).get("reason")).isEqualTo("Invoice unpaid");
        assertThat((String) entries.get(0).get("after_json")).contains("\"isSuspended\": true");

        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(superAdminCookie).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("isSuspended"));
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + UUID.randomUUID() + "/status")).cookie(superAdminCookie).content("{\"isSuspended\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListSearchesFiltersSortsAndPages() throws Exception {
        String token = "Listing" + UUID.randomUUID().toString().substring(0, 8);
        String alphaId = createBusiness(token + " Alpha", TestData.uniqueEmail("list.alpha"));
        String betaId = createBusiness(token + " Beta", TestData.uniqueEmail("list.beta"));
        String gammaId = createBusiness(token + " Gamma", TestData.uniqueEmail("list.gamma"));
        testData.suspendBusiness(UUID.fromString(betaId));

        mockMvc.perform(get("/api/businesses").param("search", token).cookie(superAdminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(25))
                .andExpect(jsonPath("$.data.rows[0].id").value(alphaId))
                .andExpect(jsonPath("$.data.rows[0].ownerName").value("Olivia Owner"))
                .andExpect(jsonPath("$.data.rows[0].employeeCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].isSuspended").value(false));
        mockMvc.perform(get("/api/businesses").param("search", token).param("sort", "name").param("sortDirection", "desc")
                        .param("page", "2").param("pageSize", "2").cookie(superAdminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].id").value(alphaId));
        mockMvc.perform(get("/api/businesses").param("search", token).param("status", "suspended").cookie(superAdminCookie))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].id").value(betaId));
        mockMvc.perform(get("/api/businesses").param("search", token).param("status", "active").cookie(superAdminCookie))
                .andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.rows[1].id").value(gammaId));
        // the owner's email is searched too
        mockMvc.perform(get("/api/businesses").param("search", "list.gamma").cookie(superAdminCookie))
                .andExpect(jsonPath("$.data.rows[*].id").value(hasItems(gammaId)));
        // unknown values are 400
        mockMvc.perform(get("/api/businesses").param("sort", "owner").param("status", "closed").param("page", "0").param("pageSize", "500")
                        .param("sortDirection", "up").cookie(superAdminCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("sort", "status", "page", "pageSize", "sortDirection")));
    }
}

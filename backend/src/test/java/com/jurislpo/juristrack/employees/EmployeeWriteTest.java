package com.jurislpo.juristrack.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.EmployeeService;
import com.jurislpo.juristrack.service.EmployeeService.EmployeeStatusChange;
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

/** The owner adds, edits, deactivates and reactivates employees: {@code POST}, {@code PATCH /{id}}, {@code PUT /{id}/status}; audited. */
class EmployeeWriteTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired EmployeeService employeeService;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID managerId;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Write Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("write.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", "W-001");
        managerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("write.manager"), ownerId, "Manish", "Manager", "W-002");
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    private String createEmployee(String json) throws Exception {
        String response = mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.id");
    }

    private Map<String, Object> auditEntry(String action, Object subjectId) {
        return jdbcTemplate.queryForMap("SELECT business_id, actor_id, entity_type, entity_id, \"before\"::text AS before_json, \"after\"::text AS after_json, reason "
                + "FROM audit_logs WHERE action = ? AND subject_id = ?::uuid ORDER BY created_at DESC LIMIT 1", action, subjectId.toString());
    }

    private int auditCount(String action, Object subjectId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND subject_id = ?::uuid", Integer.class, action, subjectId.toString());
    }

    @Test
    void theOwnerAddsAnEmployeeWhoCanSignInAtOnce() throws Exception {
        String email = TestData.uniqueEmail("new.employee");
        String response = mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie).content(
                        "{\"employeeCode\":\" W-010 \",\"firstName\":\" Asha \",\"lastName\":\"Rao\",\"email\":\"" + email.toUpperCase()
                                + "\",\"role\":\"EMPLOYEE\",\"managerId\":\"" + managerId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Employee added."))
                .andReturn().getResponse().getContentAsString();
        String employeeId = JsonPath.read(response, "$.data.id");
        mockMvc.perform(get("/api/v1/employees/" + employeeId).cookie(ownerCookie))
                .andExpect(jsonPath("$.data.employeeCode").value("W-010"))
                .andExpect(jsonPath("$.data.firstName").value("Asha"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.managerName").value("Manish Manager"));
        Map<String, Object> entry = auditEntry("employee.created", employeeId);
        assertThat(entry).containsEntry("business_id", businessId).containsEntry("actor_id", ownerId).containsEntry("entity_type", "user")
                .containsEntry("entity_id", employeeId);
        assertThat((String) entry.get("after_json")).contains("\"email\": \"" + email + "\"").contains("\"managerId\": \"" + managerId + "\"");
        assertThat(entry.get("before_json")).isNull();

        mockMvc.perform(get("/api/v1/me").cookie(SignIn.session(mockMvc, email).accessCookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.managerName").value("Manish Manager"));

        // the role defaults to EMPLOYEE; code and manager are optional
        String minimalId = createEmployee("{\"firstName\":\"Ben\",\"lastName\":\"Carter\",\"email\":\"" + TestData.uniqueEmail("ben") + "\"}");
        mockMvc.perform(get("/api/v1/employees/" + minimalId).cookie(ownerCookie))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.employeeCode").value(nullValue()))
                .andExpect(jsonPath("$.data.managerId").value(nullValue()));
    }

    @Test
    void badInputIs400() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"employeeCode\":\"bad code!\",\"firstName\":\"\",\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("employeeCode", "firstName", "lastName", "email")));
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"" + "x".repeat(61) + "\",\"lastName\":\"Rao\",\"email\":\"" + TestData.uniqueEmail("long") + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("First name must be at most 60 characters."));
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("role") + "\",\"role\":\"BOSS\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("manager") + "\",\"managerId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, businessId)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    void ownersAndSuperAdminsAreNotCreatedHere(String role) throws Exception {
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("role") + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));
    }

    @Test
    void emailsAreUniqueEverywhereAndCodesInsideTheBusiness() throws Exception {
        String otherBusinessEmail = TestData.uniqueEmail("elsewhere");
        UUID otherBusinessId = testData.createBusiness("Elsewhere " + UUID.randomUUID());
        testData.createUser(otherBusinessId, "EMPLOYEE", otherBusinessEmail, null, "Else", "Where", "W-500");
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + otherBusinessEmail.toUpperCase() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                        .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("code") + "\",\"employeeCode\":\"W-002\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_IN_USE"));
        // another business's code is free here
        createEmployee("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("code") + "\",\"employeeCode\":\"W-500\"}");

        // the same on an edit
        String employeeId = createEmployee("{\"firstName\":\"C\",\"lastName\":\"D\",\"email\":\"" + TestData.uniqueEmail("edit") + "\"}");
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"email\":\"" + otherBusinessEmail + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"employeeCode\":\"W-001\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_IN_USE"));
        assertThat(auditCount("employee.updated", employeeId)).isZero();
    }

    @Test
    void editingChangesOnlyTheFieldsSentAndAuditsBeforeAndAfter() throws Exception {
        String email = TestData.uniqueEmail("patch");
        String employeeId = createEmployee("{\"employeeCode\":\"W-020\",\"firstName\":\"Chen\",\"lastName\":\"Li\",\"email\":\"" + email
                + "\",\"managerId\":\"" + managerId + "\"}");
        String newEmail = TestData.uniqueEmail("patched");
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie)
                        .content("{\"lastName\":\"Lee\",\"email\":\"" + newEmail + "\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Employee saved."))
                .andExpect(jsonPath("$.data.firstName").value("Chen"))
                .andExpect(jsonPath("$.data.lastName").value("Lee"))
                .andExpect(jsonPath("$.data.email").value(newEmail))
                .andExpect(jsonPath("$.data.role").value("MANAGER"))
                .andExpect(jsonPath("$.data.employeeCode").value("W-020"))
                .andExpect(jsonPath("$.data.managerId").value(managerId.toString()));
        Map<String, Object> entry = auditEntry("employee.updated", employeeId);
        assertThat((String) entry.get("before_json")).contains("\"lastName\": \"Li\"").contains("\"role\": \"EMPLOYEE\"");
        assertThat((String) entry.get("after_json")).contains("\"lastName\": \"Lee\"").contains("\"role\": \"MANAGER\"");

        // null clears the code and the manager; nothing sent changes nothing and writes no audit entry
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"employeeCode\":null,\"managerId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeCode").value(nullValue()))
                .andExpect(jsonPath("$.data.managerId").value(nullValue()));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{}")).andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"lastName\":\"Lee\"}")).andExpect(status().isOk());
        assertThat(auditCount("employee.updated", employeeId)).isEqualTo(2);

        // a name or role cannot be cleared
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"firstName\":null,\"role\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItems("firstName", "role")));
    }

    @Test
    void theOwnersRoleIsNotChangedHereButTheirDetailsAre() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + ownerId)).cookie(ownerCookie).content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + managerId)).cookie(ownerCookie).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ROLE_NOT_ASSIGNABLE"));
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + ownerId)).cookie(ownerCookie).content("{\"firstName\":\"Olive\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.firstName").value("Olive")).andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void deactivatingEndsEverySessionAndBlocksSignInUntilReactivated() throws Exception {
        String email = TestData.uniqueEmail("leaver");
        String employeeId = createEmployee("{\"firstName\":\"Lee\",\"lastName\":\"Ver\",\"email\":\"" + email + "\"}");
        SessionCookies firstDevice = SignIn.session(mockMvc, email);
        SessionCookies secondDevice = SignIn.session(mockMvc, email);

        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie)
                        .content("{\"isActive\":false,\"reason\":\"Left the company\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Employee deactivated."))
                .andExpect(jsonPath("$.data.isActive").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ?::uuid AND revoked_at IS NULL", Integer.class, employeeId)).isZero();
        assertThat(jdbcTemplate.queryForList("SELECT revoked_reason FROM sessions WHERE user_id = ?::uuid", String.class, employeeId)).containsOnly("user_inactive");
        mockMvc.perform(get("/api/v1/me").cookie(firstDevice.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").cookie(secondDevice.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/session-refreshes")).cookie(secondDevice.refreshCookie())).andExpect(status().isUnauthorized());
        int codesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email);
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email)).isEqualTo(codesBefore);

        Map<String, Object> entry = auditEntry("employee.deactivated", employeeId);
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("reason", "Left the company");
        assertThat((String) entry.get("before_json")).contains("\"isActive\": true");
        assertThat((String) entry.get("after_json")).contains("\"isActive\": false").contains("\"endedSessions\": 2");

        // deactivating again changes nothing; reactivating lets them sign in again
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        assertThat(auditCount("employee.deactivated", employeeId)).isEqualTo(1);
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + employeeId + "/status")).cookie(ownerCookie).content("{\"isActive\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isActive").value(true));
        assertThat(auditCount("employee.reactivated", employeeId)).isEqualTo(1);
        rateLimiter.clear();
        mockMvc.perform(get("/api/v1/me").cookie(SignIn.session(mockMvc, email).accessCookie())).andExpect(status().isOk());
    }

    @Test
    void theOwnerCannotDeactivateThemselfButAnotherOwner() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + ownerId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_SELF"));
        UUID secondOwnerId = testData.createUser(businessId, "ADMIN", TestData.uniqueEmail("second.owner"), null);
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + secondOwnerId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isActive").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, ownerId)).isTrue();
    }

    @Test
    void theLastActiveOwnerIsNeverDeactivated() {
        // Through the API the acting owner is always another active ADMIN, so this guard is a safety net: here the actor is not an
        // active owner of the business (as a JurisOne integration call or a future platform tool could be), and the only active ADMIN stays.
        CurrentUser actor = new CurrentUser(UUID.randomUUID(), businessId, "Write Co", Role.ADMIN, "Someone", "Else", "else@test.juristrack.test",
                AuthenticationSource.INTEGRATION, null);
        assertThatThrownBy(() -> employeeService.setStatus(actor, ownerId, new EmployeeStatusChange(false, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LAST_ACTIVE_OWNER"));
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, ownerId)).isTrue();
    }

    @Test
    void statusNeedsIsActive() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + managerId + "/status")).cookie(ownerCookie).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("isActive"));
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + managerId + "/status")).cookie(ownerCookie)
                        .content("{\"isActive\":false,\"reason\":\"" + "r".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void anotherBusinessesEmployeeIs404ForEveryWrite() throws Exception {
        UUID otherBusinessId = testData.createBusiness("Isolated " + UUID.randomUUID());
        UUID strangerId = testData.createUser(otherBusinessId, "EMPLOYEE", TestData.uniqueEmail("stranger"), null, "Stranger", "Danger", null);
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + strangerId)).cookie(ownerCookie).content("{\"firstName\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + strangerId + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForMap("SELECT first_name, is_active FROM users WHERE id = ?", strangerId))
                .containsEntry("first_name", "Stranger").containsEntry("is_active", true);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ?", Integer.class, strangerId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "EMPLOYEE", "SUPER_ADMIN"})
    void onlyTheOwnerWrites(String role) throws Exception {
        String email = TestData.uniqueEmail(role.toLowerCase());
        testData.createUser("SUPER_ADMIN".equals(role) ? null : businessId, role, email, null);
        Cookie cookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(cookie)
                .content("{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + TestData.uniqueEmail("x") + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + managerId)).cookie(cookie).content("{\"firstName\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + managerId + "/status")).cookie(cookie).content("{\"isActive\":false}"))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForMap("SELECT first_name, is_active FROM users WHERE id = ?", managerId))
                .containsEntry("first_name", "Manish").containsEntry("is_active", true);
    }

    @Test
    void signedOutIs401() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + managerId)).content("{}")).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + managerId + "/status")).content("{}")).andExpect(status().isUnauthorized());
    }
}

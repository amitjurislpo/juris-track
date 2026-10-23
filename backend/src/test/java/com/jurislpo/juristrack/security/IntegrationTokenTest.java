package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Embedded in JurisOne: JurisOne's signed token authenticates the call, and the business and user are provisioned just in time
 * (created, linked by email, kept in line with the token's name, role and manager). Refused tokens answer 401 without details.
 */
class IntegrationTokenTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    TestData testData;
    UUID jurisoneBusinessId;
    String businessName;

    @BeforeEach
    void setUp() {
        testData = new TestData(jdbcTemplate);
        jurisoneBusinessId = UUID.randomUUID();
        businessName = "JurisOne Business " + jurisoneBusinessId.toString().substring(0, 8);
    }

    ResultActions callMe(String token) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
    }

    Map<String, Object> userRow(UUID jurisoneEmployeeId) {
        return jdbcTemplate.queryForMap("SELECT id, business_id, first_name, last_name, email, role::text AS role, manager_id, is_active "
                + "FROM users WHERE jurisone_employee_id = ?", jurisoneEmployeeId);
    }

    @Test
    void aValidTokenProvisionsTheBusinessAndTheUserJustInTime() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(employee.email()))
                .andExpect(jsonPath("$.data.firstName").value("Asha"))
                .andExpect(jsonPath("$.data.lastName").value("Rao"))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.businessName").value(businessName));

        Map<String, Object> business = jdbcTemplate.queryForMap("SELECT id, name, time_zone FROM businesses WHERE jurisone_business_id = ?", jurisoneBusinessId);
        assertThat(business.get("name")).isEqualTo(businessName);
        assertThat(business.get("time_zone")).isEqualTo("Asia/Kolkata");
        Map<String, Object> user = userRow(employee.employeeId());
        assertThat(user.get("business_id")).isEqualTo(business.get("id"));
        assertThat(jdbcTemplate.queryForList("SELECT action FROM audit_logs WHERE business_id = ? ORDER BY created_at", String.class, business.get("id")))
                .containsExactlyInAnyOrder("integration.business_provisioned", "integration.user_provisioned");

        // the second call finds both: nothing new is created and nothing is audited
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id = ?", Integer.class, employee.employeeId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ?", Integer.class, business.get("id"))).isEqualTo(2);
    }

    @Test
    void theTokenKeepsNameRoleAndBusinessNameInLine() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "MANAGER");
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(jsonPath("$.data.role").value("MANAGER"));

        // JurisOne removed the manager role and the person changed their name; the business was renamed
        Employee changed = new Employee(employee.employeeId(), employee.email(), "Asha Rao Menon", jurisoneBusinessId, businessName + " Ltd", null, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(changed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.lastName").value("Rao Menon"))
                .andExpect(jsonPath("$.data.businessName").value(businessName + " Ltd"));
        assertThat(userRow(employee.employeeId()).get("role")).isEqualTo("EMPLOYEE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.user_updated' AND subject_id = ?", Integer.class,
                userRow(employee.employeeId()).get("id"))).isEqualTo(1);

        // an unknown role is EMPLOYEE; ADMIN only when JurisOne sends it, and a token never takes it away again
        callMe(IntegrationTokens.tokenFor(changed.withTrackRole("MANAGER"))).andExpect(jsonPath("$.data.role").value("MANAGER"));
        callMe(IntegrationTokens.tokenFor(changed.withTrackRole("HR"))).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        callMe(IntegrationTokens.tokenFor(changed, claims -> claims.claim("trackRole", null))).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        callMe(IntegrationTokens.tokenFor(changed.withTrackRole("ADMIN"))).andExpect(jsonPath("$.data.role").value("ADMIN"));
        callMe(IntegrationTokens.tokenFor(changed.withTrackRole("HR"))).andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    void theManagerIsResolvedInsideTheBusinessAndNeverTheUserThemselves() throws Exception {
        Employee manager = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "MANAGER");
        Employee report = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE").withManager(manager.employeeId());

        // the manager has not used JurisTrack yet: unknown, so no manager for now
        callMe(IntegrationTokens.tokenFor(report)).andExpect(status().isOk());
        assertThat(userRow(report.employeeId()).get("manager_id")).isNull();

        callMe(IntegrationTokens.tokenFor(manager)).andExpect(status().isOk());
        callMe(IntegrationTokens.tokenFor(report)).andExpect(status().isOk());
        assertThat(userRow(report.employeeId()).get("manager_id")).isEqualTo(userRow(manager.employeeId()).get("id"));

        // a manager of another JurisOne business is never resolved
        Employee outsider = IntegrationTokens.newEmployee(UUID.randomUUID(), "Other " + UUID.randomUUID(), "MANAGER");
        callMe(IntegrationTokens.tokenFor(outsider)).andExpect(status().isOk());
        callMe(IntegrationTokens.tokenFor(report.withManager(outsider.employeeId()))).andExpect(status().isOk());
        assertThat(userRow(report.employeeId()).get("manager_id")).isNull();

        // the user themselves is never their own manager
        callMe(IntegrationTokens.tokenFor(report.withManager(report.employeeId()))).andExpect(status().isOk());
        assertThat(userRow(report.employeeId()).get("manager_id")).isNull();
    }

    @Test
    void aStandaloneBusinessIsNeverAdoptedByEmail() throws Exception {
        // a standalone owner planted the email of somebody at a JurisOne company that has not opened JurisTrack yet
        String plantedName = "Mallory Co " + UUID.randomUUID();
        UUID standaloneBusinessId = testData.createBusiness(plantedName);
        String email = TestData.uniqueEmail("planted");
        UUID plantedUserId = testData.createUser(standaloneBusinessId, "EMPLOYEE", email, null);

        Employee victim = new Employee(UUID.randomUUID(), email, "Hema Raman", jurisoneBusinessId, businessName, null, "MANAGER");
        callMe(IntegrationTokens.tokenFor(victim)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForMap("SELECT name, jurisone_business_id FROM businesses WHERE id = ?", standaloneBusinessId))
                .containsEntry("name", plantedName).containsEntry("jurisone_business_id", null);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_employee_id FROM users WHERE id = ?", UUID.class, plantedUserId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.user_refused' AND subject_id = ? "
                + "AND reason = 'email_of_another_business'", Integer.class, plantedUserId)).isEqualTo(1);

        // the rest of the JurisOne company still gets its own new business
        Employee colleague = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(colleague)).andExpect(status().isOk()).andExpect(jsonPath("$.data.businessName").value(businessName));
        assertThat(userRow(colleague.employeeId()).get("business_id")).isNotEqualTo(standaloneBusinessId);
    }

    @Test
    void anEmailOfAnotherBusinessOrOfTheSuperAdminIsRefused() throws Exception {
        String superAdminEmail = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        Employee pretender = new Employee(UUID.randomUUID(), superAdminEmail, "Not Admin", jurisoneBusinessId, businessName, null, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(pretender)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // a user who already belongs to another JurisOne business
        Employee first = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(first)).andExpect(status().isOk());
        Employee sameEmailElsewhere = new Employee(UUID.randomUUID(), first.email(), "Asha Rao", UUID.randomUUID(), "Elsewhere", null, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(sameEmailElsewhere)).andExpect(status().isForbidden());
    }

    @Test
    void aReplayedTokenIsRefusedAndAudited() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        String token = IntegrationTokens.tokenFor(employee);
        callMe(token).andExpect(status().isOk());
        callMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.token_rejected' AND reason = 'replayed_token'",
                Integer.class)).isPositive();
    }

    @Test
    void aDeactivatedUserIsRefusedWith403() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isOk());
        testData.deactivate((UUID) userRow(employee.employeeId()).get("id"));
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refusedTokensAnswer401WithoutDetails() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        Instant past = Instant.now().minusSeconds(3600);
        String expired = IntegrationTokens.tokenFor(employee, claims -> claims.issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(300))));
        String wrongAudience = IntegrationTokens.tokenFor(employee, claims -> claims.audience("someone-else"));
        String wrongIssuer = IntegrationTokens.tokenFor(employee, claims -> claims.issuer("http://evil.test"));
        String otherKey = IntegrationTokens.sign(IntegrationTokens.claimsOf(employee).build(), IntegrationTokens.rsaKeyPair());
        String missingBusiness = IntegrationTokens.tokenFor(employee, claims -> claims.claim("businessId", null));
        String valid = IntegrationTokens.tokenFor(employee);
        String tamperedPayload = valid.split("\\.")[0] + "." + IntegrationTokens.tokenFor(employee.withName("Mallory Evil")).split("\\.")[1] + "." + valid.split("\\.")[2];
        for (String token : new String[] {expired, wrongAudience, wrongIssuer, otherKey, missingBusiness, tamperedPayload, "not-a-jwt"}) {
            callMe(token).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id = ?", Integer.class, employee.employeeId())).isZero();
        // within the clock skew (30 s) a just-expired token is still accepted
        Instant justNow = Instant.now().minusSeconds(310);
        callMe(IntegrationTokens.tokenFor(employee, claims -> claims.issueTime(Date.from(justNow)).expirationTime(Date.from(justNow.plusSeconds(300)))))
                .andExpect(status().isOk());
    }

    @Test
    void bearerRequestsNeedNoOriginAndHaveNoSession() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN");
        // one token per request: tokens are single-use
        mockMvc.perform(patch("/api/business").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staleWorkdayHours\": 10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.staleWorkdayHours").value(10));
        mockMvc.perform(delete("/api/auth/sessions/current").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ?", Integer.class, userRow(employee.employeeId()).get("id")))
                .isZero();
    }

    @Test
    void anIntegrationUserSeesOnlyTheirOwnBusiness() throws Exception {
        Employee owner = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN");
        Employee colleague = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(colleague)).andExpect(status().isOk());
        UUID otherBusinessUserId = testData.createUser(testData.createBusiness("Other " + UUID.randomUUID()), "EMPLOYEE", TestData.uniqueEmail("other"), null);
        mockMvc.perform(get("/api/employees/" + userRow(colleague.employeeId()).get("id")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/employees/" + otherBusinessUserId).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSuspendedBusinessIsRefusedWith403BusinessSuspended() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isOk());
        testData.suspendBusiness((UUID) userRow(employee.employeeId()).get("business_id"));
        callMe(IntegrationTokens.tokenFor(employee)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
    }
}

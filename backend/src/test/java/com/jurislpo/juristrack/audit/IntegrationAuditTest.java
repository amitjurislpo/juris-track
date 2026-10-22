package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.security.EmbeddedIntegrationTest;
import com.jurislpo.juristrack.security.IntegrationTokens;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import com.jurislpo.juristrack.service.AuditAggregation;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Embedded in JurisOne: entries of Bearer requests have the source INTEGRATION; provisioning, a renamed business, a refused user and a
 * refused token (reason code only, never the token; once per reason, IP address and window) are audited.
 */
class IntegrationAuditTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuditAggregation auditAggregation;
    TestData testData;
    UUID jurisoneBusinessId;
    String businessName;

    @BeforeEach
    void setUp() {
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        jurisoneBusinessId = UUID.randomUUID();
        businessName = "JurisOne Audit " + jurisoneBusinessId.toString().substring(0, 8);
    }

    private UUID userIdOf(Employee employee) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE jurisone_employee_id = ?", UUID.class, employee.employeeId());
    }

    @Test
    void provisioningAndWritesWithTheTokenHaveTheSourceIntegration() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/me")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee))).andExpect(status().isOk());
        UUID userId = userIdOf(employee);
        assertThat(trace.entry("integration.business_provisioned")).containsEntry("source", "INTEGRATION").containsEntry("category", "integration");
        assertThat(trace.entry("integration.user_provisioned")).containsEntry("source", "INTEGRATION").containsEntry("subject_id", userId)
                .containsEntry("actor_role", "EMPLOYEE");

        AuditTrace writeTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(writeTrace.on(post("/api/me/time-events")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"START\"}")).andExpect(status().isCreated());
        assertThat(writeTrace.entry("time.workday_started")).containsEntry("source", "INTEGRATION").containsEntry("actor_id", userId);

        // JurisOne renamed the business: the token's name is taken over and audited
        Employee renamed = new Employee(employee.employeeId(), employee.email(), employee.name(), jurisoneBusinessId, businessName + " Ltd", null, "EMPLOYEE");
        AuditTrace renameTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(renameTrace.on(get("/api/me")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(renamed))).andExpect(status().isOk());
        Map<String, Object> entry = renameTrace.entry("integration.business_updated");
        assertThat((String) entry.get("before_json")).contains(businessName);
        assertThat((String) entry.get("after_json")).contains(businessName + " Ltd");
    }

    @Test
    void aRefusedTokenIsAuditedByReasonOncePerWindowWithoutTheToken() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        String wrongAudienceToken = IntegrationTokens.tokenFor(employee, claims -> claims.audience("someone-else"));
        String ipAddress = AuditTrace.newIpAddress();
        AuditTrace firstTrace = new AuditTrace(jdbcTemplate, ipAddress);
        mockMvc.perform(firstTrace.on(get("/api/me")).header("Authorization", "Bearer " + wrongAudienceToken)).andExpect(status().isUnauthorized());
        Map<String, Object> entry = firstTrace.entry("integration.token_rejected");
        assertThat(entry).containsEntry("reason", "wrong_audience").containsEntry("outcome", "DENIED").containsEntry("source", "INTEGRATION");
        assertThat(entry.get("business_id")).isNull();
        assertThat(entry.toString()).doesNotContain(wrongAudienceToken).doesNotContain(wrongAudienceToken.split("\\.")[1]);

        AuditTrace repeatTrace = new AuditTrace(jdbcTemplate, ipAddress);
        mockMvc.perform(repeatTrace.on(get("/api/me")).header("Authorization", "Bearer " + wrongAudienceToken)).andExpect(status().isUnauthorized());
        assertThat(repeatTrace.entries()).isEmpty();
        AuditTrace otherReasonTrace = new AuditTrace(jdbcTemplate, ipAddress);
        mockMvc.perform(otherReasonTrace.on(get("/api/me")).header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        assertThat(otherReasonTrace.entry("integration.token_rejected")).containsEntry("reason", "malformed_token");
    }

    @Test
    void aDeactivatedUserAndASuspendedBusinessAreAuditedAsDenied() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee))).andExpect(status().isOk());
        UUID userId = userIdOf(employee);
        UUID businessId = jdbcTemplate.queryForObject("SELECT business_id FROM users WHERE id = ?", UUID.class, userId);

        testData.deactivate(userId);
        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(get("/api/me")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)))
                .andExpect(status().isForbidden());
        assertThat(refusedTrace.entry("integration.user_refused")).containsEntry("reason", "user_deactivated").containsEntry("subject_id", userId)
                .containsEntry("business_id", businessId).containsEntry("outcome", "DENIED");
        jdbcTemplate.update("UPDATE users SET is_active = true WHERE id = ?", userId);

        testData.suspendBusiness(businessId);
        AuditTrace suspendedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(suspendedTrace.on(get("/api/me/time")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)))
                .andExpect(status().isForbidden());
        assertThat(suspendedTrace.entry("security.suspended_business_request")).containsEntry("business_id", businessId).containsEntry("actor_id", userId)
                .containsEntry("source", "INTEGRATION");
        AuditTrace againTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(againTrace.on(get("/api/me/time")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)))
                .andExpect(status().isForbidden());
        assertThat(againTrace.entries()).isEmpty();
    }

    @Test
    void anEmailOfAnotherBusinessIsRecordedInThatBusiness() throws Exception {
        UUID standaloneBusinessId = testData.createBusiness("Taken Co " + UUID.randomUUID());
        String email = TestData.uniqueEmail("taken");
        UUID standaloneUserId = testData.createUser(standaloneBusinessId, "EMPLOYEE", email, null);
        // the standalone business is linked by the first token, then the same email arrives from another JurisOne business
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(new Employee(UUID.randomUUID(), email, "Ravi Kumar",
                jurisoneBusinessId, businessName, null, "EMPLOYEE")))).andExpect(status().isOk());
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(get("/api/me")).header("Authorization", "Bearer " + IntegrationTokens.tokenFor(new Employee(UUID.randomUUID(), email,
                "Ravi Kumar", UUID.randomUUID(), "Elsewhere", null, "EMPLOYEE")))).andExpect(status().isForbidden());
        assertThat(trace.entry("integration.user_refused")).containsEntry("reason", "email_of_another_business").containsEntry("subject_id", standaloneUserId)
                .containsEntry("business_id", standaloneBusinessId);
    }
}

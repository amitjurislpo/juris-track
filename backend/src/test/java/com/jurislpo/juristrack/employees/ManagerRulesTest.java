package com.jurislpo.juristrack.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The reporting-line rules (422): a manager is an active MANAGER or ADMIN of the same business, never the employee, never someone
 * under the employee (no loops); a MANAGER with active reports does not become an EMPLOYEE.
 *
 * <p>Reporting line in every test: owner &lt;- director (MANAGER) &lt;- lead (MANAGER) &lt;- worker (EMPLOYEE).
 */
class ManagerRulesTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID directorId;
    UUID leadId;
    UUID workerId;
    Cookie ownerCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Line Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("line.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        directorId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("director"), ownerId);
        leadId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("lead"), directorId);
        workerId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("worker"), leadId);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
    }

    private ResultActions setManager(UUID employeeId, Object managerId) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + employeeId)).cookie(ownerCookie).content("{\"managerId\":\"" + managerId + "\"}"));
    }

    private ResultActions createWithManager(Object managerId) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(ownerCookie)
                .content("{\"firstName\":\"New\",\"lastName\":\"Hire\",\"email\":\"" + TestData.uniqueEmail("hire") + "\",\"managerId\":\"" + managerId + "\"}"));
    }

    private UUID managerOf(UUID employeeId) {
        return jdbcTemplate.queryForObject("SELECT manager_id FROM users WHERE id = ?", UUID.class, employeeId);
    }

    @Test
    void aValidManagerIsAcceptedAndTheOwnerCanBeAManager() throws Exception {
        setManager(workerId, directorId).andExpect(status().isOk()).andExpect(jsonPath("$.data.managerId").value(directorId.toString()));
        setManager(workerId, ownerId).andExpect(status().isOk());
        assertThat(managerOf(workerId)).isEqualTo(ownerId);
        createWithManager(leadId).andExpect(status().isCreated());
    }

    @Test
    void aLoopInTheReportingLineIs422() throws Exception {
        // the director cannot report to the lead (who reports to the director), nor to the worker further down
        setManager(directorId, leadId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_CYCLE"));
        jdbcTemplate.update("UPDATE users SET role = 'MANAGER' WHERE id = ?", workerId);
        setManager(directorId, workerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_CYCLE"));
        // the owner cannot report to anybody under them
        setManager(ownerId, workerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_CYCLE"));
        assertThat(managerOf(directorId)).isEqualTo(ownerId);
        assertThat(managerOf(ownerId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'employee.updated' AND business_id = ?", Integer.class, businessId))
                .isZero();
    }

    @Test
    void anEmployeeIsNotTheirOwnManager() throws Exception {
        setManager(leadId, leadId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_IS_SELF"));
    }

    @Test
    void aManagerOfAnotherBusinessOrUnknownIs422() throws Exception {
        UUID otherBusinessManagerId = testData.createUser(testData.createBusiness("Other Line Co " + UUID.randomUUID()), "MANAGER",
                TestData.uniqueEmail("foreign.manager"), null);
        setManager(workerId, otherBusinessManagerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_NOT_FOUND"));
        createWithManager(otherBusinessManagerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_NOT_FOUND"));
        setManager(workerId, UUID.randomUUID()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_NOT_FOUND"));
        assertThat(managerOf(workerId)).isEqualTo(leadId);
    }

    @Test
    void anInactiveManagerOrAnEmployeeIs422() throws Exception {
        UUID colleagueId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("colleague"), leadId);
        setManager(workerId, colleagueId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_ROLE_REQUIRED"));
        UUID formerManagerId = testData.createUser(businessId, "MANAGER", TestData.uniqueEmail("former"), ownerId);
        testData.deactivate(formerManagerId);
        setManager(workerId, formerManagerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_INACTIVE"));
        createWithManager(formerManagerId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_INACTIVE"));
    }

    @Test
    void aManagerWithActiveReportsDoesNotBecomeAnEmployee() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + leadId)).cookie(ownerCookie).content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_HAS_REPORTS"));
        // once the report has another manager, the role changes
        setManager(workerId, directorId).andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + leadId)).cookie(ownerCookie).content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        // a deactivated report does not hold the role back
        UUID internId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("intern"), directorId);
        testData.deactivate(internId);
        setManager(workerId, ownerId).andExpect(status().isOk());
        setManager(leadId, ownerId).andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(patch("/api/v1/employees/" + directorId)).cookie(ownerCookie).content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        // an EMPLOYEE cannot be chosen as a manager afterwards
        setManager(workerId, directorId).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MANAGER_ROLE_REQUIRED"));
    }
}

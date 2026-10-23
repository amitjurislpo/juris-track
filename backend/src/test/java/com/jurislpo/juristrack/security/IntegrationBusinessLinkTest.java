package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A standalone business moves to JurisOne only through the super admin's explicit link ({@code PUT /api/businesses/{id}/jurisone-link}),
 * never by a token's email; once linked, its people are linked by email on their first token, and a token never takes the ADMIN role
 * away from an owner.
 */
class IntegrationBusinessLinkTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    TestData testData;
    UUID superAdminId;
    Cookie superAdminCookie;

    @BeforeEach
    void setUp() throws Exception {
        testData = new TestData(jdbcTemplate);
        String superAdminEmail = TestData.uniqueEmail("link.platform");
        superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
    }

    private ResultActions link(UUID businessId, String body) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie).content(body));
    }

    private ResultActions callMe(Employee employee) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)));
    }

    private String roleOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT role::text FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void theSuperAdminLinksAStandaloneBusinessAndItsOwnerKeepsTheOwnerRole() throws Exception {
        UUID businessId = testData.createBusiness("Moving Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("link.owner");
        UUID ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        UUID jurisoneBusinessId = UUID.randomUUID();

        link(businessId, "{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Business linked to JurisOne.")).andExpect(jsonPath("$.data.isLinkedToJurisone").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, businessId)).isEqualTo(jurisoneBusinessId);
        assertThat(jdbcTemplate.queryForMap("SELECT actor_id, category, outcome, \"after\"->>'jurisoneBusinessId' AS linked_id FROM audit_logs "
                + "WHERE action = 'integration.business_linked' AND business_id = ?", businessId))
                .containsEntry("actor_id", superAdminId).containsEntry("category", "integration").containsEntry("outcome", "SUCCESS")
                .containsEntry("linked_id", jurisoneBusinessId.toString());
        // the same link again changes nothing
        link(businessId, "{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.business_linked' AND business_id = ?",
                Integer.class, businessId)).isEqualTo(1);

        // the owner opens JurisTrack inside JurisOne: linked by email, still the owner although the token says MANAGER, then nothing
        Employee owner = new Employee(UUID.randomUUID(), ownerEmail, "Olivia Owner", jurisoneBusinessId, "Moving Co Ltd", null, "MANAGER");
        callMe(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_employee_id FROM users WHERE id = ?", UUID.class, ownerId)).isEqualTo(owner.employeeId());
        callMe(owner.withTrackRole(null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        callMe(owner.withTrackRole("EMPLOYEE")).andExpect(status().isOk());
        assertThat(roleOf(ownerId)).isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ? AND role = 'ADMIN' AND is_active", Integer.class, businessId))
                .isEqualTo(1);
    }

    @Test
    void aTokenMovesPeopleBetweenEmployeeAndManagerAndMakesAnOwnerOnlyWhenItSaysSo() throws Exception {
        UUID jurisoneBusinessId = UUID.randomUUID();
        Employee person = IntegrationTokens.newEmployee(jurisoneBusinessId, "Roles Co " + jurisoneBusinessId, "EMPLOYEE");
        callMe(person).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        UUID personId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE jurisone_employee_id = ?", UUID.class, person.employeeId());
        callMe(person.withTrackRole("MANAGER")).andExpect(jsonPath("$.data.role").value("MANAGER"));
        callMe(person.withTrackRole("EMPLOYEE")).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        callMe(person.withTrackRole("ADMIN")).andExpect(jsonPath("$.data.role").value("ADMIN"));
        callMe(person.withTrackRole("MANAGER")).andExpect(jsonPath("$.data.role").value("ADMIN"));
        assertThat(roleOf(personId)).isEqualTo("ADMIN");
    }

    @Test
    void linkingIsRefusedWhenEitherSideIsAlreadyLinkedAndCanBeUndone() throws Exception {
        UUID businessId = testData.createBusiness("Linked Co " + UUID.randomUUID());
        UUID otherBusinessId = testData.createBusiness("Other Co " + UUID.randomUUID());
        UUID jurisoneBusinessId = UUID.randomUUID();
        link(businessId, "{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}").andExpect(status().isOk());

        link(businessId, "{\"jurisoneBusinessId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_ALREADY_LINKED"));
        link(otherBusinessId, "{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JURISONE_BUSINESS_IN_USE"));
        link(otherBusinessId, "{\"jurisoneBusinessId\":\"not-an-id\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("jurisoneBusinessId"));
        link(otherBusinessId, "{}").andExpect(status().isBadRequest());
        link(UUID.randomUUID(), "{\"jurisoneBusinessId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());

        mockMvc.perform(SignIn.fromWebApp(delete("/api/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isLinkedToJurisone").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, businessId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT \"before\"->>'jurisoneBusinessId' FROM audit_logs WHERE action = 'integration.business_unlinked' "
                + "AND business_id = ?", String.class, businessId)).isEqualTo(jurisoneBusinessId.toString());
        // unlinking again changes nothing; now the other business may take the JurisOne id
        mockMvc.perform(SignIn.fromWebApp(delete("/api/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.business_unlinked' AND business_id = ?",
                Integer.class, businessId)).isEqualTo(1);
        link(otherBusinessId, "{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}").andExpect(status().isOk());
    }

    @Test
    void onlyTheSuperAdminLinks() throws Exception {
        UUID businessId = testData.createBusiness("Owner Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("link.self");
        testData.createUser(businessId, "ADMIN", ownerEmail, null);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/jurisone-link")).cookie(ownerCookie)
                .content("{\"jurisoneBusinessId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(delete("/api/businesses/" + businessId + "/jurisone-link")).cookie(ownerCookie)).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, businessId)).isNull();
    }
}

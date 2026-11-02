package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A standalone business moves to JurisOne only through the super admin's explicit link ({@code PUT /api/v1/businesses/{id}/jurisone-link}),
 * never by a token's email; once linked, its people are linked by email on their first token, and a token never takes the ADMIN role
 * away from an owner.
 */
class IntegrationBusinessLinkTest extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired com.jurislpo.juristrack.service.RateLimiter rateLimiter;
    TestData testData;
    UUID superAdminId;
    Cookie superAdminCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear(); // several sign-ins per test share one test IP address
        testData = new TestData(jdbcTemplate);
        String superAdminEmail = TestData.uniqueEmail("link.platform");
        superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
    }

    private ResultActions link(UUID businessId, String body) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie).content(body));
    }

    private ResultActions callMe(Employee employee) throws Exception {
        return mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)));
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

        mockMvc.perform(SignIn.fromWebApp(delete("/api/v1/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isLinkedToJurisone").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, businessId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT \"before\"->>'jurisoneBusinessId' FROM audit_logs WHERE action = 'integration.business_unlinked' "
                + "AND business_id = ?", String.class, businessId)).isEqualTo(jurisoneBusinessId.toString());
        // unlinking again changes nothing; now the other business may take the JurisOne id
        mockMvc.perform(SignIn.fromWebApp(delete("/api/v1/businesses/" + businessId + "/jurisone-link")).cookie(superAdminCookie)).andExpect(status().isOk());
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
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/businesses/" + businessId + "/jurisone-link")).cookie(ownerCookie)
                .content("{\"jurisoneBusinessId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(SignIn.fromWebApp(delete("/api/v1/businesses/" + businessId + "/jurisone-link")).cookie(ownerCookie)).andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, businessId)).isNull();
    }

    @Test
    void aLinkedBusinessHasNoSignInOfItsOwnAndLinkingEndsItsOpenSessions() throws Exception {
        UUID businessId = testData.createBusiness("Gateway Only Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("link.signin");
        UUID ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null);
        SignIn.SessionCookies before = SignIn.session(mockMvc, ownerEmail);
        mockMvc.perform(get("/api/v1/me").cookie(before.accessCookie())).andExpect(status().isOk());

        link(businessId, "{\"jurisoneBusinessId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());

        // the session that existed is over at once, and no new code is sent or accepted (the same neutral answers as for any refused account)
        mockMvc.perform(get("/api/v1/me").cookie(before.accessCookie())).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, ownerId)).isZero();
        SignIn.requestCode(mockMvc, ownerEmail).andExpect(status().isOk());
        SignIn.postCode(mockMvc, ownerEmail, SignIn.CODE, true).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.sign_in_failed' AND subject_id = ? AND reason = 'business_linked_to_jurisone'",
                Integer.class, ownerId)).isGreaterThanOrEqualTo(1);

        // the super admin of JurisTrack (no business) still signs in, and a business that is not linked is not affected
        SignIn.session(mockMvc, jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, superAdminId));
        UUID standalone = testData.createBusiness("Standalone Co " + UUID.randomUUID());
        String standaloneEmail = TestData.uniqueEmail("standalone.owner");
        testData.createUser(standalone, "ADMIN", standaloneEmail, null);
        SignIn.session(mockMvc, standaloneEmail);
    }

    @Test
    void aLinkedBusinessDoesNotAddPeopleHereAndAStandaloneOneStillCan() throws Exception {
        UUID jurisoneBusinessId = UUID.randomUUID();
        Employee owner = IntegrationTokens.newEmployee(jurisoneBusinessId, "Roster Only Co " + jurisoneBusinessId, "ADMIN");
        callMe(owner).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));
        String newPerson = "{\"firstName\":\"Nora\",\"lastName\":\"New\",\"email\":\"" + TestData.uniqueEmail("nora") + "\",\"employeeCode\":\"NN-1\"}";
        mockMvc.perform(post("/api/v1/employees").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(owner)).contentType(MediaType.APPLICATION_JSON).content(newPerson))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMPLOYEES_MANAGED_IN_JURISONE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = (SELECT business_id FROM users WHERE jurisone_employee_id = ?)", Integer.class,
                owner.employeeId())).isEqualTo(1);

        UUID standalone = testData.createBusiness("Adds Here Co " + UUID.randomUUID());
        String standaloneEmail = TestData.uniqueEmail("adds.owner");
        testData.createUser(standalone, "ADMIN", standaloneEmail, null);
        Cookie cookie = SignIn.session(mockMvc, standaloneEmail).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).cookie(cookie).content(newPerson.replace("NN-1", "NN-2").replace("nora", "nora2"))).andExpect(status().isCreated());
    }

    @Test
    void anOwnerWhoIsNoLongerTheOwnerInJurisOneIsDemotedUnlessTheyAreTheLastOne() throws Exception {
        UUID jurisoneBusinessId = UUID.randomUUID();
        String businessName = "Demotion Co " + jurisoneBusinessId;
        Employee first = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN");
        Employee second = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN");
        callMe(first).andExpect(jsonPath("$.data.role").value("ADMIN"));
        // the only owner is never demoted by a token
        callMe(first.withTrackRole("MANAGER")).andExpect(jsonPath("$.data.role").value("ADMIN"));
        callMe(second).andExpect(jsonPath("$.data.role").value("ADMIN"));
        // with another owner present, JurisOne's word counts: it says the first one is a plain manager now
        callMe(first.withTrackRole("MANAGER")).andExpect(jsonPath("$.data.role").value("MANAGER"));
        callMe(second.withTrackRole("EMPLOYEE")).andExpect(jsonPath("$.data.role").value("ADMIN")); // now the last one
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id IN (?, ?) AND role = 'ADMIN'", Integer.class,
                first.employeeId(), second.employeeId())).isEqualTo(1);
    }
}

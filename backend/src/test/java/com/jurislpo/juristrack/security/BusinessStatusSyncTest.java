package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code PUT /api/v1/integration/business/status}: JurisOne suspends or reactivates its business and the linked business here follows. Service token of
 * scope {@code business:status} only (the roster token cannot call it and it cannot call the roster), named business only, single use, idempotent.
 */
class BusinessStatusSyncTest extends EmbeddedIntegrationTest {

    static final String PATH = "/api/v1/integration/business/status";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    UUID jurisoneBusinessId = UUID.randomUUID();
    String businessName = "Status Co " + jurisoneBusinessId;

    static String statusToken(UUID businessId, String name) {
        return IntegrationTokens.serviceTokenFor(businessId, name, claims -> claims.claim("scope", IntegrationTokenVerifier.BUSINESS_STATUS_SCOPE));
    }

    ResultActions setStatus(String token, String body) throws Exception {
        return mockMvc.perform(put(PATH).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions callMe(Employee employee) throws Exception {
        return mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)));
    }

    boolean isSuspended(UUID jurisone) {
        return jdbcTemplate.queryForObject("SELECT is_suspended FROM businesses WHERE jurisone_business_id = ?", Boolean.class, jurisone);
    }

    @Test
    void jurisOneSuspendsAndReactivatesItsBusinessAndEveryoneFollows() throws Exception {
        Employee employee = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        callMe(employee).andExpect(status().isOk());
        // an open workday must not keep running through the suspension
        mockMvc.perform(post("/api/v1/me/time-events").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"START\"}")).andExpect(status().is2xxSuccessful());

        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":true,\"reason\":\"jurisone\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isSuspended").value(true)).andExpect(jsonPath("$.data.hasChanged").value(true));
        assertThat(isSuspended(jurisoneBusinessId)).isTrue();
        callMe(employee).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        UUID businessId = jdbcTemplate.queryForObject("SELECT id FROM businesses WHERE jurisone_business_id = ?", UUID.class, jurisoneBusinessId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'business.suspended' AND actor_id IS NULL AND reason = 'jurisone'",
                Integer.class, businessId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'workday.closed'", Integer.class, businessId)).isEqualTo(1);

        // the same state again changes nothing and writes nothing
        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.data.hasChanged").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'business.suspended'", Integer.class, businessId)).isEqualTo(1);

        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.data.isSuspended").value(false));
        callMe(employee).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'business.reactivated' AND actor_id IS NULL",
                Integer.class, businessId)).isEqualTo(1);
    }

    @Test
    void aTokenOnlyReachesTheBusinessItNamesAndAnUnknownBusinessIs404() throws Exception {
        UUID otherJurisone = UUID.randomUUID();
        callMe(IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE")).andExpect(status().isOk());
        callMe(IntegrationTokens.newEmployee(otherJurisone, "Other Co " + otherJurisone, "EMPLOYEE")).andExpect(status().isOk());

        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":true}").andExpect(status().isOk());
        assertThat(isSuspended(jurisoneBusinessId)).isTrue();
        assertThat(isSuspended(otherJurisone)).isFalse(); // the other business is untouched
        // nothing here belongs to that JurisOne business yet: nothing to do, and nothing is created
        UUID unknown = UUID.randomUUID();
        setStatus(statusToken(unknown, "Nobody Co"), "{\"isSuspended\":true}").andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, unknown)).isZero();
    }

    @Test
    void onlyTheStatusScopedServiceTokenOpensIt() throws Exception {
        callMe(IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE")).andExpect(status().isOk());
        String body = "{\"isSuspended\":true}";
        // the roster token, a user token, no token, a token without scope or with a person in it, a wrong audience, a cookie session
        setStatus(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName), body).andExpect(status().isUnauthorized());
        setStatus(IntegrationTokens.tokenFor(IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN")), body).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        setStatus(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("scope", null)), body).andExpect(status().isUnauthorized());
        setStatus(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName,
                claims -> claims.claim("scope", IntegrationTokenVerifier.BUSINESS_STATUS_SCOPE).subject(UUID.randomUUID().toString())), body).andExpect(status().isUnauthorized());
        setStatus(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName,
                claims -> claims.claim("scope", IntegrationTokenVerifier.BUSINESS_STATUS_SCOPE).audience("somebody-else")), body).andExpect(status().isUnauthorized());
        TestData testData = new TestData(jdbcTemplate);
        String ownerEmail = TestData.uniqueEmail("status.owner");
        testData.createUser(testData.createBusiness("Standalone Status Co " + UUID.randomUUID()), "ADMIN", ownerEmail, null);
        Cookie cookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(put(PATH)).cookie(cookie).content(body)).andExpect(status().is4xxClientError());
        assertThat(isSuspended(jurisoneBusinessId)).isFalse();
        // and the status token opens nothing else: not the roster, not a user endpoint
        mockMvc.perform(put("/api/v1/integration/employees").header("Authorization", "Bearer " + statusToken(jurisoneBusinessId, businessName))
                .contentType(MediaType.APPLICATION_JSON).content("{\"shifts\":[],\"employees\":[]}")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + statusToken(jurisoneBusinessId, businessName))).andExpect(status().isUnauthorized());
    }

    @Test
    void aReplayedTokenAndBadBodiesAreRefused() throws Exception {
        callMe(IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE")).andExpect(status().isOk());
        String token = statusToken(jurisoneBusinessId, businessName);
        setStatus(token, "{\"isSuspended\":true}").andExpect(status().isOk());
        setStatus(token, "{\"isSuspended\":false}").andExpect(status().isUnauthorized()); // single use
        assertThat(isSuspended(jurisoneBusinessId)).isTrue();

        setStatus(statusToken(jurisoneBusinessId, businessName), "{}").andExpect(status().isBadRequest());
        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":\"yes please\"}").andExpect(status().is4xxClientError());
        setStatus(statusToken(jurisoneBusinessId, businessName), "{\"isSuspended\":true,\"reason\":\"" + "x".repeat(5000) + "\"}").andExpect(status().isBadRequest());
        setStatus(statusToken(jurisoneBusinessId, businessName), "").andExpect(status().is4xxClientError());
        assertThat(isSuspended(jurisoneBusinessId)).isTrue();
    }
}

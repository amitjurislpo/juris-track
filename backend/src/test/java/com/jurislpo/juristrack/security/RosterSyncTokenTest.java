package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /api/integration/employees} takes JurisOne's <b>service token</b> only: signed by JurisOne's key, right issuer and audience,
 * a short life, single use, scope exactly {@code employees:sync}, no person in it. A user token or a cookie session cannot call it and the
 * service token opens no other endpoint. Refused tokens answer 401 without details and are audited with the reason code.
 */
class RosterSyncTokenTest extends RosterSyncTestBase {

    private static final String EMPTY_BODY = "{\"shifts\":[],\"employees\":[]}";

    private long rejectionsWithReason(String reason) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'integration.token_rejected' AND reason = ?", Long.class, reason);
    }

    @Test
    void aValidServiceTokenIsAccepted() throws Exception {
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName), EMPTY_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.shifts.created").value(0))
                .andExpect(jsonPath("$.data.employees.created").value(0))
                .andExpect(jsonPath("$.data.scheduleEntries.set").value(0))
                .andExpect(jsonPath("$.data.skipped").isEmpty());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE jurisone_business_id = ?", String.class, jurisoneBusinessId))
                .isEqualTo(businessName);
    }

    @Test
    void noTokenAnswers401() throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/integration/employees")).content(EMPTY_BODY)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aTokenWithoutTheScopeOrWithAnotherScopeIsRefused() throws Exception {
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("scope", null)), EMPTY_BODY)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(rejectionsWithReason("missing_scope")).isPositive();
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("scope", "employees:read")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("scope", "employees:sync other")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("wrong_scope")).isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isZero();
    }

    @Test
    void aTokenThatIsAboutAPersonIsNotAServiceToken() throws Exception {
        UUID employeeId = UUID.randomUUID();
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.subject(employeeId.toString())), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("email", "someone@test.juristrack.test")),
                EMPTY_BODY).andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("not_a_service_token")).isPositive();
    }

    @Test
    void aUserTokenCannotCallTheRosterSync() throws Exception {
        Employee manager = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "MANAGER");
        syncAs(IntegrationTokens.tokenFor(manager), EMPTY_BODY).andExpect(status().isUnauthorized());
        Employee owner = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "ADMIN");
        syncAs(IntegrationTokens.tokenFor(owner), EMPTY_BODY).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id IN (?, ?)", Integer.class, manager.employeeId(),
                owner.employeeId())).isZero();
    }

    @Test
    void aServiceTokenWorksOnNoOtherEndpoint() throws Exception {
        String token = IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName);
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/shifts").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/team/members").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        // not even the roster path with another method, spelling or a path under it
        String freshToken = IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName);
        mockMvc.perform(get("/api/integration/employees").header("Authorization", "Bearer " + freshToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/integration/employees/").header("Authorization", "Bearer " + freshToken).contentType("application/json").content(EMPTY_BODY))
                .andExpect(status().isUnauthorized());
        // nothing was provisioned by any of it
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isZero();
        assertThat(rejectionsWithReason("service_token_not_accepted")).isPositive();
    }

    @Test
    void aCookieSessionCannotCallTheRosterSync() throws Exception {
        UUID businessId = testData.createBusiness("Cookie Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("cookie.owner");
        testData.createUser(businessId, "ADMIN", ownerEmail, null);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(put("/api/integration/employees")).cookie(ownerCookie).content(EMPTY_BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Instant longAgo = Instant.now().minusSeconds(3600);
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName,
                claims -> claims.issueTime(Date.from(longAgo)).expirationTime(Date.from(longAgo.plusSeconds(300)))), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("expired")).isPositive();
    }

    @Test
    void aTokenThatLivesTooLongIsRefused() throws Exception {
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.expirationTime(Date.from(Instant.now().plusSeconds(3600)))),
                EMPTY_BODY).andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("lifetime_too_long")).isPositive();
    }

    @Test
    void aReplayedTokenIsRefused() throws Exception {
        String token = IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName);
        syncAs(token, EMPTY_BODY).andExpect(status().isOk());
        syncAs(token, EMPTY_BODY).andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("replayed_token")).isPositive();
    }

    @Test
    void aWrongAudienceOrIssuerIsRefused() throws Exception {
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.audience("somebody-else")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("wrong_audience")).isPositive();
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.issuer("http://not-jurisone.test")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("wrong_issuer")).isPositive();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        String forged = IntegrationTokens.sign(IntegrationTokens.serviceClaimsOf(jurisoneBusinessId, businessName).build(), IntegrationTokens.rsaKeyPair());
        syncAs(forged, EMPTY_BODY).andExpect(status().isUnauthorized());
        assertThat(rejectionsWithReason("bad_signature")).isPositive();
    }

    @Test
    void theBusinessClaimsAreRequired() throws Exception {
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("businessId", null)), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("businessId", "not-a-uuid")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName, claims -> claims.claim("businessName", " ")), EMPTY_BODY)
                .andExpect(status().isUnauthorized());
        assertThat(List.of(rejectionsWithReason("missing_claim: businessId"), rejectionsWithReason("invalid_claim: businessId"),
                rejectionsWithReason("missing_claim: businessName"))).allMatch(count -> count > 0);
    }
}

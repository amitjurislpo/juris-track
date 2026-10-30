package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Sign-in and sessions: every code request (also for unknown and inactive accounts, without telling the caller), every refused sign-in with
 * its reason, the burnt code, rate limits (one entry per limit, key and window), sign-out, and refused refreshes. Codes, tokens and cookies
 * never reach the log.
 */
class SignInAuditTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    TestData testData;
    UUID businessId;
    String ipAddress;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Sign-in Audit Co " + UUID.randomUUID());
        ipAddress = AuditTrace.newIpAddress();
    }

    private ResultActions requestCode(AuditTrace trace, String email) throws Exception {
        return mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/v1/auth/otp-requests"))).content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions postCode(AuditTrace trace, String email, String code) throws Exception {
        return mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/v1/auth/sessions"))).content("{\"email\":\"" + email + "\",\"otp\":\"" + code + "\"}"));
    }

    private AuditTrace trace() {
        return new AuditTrace(jdbcTemplate, ipAddress);
    }

    @Test
    void codeRequestsForUnknownAndInactiveAccountsAreRecordedAsDeniedWithTheSameAnswer() throws Exception {
        String unknownEmail = TestData.uniqueEmail("nobody");
        String inactiveEmail = TestData.uniqueEmail("inactive");
        UUID inactiveId = testData.createUser(businessId, "EMPLOYEE", inactiveEmail, null);
        testData.deactivate(inactiveId);
        String activeEmail = TestData.uniqueEmail("active");
        testData.createUser(businessId, "EMPLOYEE", activeEmail, null);

        AuditTrace unknownTrace = trace();
        String unknownAnswer = requestCode(unknownTrace, unknownEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AuditTrace inactiveTrace = trace();
        String inactiveAnswer = requestCode(inactiveTrace, inactiveEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        AuditTrace activeTrace = trace();
        String activeAnswer = requestCode(activeTrace, activeEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknownAnswer).isEqualTo(activeAnswer).isEqualTo(inactiveAnswer);

        Map<String, Object> unknownEntry = unknownTrace.entry("auth.sign_in_code_requested");
        assertThat(unknownEntry).containsEntry("outcome", "DENIED").containsEntry("reason", "unknown_email");
        assertThat(unknownEntry.get("business_id")).isNull();
        assertThat(unknownEntry.get("subject_id")).isNull();
        assertThat((String) unknownEntry.get("after_json")).contains(unknownEmail.charAt(0) + "***@test.juristrack.test").doesNotContain(unknownEmail);
        assertThat(inactiveTrace.entry("auth.sign_in_code_requested")).containsEntry("outcome", "DENIED").containsEntry("reason", "inactive")
                .containsEntry("subject_id", inactiveId).containsEntry("business_id", businessId);
        assertThat(activeTrace.entry("auth.sign_in_code_requested")).containsEntry("outcome", "SUCCESS");
    }

    @Test
    void everyRefusedSignInHasItsReasonAndTheBurntCodeIsRecordedOnce() throws Exception {
        String email = TestData.uniqueEmail("burner");
        UUID userId = testData.createUser(businessId, "EMPLOYEE", email, null);
        AuditTrace noCodeTrace = trace();
        postCode(noCodeTrace, email, SignIn.CODE).andExpect(status().isUnauthorized());
        assertThat(noCodeTrace.entry("auth.sign_in_failed")).containsEntry("reason", "expired_or_used_code").containsEntry("subject_id", userId);

        requestCode(trace(), email).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            AuditTrace wrongTrace = trace();
            postCode(wrongTrace, email, "111111").andExpect(status().isUnauthorized());
            assertThat(wrongTrace.entry("auth.sign_in_failed")).containsEntry("reason", "wrong_code");
            assertThat(wrongTrace.count("auth.sign_in_code_burnt")).isZero();
        }
        AuditTrace burningTrace = trace();
        postCode(burningTrace, email, "111111").andExpect(status().isUnauthorized());
        assertThat(burningTrace.entry("auth.sign_in_code_burnt")).containsEntry("outcome", "DENIED").containsEntry("subject_id", userId)
                .containsEntry("business_id", businessId).containsEntry("reason", "too_many_wrong_codes");
        AuditTrace burntTrace = trace();
        postCode(burntTrace, email, SignIn.CODE).andExpect(status().isUnauthorized());
        assertThat(burntTrace.entry("auth.sign_in_failed")).containsEntry("reason", "code_burnt");

        jdbcTemplate.update("UPDATE login_otps SET expires_at = now() - interval '1 minute' WHERE email = ?", email);
        requestCode(trace(), email);
        jdbcTemplate.update("UPDATE login_otps SET expires_at = now() - interval '1 minute' WHERE email = ? AND used_at IS NULL", email);
        AuditTrace expiredTrace = trace();
        postCode(expiredTrace, email, SignIn.CODE).andExpect(status().isUnauthorized());
        assertThat(expiredTrace.entry("auth.sign_in_failed")).containsEntry("reason", "expired_or_used_code");

        // no code ever reaches the log
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE subject_id = ? AND (\"after\"::text LIKE '%111111%' "
                + "OR \"after\"::text LIKE '%" + SignIn.CODE + "%')", Integer.class, userId)).isZero();
    }

    @Test
    void codeRequestRateLimitsAreRecordedOncePerWindow() throws Exception {
        String email = TestData.uniqueEmail("limited");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        for (int i = 0; i < 5; i++) requestCode(trace(), email).andExpect(status().isOk());
        AuditTrace firstLimitedTrace = trace();
        requestCode(firstLimitedTrace, email).andExpect(status().isTooManyRequests());
        Map<String, Object> entry = firstLimitedTrace.entry("auth.rate_limited");
        assertThat(entry).containsEntry("outcome", "DENIED").containsEntry("reason", "code_requests_per_email").containsEntry("category", "auth");
        assertThat((String) entry.get("after_json")).contains("\"maximumPerWindow\": 5").doesNotContain(email);
        AuditTrace secondLimitedTrace = trace();
        requestCode(secondLimitedTrace, email).andExpect(status().isTooManyRequests());
        assertThat(secondLimitedTrace.count("auth.rate_limited")).isZero();
    }

    @Test
    void failedSignInRateLimitIsRecordedOncePerWindow() throws Exception {
        String email = TestData.uniqueEmail("guesser");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        // spread over three accounts, so the per-email limit (10) is not what stops the guesses
        List<String> emails = List.of(TestData.uniqueEmail("guesser.one"), TestData.uniqueEmail("guesser.two"), email);
        for (int i = 0; i < 20; i++) postCode(trace(), emails.get(i % 3), "123456").andExpect(status().isUnauthorized());
        AuditTrace limitedTrace = trace();
        postCode(limitedTrace, email, "123456").andExpect(status().isTooManyRequests());
        assertThat(limitedTrace.entry("auth.rate_limited")).containsEntry("reason", "failed_sign_ins_per_ip");
        AuditTrace againTrace = trace();
        postCode(againTrace, email, "123456").andExpect(status().isTooManyRequests());
        assertThat(againTrace.entries()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.rate_limited' AND ip_address = ?", Integer.class,
                ipAddress)).isEqualTo(1);
    }

    @Test
    void signingOutWithOnlyTheRefreshCookieIsAudited() throws Exception {
        String email = TestData.uniqueEmail("leaving");
        UUID userId = testData.createUser(businessId, "EMPLOYEE", email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);
        AuditTrace trace = trace();
        mockMvc.perform(trace.on(SignIn.fromWebApp(delete("/api/v1/auth/sessions/current"))).cookie(cookies.refreshCookie())).andExpect(status().isOk());
        Map<String, Object> entry = trace.entry("auth.signed_out");
        assertThat(entry).containsEntry("actor_id", userId).containsEntry("subject_id", userId).containsEntry("business_id", businessId);
        assertThat((String) entry.get("after_json")).contains("withRefreshCookieOnly");
        // signing out again ends nothing and writes nothing
        AuditTrace againTrace = trace();
        mockMvc.perform(againTrace.on(SignIn.fromWebApp(delete("/api/v1/auth/sessions/current"))).cookie(cookies.refreshCookie())).andExpect(status().isOk());
        assertThat(againTrace.entries()).isEmpty();
    }

    @Test
    void refreshesAreAuditedOnlyWhenRefusedAndOncePerWindow() throws Exception {
        String email = TestData.uniqueEmail("refresher");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);
        AuditTrace successTrace = trace();
        mockMvc.perform(successTrace.on(SignIn.fromWebApp(post("/api/v1/auth/session-refreshes"))).cookie(cookies.refreshCookie())).andExpect(status().isCreated());
        AuditTrace missingTrace = trace();
        mockMvc.perform(missingTrace.on(SignIn.fromWebApp(post("/api/v1/auth/session-refreshes")))).andExpect(status().isUnauthorized());
        assertThat(successTrace.entries()).isEmpty();
        assertThat(missingTrace.entries()).isEmpty();

        List<AuditTrace> unknownTraces = List.of(trace(), trace());
        for (AuditTrace unknownTrace : unknownTraces) {
            mockMvc.perform(unknownTrace.on(SignIn.fromWebApp(post("/api/v1/auth/session-refreshes"))).cookie(new Cookie("jt_rt", "stolen-or-made-up")))
                    .andExpect(status().isUnauthorized());
        }
        Map<String, Object> entry = unknownTraces.get(0).entry("auth.session_refresh_failed");
        assertThat(entry).containsEntry("reason", "unknown").containsEntry("outcome", "DENIED");
        assertThat(entry.toString()).doesNotContain("stolen-or-made-up");
        assertThat(unknownTraces.get(1).entries()).isEmpty();
    }
}

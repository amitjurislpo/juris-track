package com.jurislpo.juristrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuthService;
import com.jurislpo.juristrack.service.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Standalone sign-in by emailed code: generic answers, wrong and burnt codes, cookies, rate limits, audit. */
class OtpSignInTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Sign-in Test " + UUID.randomUUID());
    }

    String newEmployee() {
        String email = TestData.uniqueEmail("employee");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        return email;
    }

    int openCodes(String email) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email = ? AND used_at IS NULL", Integer.class, email);
    }

    @Test
    void aRequestedCodeSignsInAndSetsBothSessionCookies() throws Exception {
        String email = newEmployee();
        SignIn.requestCode(mockMvc, email.toUpperCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.message").value(AuthService.CODE_REQUESTED));
        assertThat(openCodes(email)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT code_hash FROM login_otps WHERE email = ?", String.class, email)).doesNotContain(SignIn.CODE);

        MockHttpServletResponse response = SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andReturn().getResponse();
        String accessCookie = SignIn.setCookieHeader(response, "jt_at");
        String refreshCookie = SignIn.setCookieHeader(response, "jt_rt");
        assertThat(accessCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/").contains("Max-Age=").doesNotContain("Secure");
        assertThat(refreshCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=");
        assertThat(jdbcTemplate.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE email = ?", Boolean.class, email)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.signed_in' AND business_id = ?", Integer.class, businessId))
                .isEqualTo(1);

        // a code works once
        SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
    }

    @Test
    void withoutKeepMeSignedInTheCookiesEndWithTheBrowser() throws Exception {
        String email = newEmployee();
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        MockHttpServletResponse response = SignIn.postCode(mockMvc, email, SignIn.CODE, false).andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(SignIn.setCookieHeader(response, "jt_at")).doesNotContain("Max-Age");
        assertThat(SignIn.setCookieHeader(response, "jt_rt")).doesNotContain("Max-Age");
        assertThat(jdbcTemplate.queryForObject("SELECT expires_at < now() + interval '13 hours' FROM sessions s JOIN users u ON u.id = s.user_id WHERE u.email = ?",
                Boolean.class, email)).isTrue();
    }

    @Test
    void aWrongCodeIsRejectedAndFiveWrongTriesBurnTheCode() throws Exception {
        String email = newEmployee();
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            SignIn.postCode(mockMvc, email, "111111", true).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
        }
        // the right code no longer works: the code is burnt
        SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'auth.sign_in_failed' AND business_id = ?", Integer.class, businessId))
                .isEqualTo(6);
        // a new code works
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isCreated());
    }

    @Test
    void aNewCodeReplacesTheOldOneAndAnExpiredCodeIsRejected() throws Exception {
        String email = newEmployee();
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        assertThat(openCodes(email)).isEqualTo(1);
        jdbcTemplate.update("UPDATE login_otps SET expires_at = now() - interval '1 minute' WHERE email = ?", email);
        SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
    }

    @Test
    void unknownAndInactiveAccountsGetTheSameAnswerButNoCode() throws Exception {
        String inactiveEmail = newEmployee();
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE email = ?", inactiveEmail);
        String unknownEmail = TestData.uniqueEmail("nobody");

        String answerForKnown = SignIn.requestCode(mockMvc, newEmployee()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String answerForInactive = SignIn.requestCode(mockMvc, inactiveEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String answerForUnknown = SignIn.requestCode(mockMvc, unknownEmail).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(answerForInactive).isEqualTo(answerForKnown);
        assertThat(answerForUnknown).isEqualTo(answerForKnown);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email IN (?, ?)", Integer.class, inactiveEmail, unknownEmail)).isZero();
        // and no code ever works for them, with the same refusal as a wrong code
        SignIn.postCode(mockMvc, inactiveEmail, SignIn.CODE, true).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
        SignIn.postCode(mockMvc, unknownEmail, SignIn.CODE, true).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(AuthService.WRONG_CODE));
    }

    @Test
    void codeRequestsAreRateLimitedPerEmail() throws Exception {
        String email = newEmployee();
        for (int i = 0; i < 5; i++) SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        SignIn.requestCode(mockMvc, email).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(jsonPath("$.detail").value(AuthService.TOO_MANY_CODE_REQUESTS));
        // an unknown email is limited the same way (no difference to observe)
        rateLimiter.clear(); // the per-IP limit (10) would otherwise end this test first
        String unknownEmail = TestData.uniqueEmail("nobody");
        for (int i = 0; i < 5; i++) SignIn.requestCode(mockMvc, unknownEmail).andExpect(status().isOk());
        SignIn.requestCode(mockMvc, unknownEmail).andExpect(status().isTooManyRequests());
    }

    @Test
    void codeRequestsAreRateLimitedPerIpAddress() throws Exception {
        for (int i = 0; i < 10; i++) SignIn.requestCode(mockMvc, TestData.uniqueEmail("anyone")).andExpect(status().isOk());
        SignIn.requestCode(mockMvc, TestData.uniqueEmail("anyone")).andExpect(status().isTooManyRequests());
    }

    @Test
    void manyWrongCodesFromOneIpAddressAreRateLimited() throws Exception {
        // three accounts, so the per-email limit (10) is not what stops the guesses
        List<String> emails = List.of(newEmployee(), newEmployee(), newEmployee());
        for (int i = 0; i < 20; i++) SignIn.postCode(mockMvc, emails.get(i % 3), "123456", true).andExpect(status().isUnauthorized());
        SignIn.postCode(mockMvc, emails.get(2), "123456", true).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(AuthService.TOO_MANY_FAILED_SIGN_INS));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        SignIn.requestCode(mockMvc, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        SignIn.requestCode(mockMvc, "not-an-email").andExpect(status().isBadRequest());
        SignIn.postCode(mockMvc, newEmployee(), "12ab", true).andExpect(status().isBadRequest());
        // there are no passwords
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/sessions"))
                .content("{\"email\":\"" + newEmployee() + "\",\"password\":\"anything-at-all-1\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void theSuperAdminSignsInWithoutABusiness() throws Exception {
        String email = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", email, null);
        SignIn.requestCode(mockMvc, email).andExpect(status().isOk());
        SignIn.postCode(mockMvc, email, SignIn.CODE, true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN")).andExpect(jsonPath("$.data.businessName").isEmpty());
    }

    @Test
    void manyWrongCodesForOneAccountAreRateLimitedWhateverTheIpAddress() throws Exception {
        String email = newEmployee();
        // ten guesses, each from another address (the header is believed: the connection comes from a trusted proxy)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/sessions")).header("X-Forwarded-For", "198.51.100." + (10 + i))
                    .content("{\"email\":\"" + email + "\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/sessions")).header("X-Forwarded-For", "198.51.100.99")
                .content("{\"email\":\"" + email + "\",\"otp\":\"123456\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void aCallerThatIsNotATrustedProxyCannotChooseItsIpAddress() throws Exception {
        // 20 wrong codes, each claiming another address in X-Forwarded-For, from one connection that is not a proxy: still one IP address
        List<String> emails = List.of(newEmployee(), newEmployee(), newEmployee());
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/sessions")).with(request -> {
                        request.setRemoteAddr("203.0.113.77");
                        return request;
                    }).header("X-Forwarded-For", "192.0.2." + (1 + i))
                    .content("{\"email\":\"" + emails.get(i % 3) + "\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/auth/sessions")).with(request -> {
                    request.setRemoteAddr("203.0.113.77");
                    return request;
                }).header("X-Forwarded-For", "192.0.2.200")
                .content("{\"email\":\"" + emails.get(0) + "\",\"otp\":\"123456\"}")).andExpect(status().isTooManyRequests());
    }
}

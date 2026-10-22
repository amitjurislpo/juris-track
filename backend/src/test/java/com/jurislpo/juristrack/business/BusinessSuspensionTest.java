package com.jurislpo.juristrack.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A suspended business: its users get no sign-in codes and cannot sign in, and their existing sessions are refused with 403
 * {@code BUSINESS_SUSPENDED} (signing out still works); after reactivation the same session works again. Other businesses are untouched.
 */
class BusinessSuspensionTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    TestData testData;
    UUID businessId;
    String employeeEmail;
    Cookie superAdminCookie;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Suspended Co " + UUID.randomUUID());
        employeeEmail = TestData.uniqueEmail("suspended.employee");
        testData.createUser(businessId, "EMPLOYEE", employeeEmail, null);
        String superAdminEmail = TestData.uniqueEmail("platform");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
    }

    private void setSuspended(boolean isSuspended) throws Exception {
        mockMvc.perform(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status")).cookie(superAdminCookie)
                .content("{\"isSuspended\":" + isSuspended + "}")).andExpect(status().isOk());
    }

    private int codesSentTo(String email) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email);
    }

    @Test
    void existingSessionsAreRefusedWith403UntilTheBusinessIsReactivated() throws Exception {
        String ownerEmail = TestData.uniqueEmail("suspended.owner");
        testData.createUser(businessId, "ADMIN", ownerEmail, null);
        SessionCookies employeeCookies = SignIn.session(mockMvc, employeeEmail);
        SessionCookies ownerCookies = SignIn.session(mockMvc, ownerEmail);
        String otherEmail = TestData.uniqueEmail("unaffected");
        testData.createUser(testData.createBusiness("Unaffected " + UUID.randomUUID()), "EMPLOYEE", otherEmail, null);
        SessionCookies otherCookies = SignIn.session(mockMvc, otherEmail);

        setSuspended(true);
        mockMvc.perform(get("/api/me").cookie(employeeCookies.accessCookie())).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail").value("Your company's access to JurisTrack is suspended. Please contact support."));
        mockMvc.perform(get("/api/employees").cookie(ownerCookies.accessCookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        mockMvc.perform(get("/api/me").cookie(otherCookies.accessCookie())).andExpect(status().isOk());

        // the session was not ended: it works again after reactivation
        setSuspended(false);
        mockMvc.perform(get("/api/me").cookie(employeeCookies.accessCookie())).andExpect(status().isOk());
    }

    @Test
    void aSuspendedBusinessGetsNoCodesAndCannotSignIn() throws Exception {
        setSuspended(true);
        int codesBefore = codesSentTo(employeeEmail);
        SignIn.requestCode(mockMvc, employeeEmail).andExpect(status().isOk()); // the same answer as for everybody
        assertThat(codesSentTo(employeeEmail)).isEqualTo(codesBefore);
        SignIn.postCode(mockMvc, employeeEmail, SignIn.CODE, false).andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'auth.sign_in_failed' "
                + "AND \"after\"->>'reason' = 'business_suspended'", Integer.class, businessId)).isEqualTo(1);

        setSuspended(false);
        SignIn.session(mockMvc, employeeEmail);
        assertThat(codesSentTo(employeeEmail)).isEqualTo(codesBefore + 1);
    }

    @Test
    void signingOutStillWorksWhileSuspended() throws Exception {
        SessionCookies cookies = SignIn.session(mockMvc, employeeEmail);
        setSuspended(true);
        mockMvc.perform(delete("/api/auth/sessions/current").header("origin", SignIn.ORIGIN).cookie(cookies.accessCookie(), cookies.refreshCookie()))
                .andExpect(status().isOk());
        setSuspended(false);
        mockMvc.perform(get("/api/me").cookie(cookies.accessCookie())).andExpect(status().isUnauthorized());
    }
}

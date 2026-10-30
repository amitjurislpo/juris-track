package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.common.ApiPaths;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every API path lives under {@code /api/v1}: no handler outside it (the error page aside), the old {@code /api/...} paths are not
 * served at all, and the gates (sign-in, Origin check, permissions) apply on the new prefix.
 */
class ApiVersionTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyHandlerPathIsUnderTheVersionedPrefix() {
        List<String> paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .filter(path -> !path.equals("/error"))
                .toList();
        assertThat(paths).hasSizeGreaterThan(40);
        assertThat(paths).allSatisfy(path -> assertThat(path).startsWith(ApiPaths.PREFIX + "/"));
        assertThat(ApiPaths.PREFIX).isEqualTo("/api/v1");
        // the sign-in and roster-sync paths that the filters and security rules name must be real handlers
        List<String> methodAndPaths = handlerMapping.getHandlerMethods().keySet().stream().map(RequestMappingInfo::toString).toList();
        assertThat(methodAndPaths).anyMatch(entry -> entry.contains("/api/v1/auth/otp-requests"));
        assertThat(methodAndPaths).anyMatch(entry -> entry.contains(RequestAuthenticationFilter.ROSTER_SYNC_PATH));
    }

    @Test
    void theOldUnversionedPathsAreNotServed() throws Exception {
        String email = TestData.uniqueEmail("v1.owner");
        UUID businessId = new TestData(jdbcTemplate).createBusiness("V1 " + UUID.randomUUID());
        new TestData(jdbcTemplate).createUser(businessId, "ADMIN", email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);
        mockMvc.perform(get("/api/v1/me").cookie(cookies.accessCookie())).andExpect(status().isOk());

        for (String oldPath : List.of("/api/me", "/api/workdays/mine", "/api/employees", "/api/auth/otp-requests", "/api/integration/employees")) {
            // signed out, signed in, read and write: never a success
            assertThat(mockMvc.perform(get(oldPath)).andReturn().getResponse().getStatus()).as("GET %s signed out", oldPath).isGreaterThanOrEqualTo(400);
            assertThat(mockMvc.perform(get(oldPath).cookie(cookies.accessCookie())).andReturn().getResponse().getStatus())
                    .as("GET %s signed in", oldPath).isGreaterThanOrEqualTo(400);
            assertThat(mockMvc.perform(SignIn.fromWebApp(post(oldPath)).cookie(cookies.accessCookie()).content("{\"email\":\"" + email + "\"}"))
                    .andReturn().getResponse().getStatus()).as("POST %s", oldPath).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void theGatesApplyOnTheVersionedPrefix() throws Exception {
        String ownerEmail = TestData.uniqueEmail("gate.owner");
        String employeeEmail = TestData.uniqueEmail("gate.employee");
        UUID businessId = new TestData(jdbcTemplate).createBusiness("Gate " + UUID.randomUUID());
        new TestData(jdbcTemplate).createUser(businessId, "ADMIN", ownerEmail, null);
        new TestData(jdbcTemplate).createUser(businessId, "EMPLOYEE", employeeEmail, null);
        SessionCookies ownerCookies = SignIn.session(mockMvc, ownerEmail);
        SessionCookies employeeCookies = SignIn.session(mockMvc, employeeEmail);

        // signed out: 401 on reads and writes
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/workdays/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/employees")).content("{}")).andExpect(status().isUnauthorized());
        // the roster sync takes the service token only: a cookie session is refused
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/integration/employees")).cookie(ownerCookies.accessCookie()).content("{}"))
                .andExpect(status().isForbidden());

        // every state-changing method without the web app's Origin is refused, a wrong Origin too
        for (MockHttpServletRequestBuilder write : List.of(post("/api/v1/employees"), put("/api/v1/employees/" + UUID.randomUUID() + "/status"),
                patch("/api/v1/business"), delete("/api/v1/auth/sessions/current"))) {
            mockMvc.perform(write.cookie(ownerCookies.accessCookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
        }
        mockMvc.perform(post("/api/v1/employees").cookie(ownerCookies.accessCookie()).header("origin", "https://evil.example.com")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());

        // permissions are unchanged: an employee does not manage people, the owner reads them
        mockMvc.perform(get("/api/v1/employees").cookie(employeeCookies.accessCookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/employees").cookie(ownerCookies.accessCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

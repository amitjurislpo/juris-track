package com.jurislpo.juristrack.team;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One business with a three-level reporting line and a second branch, signed in on demand:
 * <pre>
 * owner (ADMIN)
 * ├── managerA (MANAGER)
 * │   ├── managerB (MANAGER)
 * │   │   └── employee (EMPLOYEE)
 * │   └── employeeOfA (EMPLOYEE)
 * └── managerC (MANAGER)
 *     └── employeeOfC (EMPLOYEE)
 * </pre>
 * The business time zone is Asia/Kolkata (UTC+05:30), stale workday hours 14.
 */
final class TeamFixture {

    final UUID businessId;
    final UUID ownerId;
    final UUID managerAId;
    final UUID managerBId;
    final UUID employeeId;
    final UUID employeeOfAId;
    final UUID managerCId;
    final UUID employeeOfCId;

    private final MockMvc mockMvc;
    private final RateLimiter rateLimiter;
    private final Map<UUID, String> emailByUserId = new HashMap<>();
    private final Map<UUID, Cookie> cookieByUserId = new HashMap<>();

    TeamFixture(MockMvc mockMvc, JdbcTemplate jdbcTemplate, RateLimiter rateLimiter) {
        this.mockMvc = mockMvc;
        this.rateLimiter = rateLimiter;
        TestData testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Team Co " + UUID.randomUUID());
        ownerId = createUser(testData, "ADMIN", null, "Olivia", "Owner", "OWN-1");
        managerAId = createUser(testData, "MANAGER", ownerId, "Mira", "Anand", "MGR-A");
        managerBId = createUser(testData, "MANAGER", managerAId, "Bala", "Bose", "MGR-B");
        employeeId = createUser(testData, "EMPLOYEE", managerBId, "Esha", "Iyer", "EMP-7");
        employeeOfAId = createUser(testData, "EMPLOYEE", managerAId, "Arun", "Kumar", "EMP-8");
        managerCId = createUser(testData, "MANAGER", ownerId, "Chitra", "Rao", "MGR-C");
        employeeOfCId = createUser(testData, "EMPLOYEE", managerCId, "Dev", "Shah", "EMP-9");
    }

    private UUID createUser(TestData testData, String role, UUID managerId, String firstName, String lastName, String employeeCode) {
        String email = TestData.uniqueEmail(firstName.toLowerCase());
        UUID userId = testData.createUser(businessId, role, email, managerId, firstName, lastName, employeeCode);
        emailByUserId.put(userId, email);
        return userId;
    }

    String emailOf(UUID userId) {
        return emailByUserId.get(userId);
    }

    /** The user's access cookie, signing them in the first time. */
    Cookie cookieOf(UUID userId) throws Exception {
        Cookie cookie = cookieByUserId.get(userId);
        if (cookie == null) {
            rateLimiter.clear();
            cookie = SignIn.session(mockMvc, emailByUserId.get(userId)).accessCookie();
            cookieByUserId.put(userId, cookie);
        }
        return cookie;
    }

    /** {@code POST /api/v1/me/time-events} as the user. */
    ResultActions sendEvent(UUID userId, String type) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(cookieOf(userId)).content("{\"type\":\"" + type + "\"}"));
    }

    /** A time event that must change the state (201); answers the workday id it applied to. */
    String applyEvent(UUID userId, String type) throws Exception {
        String answer = sendEvent(userId, type).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(answer, "$.data.workdayId");
    }

    ResultActions read(UUID userId, String path) throws Exception {
        return mockMvc.perform(get(path).cookie(cookieOf(userId)));
    }

    ResultActions postJson(UUID userId, String path, String json) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post(path)).cookie(cookieOf(userId)).content(json));
    }

    ResultActions deleteJson(UUID userId, String path) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(delete(path)).cookie(cookieOf(userId)));
    }

    ResultActions patchJson(UUID userId, String path, String json) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(patch(path)).cookie(cookieOf(userId)).content(json));
    }

    ResultActions putJson(UUID userId, String path, String json) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(put(path)).cookie(cookieOf(userId)).content(json));
    }

    /** A value of a JSON answer that must be 200. */
    <T> T valueOf(ResultActions answer, String jsonPath) throws Exception {
        return JsonPath.read(answer.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), jsonPath);
    }
}

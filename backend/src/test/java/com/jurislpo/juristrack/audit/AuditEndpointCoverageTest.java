package com.jurislpo.juristrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.SignIn.SessionCookies;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAction;
import com.jurislpo.juristrack.service.AuditAggregation;
import com.jurislpo.juristrack.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every write endpoint writes its audit entry. {@link #EXPECTED_AUDIT_ACTIONS} lists each POST / PUT / PATCH / DELETE handler of the API
 * with the audit actions it writes; {@link #everyWriteEndpointIsInTheAuditMap()} fails the build when a write endpoint is added without
 * an entry here (and the test that proves it), or an entry is left behind for a removed endpoint. One test per endpoint below calls it
 * and checks the entry: action, outcome, actor, subject, request id and IP address.
 */
class AuditEndpointCoverageTest extends IntegrationTest {

    /** {@code "METHOD /path"} of every write endpoint -> the audit actions the tests below prove it writes. */
    static final Map<String, List<String>> EXPECTED_AUDIT_ACTIONS = Map.ofEntries(
            Map.entry("POST /api/auth/otp-requests", List.of("auth.sign_in_code_requested")),
            Map.entry("POST /api/auth/sessions", List.of("auth.signed_in", "auth.sign_in_failed")),
            // a successful refresh is not audited (every few minutes); refusals and a reused token are
            Map.entry("POST /api/auth/session-refreshes", List.of("auth.session_refresh_failed", "auth.session_revoked_refresh_reuse")),
            Map.entry("DELETE /api/auth/sessions/current", List.of("auth.signed_out")),
            Map.entry("POST /api/businesses", List.of("business.created", "employee.created")),
            Map.entry("PATCH /api/businesses/{businessId}", List.of("business.updated")),
            Map.entry("PUT /api/businesses/{businessId}/status", List.of("business.suspended", "business.reactivated")),
            Map.entry("PUT /api/businesses/{businessId}/jurisone-link", List.of("integration.business_linked")),
            Map.entry("DELETE /api/businesses/{businessId}/jurisone-link", List.of("integration.business_unlinked")),
            Map.entry("PATCH /api/business", List.of("business.updated")),
            Map.entry("POST /api/employees", List.of("employee.created", "employee.change_refused")),
            Map.entry("PATCH /api/employees/{employeeId}", List.of("employee.updated", "employee.change_refused")),
            Map.entry("PUT /api/employees/{employeeId}/status", List.of("employee.deactivated", "workday.closed", "employee.reactivated",
                    "employee.change_refused")),
            Map.entry("POST /api/me/time-events", List.of("time.workday_started", "time.break_started", "time.work_resumed", "time.workday_ended",
                    "time.workday_reopened")),
            Map.entry("PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}", List.of("time.session_adjusted", "time.correction_refused")),
            Map.entry("PATCH /api/workdays/{workdayId}/break-sessions/{sessionId}", List.of("time.session_adjusted", "time.correction_refused")),
            Map.entry("PUT /api/workdays/{workdayId}/status", List.of("time.workday_closed")));

    private static final Set<RequestMethod> WRITE_METHODS = Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AuditAggregation auditAggregation;
    @Autowired AdjustableClock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    TestData testData;
    UUID businessId;
    UUID ownerId;
    UUID managerId;
    String managerEmail;
    Cookie ownerCookie;
    Cookie superAdminCookie;
    UUID superAdminId;

    @BeforeEach
    void setUp() throws Exception {
        rateLimiter.clear();
        auditAggregation.clear();
        testData = new TestData(jdbcTemplate);
        businessId = testData.createBusiness("Coverage Co " + UUID.randomUUID());
        String ownerEmail = TestData.uniqueEmail("coverage.owner");
        ownerId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", null);
        managerEmail = TestData.uniqueEmail("coverage.manager");
        managerId = testData.createUser(businessId, "MANAGER", managerEmail, ownerId, "Manish", "Manager", null);
        ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        String superAdminEmail = TestData.uniqueEmail("coverage.platform");
        superAdminId = testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        superAdminCookie = SignIn.session(mockMvc, superAdminEmail).accessCookie();
    }

    @Test
    void everyWriteEndpointIsInTheAuditMap() {
        Set<String> writeEndpoints = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((RequestMappingInfo mapping, HandlerMethod handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.jurislpo.juristrack")) return;
            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            // a handler without a method restriction accepts writes too
            Set<RequestMethod> writeMethods = methods.isEmpty() ? WRITE_METHODS : methods.stream().filter(WRITE_METHODS::contains).collect(Collectors.toSet());
            for (RequestMethod method : writeMethods) for (String path : mapping.getPatternValues()) writeEndpoints.add(method + " " + path);
        });
        assertThat(writeEndpoints).as("write endpoints without an entry in EXPECTED_AUDIT_ACTIONS (add the audit entry and its test)")
                .isSubsetOf(EXPECTED_AUDIT_ACTIONS.keySet());
        assertThat(EXPECTED_AUDIT_ACTIONS.keySet()).as("entries for endpoints that no longer exist").isSubsetOf(writeEndpoints);
        Set<String> knownActions = Arrays.stream(AuditAction.values()).map(AuditAction::storedName).collect(Collectors.toSet());
        assertThat(EXPECTED_AUDIT_ACTIONS.values().stream().flatMap(List::stream).toList()).isSubsetOf(knownActions);
    }

    @Test
    void theRequestIdIsKeptOrMadeAndEchoed() throws Exception {
        String email = TestData.uniqueEmail("coverage.requestid");
        testData.createUser(businessId, "EMPLOYEE", email, null);
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/auth/otp-requests"))).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", trace.requestId));
        assertThat(trace.entry("auth.sign_in_code_requested")).containsEntry("http_method", "POST").containsEntry("request_path", "/api/auth/otp-requests");

        // an id that is too long or has other characters is replaced by a new UUID
        String replacedId = mockMvc.perform(SignIn.fromWebApp(post("/api/auth/otp-requests")).header("X-Request-Id", "bad id; DROP TABLE")
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(replacedId).matches("[0-9a-f-]{36}");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE request_id = ?", Integer.class, replacedId)).isEqualTo(1);
        String longId = "x".repeat(65);
        assertThat(mockMvc.perform(SignIn.fromWebApp(post("/api/auth/otp-requests")).header("X-Request-Id", longId)
                .content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getHeader("X-Request-Id")).isNotEqualTo(longId);
    }

    @Test
    void requestingACodeIsAudited() throws Exception {
        String email = TestData.uniqueEmail("coverage.code");
        UUID userId = testData.createUser(businessId, "EMPLOYEE", email, null);
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/auth/otp-requests"))).content("{\"email\":\"" + email + "\"}")).andExpect(status().isOk());
        assertThat(trace.entry("auth.sign_in_code_requested")).containsEntry("outcome", "SUCCESS").containsEntry("subject_id", userId)
                .containsEntry("business_id", businessId).containsEntry("category", "auth").containsEntry("source", "STANDALONE");
    }

    @Test
    void signingInIsAudited() throws Exception {
        String email = TestData.uniqueEmail("coverage.signin");
        UUID userId = testData.createUser(businessId, "MANAGER", email, null);
        SignIn.requestCode(mockMvc, email);
        AuditTrace wrongTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(wrongTrace.on(SignIn.fromWebApp(post("/api/auth/sessions"))).content("{\"email\":\"" + email + "\",\"otp\":\"999999\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(wrongTrace.entry("auth.sign_in_failed")).containsEntry("outcome", "DENIED").containsEntry("reason", "wrong_code")
                .containsEntry("subject_id", userId);

        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/auth/sessions"))).header("User-Agent", "Coverage Browser/1.0")
                .content("{\"email\":\"" + email + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(status().isCreated());
        Map<String, Object> entry = trace.entry("auth.signed_in");
        assertThat(entry).containsEntry("outcome", "SUCCESS").containsEntry("actor_id", userId).containsEntry("actor_role", "MANAGER")
                .containsEntry("subject_id", userId).containsEntry("business_id", businessId).containsEntry("user_agent", "Coverage Browser/1.0");
        assertThat((String) entry.get("after_json")).doesNotContain(SignIn.CODE);
    }

    @Test
    void refusedAndReusedRefreshesAreAudited() throws Exception {
        String email = TestData.uniqueEmail("coverage.refresh");
        UUID userId = testData.createUser(businessId, "EMPLOYEE", email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);
        mockMvc.perform(SignIn.fromWebApp(post("/api/auth/session-refreshes")).cookie(cookies.refreshCookie())).andExpect(status().isCreated());

        AuditTrace reuseTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(reuseTrace.on(SignIn.fromWebApp(post("/api/auth/session-refreshes"))).cookie(cookies.refreshCookie()))
                .andExpect(status().isUnauthorized());
        assertThat(reuseTrace.entry("auth.session_revoked_refresh_reuse")).containsEntry("outcome", "DENIED").containsEntry("subject_id", userId)
                .containsEntry("business_id", businessId);

        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(SignIn.fromWebApp(post("/api/auth/session-refreshes"))).cookie(cookies.refreshCookie()))
                .andExpect(status().isUnauthorized());
        assertThat(refusedTrace.entry("auth.session_refresh_failed")).containsEntry("outcome", "DENIED").containsEntry("reason", "revoked")
                .containsEntry("subject_id", userId);
    }

    @Test
    void signingOutIsAudited() throws Exception {
        String email = TestData.uniqueEmail("coverage.signout");
        UUID userId = testData.createUser(businessId, "EMPLOYEE", email, null);
        SessionCookies cookies = SignIn.session(mockMvc, email);
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(delete("/api/auth/sessions/current"))).cookie(cookies.accessCookie(), cookies.refreshCookie()))
                .andExpect(status().isOk());
        assertThat(trace.entry("auth.signed_out")).containsEntry("outcome", "SUCCESS").containsEntry("actor_id", userId).containsEntry("subject_id", userId)
                .containsEntry("actor_role", "EMPLOYEE");
    }

    @Test
    void creatingABusinessIsAudited() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        String response = mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/businesses"))).cookie(superAdminCookie)
                        .content("{\"name\":\"Covered Co\",\"owner\":{\"firstName\":\"Nia\",\"lastName\":\"New\",\"email\":\"" + TestData.uniqueEmail("covered") + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID newBusinessId = UUID.fromString(JsonPath.read(response, "$.data.id"));
        UUID newOwnerId = UUID.fromString(JsonPath.read(response, "$.data.ownerId"));
        assertThat(trace.entry("business.created")).containsEntry("actor_id", superAdminId).containsEntry("actor_role", "SUPER_ADMIN")
                .containsEntry("business_id", newBusinessId).containsEntry("outcome", "SUCCESS").containsEntry("category", "business");
        assertThat(trace.entry("employee.created")).containsEntry("subject_id", newOwnerId).containsEntry("category", "people");
    }

    @Test
    void updatingABusinessIsAudited() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(patch("/api/businesses/" + businessId))).cookie(superAdminCookie).content("{\"staleWorkdayHours\":9}"))
                .andExpect(status().isOk());
        Map<String, Object> entry = trace.entry("business.updated");
        assertThat(entry).containsEntry("actor_id", superAdminId).containsEntry("business_id", businessId).containsEntry("entity_id", businessId.toString());
        assertThat((String) entry.get("before_json")).contains("\"staleWorkdayHours\": 14");
        assertThat((String) entry.get("after_json")).contains("\"staleWorkdayHours\": 9");
    }

    @Test
    void suspendingAndReactivatingABusinessIsAudited() throws Exception {
        AuditTrace suspendTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(suspendTrace.on(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status"))).cookie(superAdminCookie)
                .content("{\"isSuspended\":true,\"reason\":\"Unpaid\"}")).andExpect(status().isOk());
        assertThat(suspendTrace.entry("business.suspended")).containsEntry("reason", "Unpaid").containsEntry("business_id", businessId);
        AuditTrace reactivateTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(reactivateTrace.on(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/status"))).cookie(superAdminCookie)
                .content("{\"isSuspended\":false}")).andExpect(status().isOk());
        assertThat(reactivateTrace.entry("business.reactivated")).containsEntry("actor_id", superAdminId);
    }

    @Test
    void linkingAndUnlinkingABusinessToJurisoneIsAudited() throws Exception {
        UUID jurisoneBusinessId = UUID.randomUUID();
        AuditTrace linkTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(linkTrace.on(SignIn.fromWebApp(put("/api/businesses/" + businessId + "/jurisone-link"))).cookie(superAdminCookie)
                .content("{\"jurisoneBusinessId\":\"" + jurisoneBusinessId + "\"}")).andExpect(status().isOk());
        assertThat(linkTrace.entry("integration.business_linked")).containsEntry("actor_id", superAdminId).containsEntry("actor_role", "SUPER_ADMIN")
                .containsEntry("business_id", businessId).containsEntry("category", "integration").containsEntry("outcome", "SUCCESS")
                .containsEntry("entity_type", "business").containsEntry("entity_id", businessId.toString());
        AuditTrace unlinkTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(unlinkTrace.on(SignIn.fromWebApp(delete("/api/businesses/" + businessId + "/jurisone-link"))).cookie(superAdminCookie))
                .andExpect(status().isOk());
        assertThat(unlinkTrace.entry("integration.business_unlinked")).containsEntry("actor_id", superAdminId).containsEntry("business_id", businessId);
    }

    @Test
    void changingTheOwnBusinessSettingsIsAudited() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(patch("/api/business"))).cookie(ownerCookie).content("{\"displayTimeZones\":[\"UTC\"]}"))
                .andExpect(status().isOk());
        Map<String, Object> entry = trace.entry("business.updated");
        assertThat(entry).containsEntry("actor_id", ownerId).containsEntry("actor_role", "ADMIN").containsEntry("business_id", businessId);
        assertThat((String) entry.get("after_json")).contains("\"displayTimeZones\": [\"UTC\"]");
    }

    @Test
    void addingAnEmployeeIsAuditedAndARefusedOneToo() throws Exception {
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        String response = mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/employees"))).cookie(ownerCookie)
                        .content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"email\":\"" + TestData.uniqueEmail("covered.employee") + "\",\"managerId\":\""
                                + managerId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID employeeId = UUID.fromString(JsonPath.read(response, "$.data.id"));
        assertThat(trace.entry("employee.created")).containsEntry("actor_id", ownerId).containsEntry("subject_id", employeeId)
                .containsEntry("entity_type", "user").containsEntry("entity_id", employeeId.toString());

        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(SignIn.fromWebApp(post("/api/employees"))).cookie(ownerCookie)
                        .content("{\"firstName\":\"Al\",\"lastName\":\"Admin\",\"email\":\"" + TestData.uniqueEmail("refused") + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(refusedTrace.entry("employee.change_refused")).containsEntry("outcome", "DENIED").containsEntry("reason", "ROLE_NOT_ASSIGNABLE")
                .containsEntry("actor_id", ownerId);
    }

    @Test
    void changingAnEmployeeIsAuditedWithEveryChangedFieldAndARefusalToo() throws Exception {
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", TestData.uniqueEmail("covered.change"), null, "Ben", "Carter", "C-1");
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(patch("/api/employees/" + employeeId))).cookie(ownerCookie)
                .content("{\"role\":\"MANAGER\",\"managerId\":\"" + managerId + "\",\"employeeCode\":null}")).andExpect(status().isOk());
        Map<String, Object> entry = trace.entry("employee.updated");
        assertThat(entry).containsEntry("subject_id", employeeId).containsEntry("actor_id", ownerId);
        assertThat((String) entry.get("before_json")).contains("\"role\": \"EMPLOYEE\"").contains("\"managerId\": null").contains("\"employeeCode\": \"C-1\"");
        assertThat((String) entry.get("after_json")).contains("\"role\": \"MANAGER\"").contains("\"managerId\": \"" + managerId + "\"")
                .contains("\"employeeCode\": null");

        // the manager reports to the new manager: a loop is refused, the change rolled back, the refusal recorded
        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(SignIn.fromWebApp(patch("/api/employees/" + managerId))).cookie(ownerCookie)
                .content("{\"managerId\":\"" + employeeId + "\",\"firstName\":\"Changed\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(refusedTrace.entry("employee.change_refused")).containsEntry("reason", "MANAGER_CYCLE").containsEntry("subject_id", managerId)
                .containsEntry("outcome", "DENIED");
        assertThat(jdbcTemplate.queryForObject("SELECT first_name FROM users WHERE id = ?", String.class, managerId)).isEqualTo("Manish");
        assertThat(refusedTrace.count("employee.updated")).isZero();
    }

    @Test
    void deactivatingClosesTheWorkdayAndReactivatingIsAuditedAndRefusalsToo() throws Exception {
        String email = TestData.uniqueEmail("covered.leaver");
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", email, managerId);
        Cookie employeeCookie = SignIn.session(mockMvc, email).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/me/time-events")).cookie(employeeCookie).content("{\"type\":\"START\"}")).andExpect(status().isCreated());

        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(put("/api/employees/" + employeeId + "/status"))).cookie(ownerCookie)
                .content("{\"isActive\":false,\"reason\":\"Left\"}")).andExpect(status().isOk());
        assertThat(trace.entry("employee.deactivated")).containsEntry("subject_id", employeeId).containsEntry("reason", "Left");
        assertThat(trace.entry("workday.closed")).containsEntry("subject_id", employeeId).containsEntry("actor_id", ownerId)
                .containsEntry("category", "time").containsEntry("entity_type", "workday");

        AuditTrace reactivateTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(reactivateTrace.on(SignIn.fromWebApp(put("/api/employees/" + employeeId + "/status"))).cookie(ownerCookie)
                .content("{\"isActive\":true}")).andExpect(status().isOk());
        assertThat(reactivateTrace.entry("employee.reactivated")).containsEntry("subject_id", employeeId);

        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(SignIn.fromWebApp(put("/api/employees/" + ownerId + "/status"))).cookie(ownerCookie)
                .content("{\"isActive\":false}")).andExpect(status().isUnprocessableEntity());
        assertThat(refusedTrace.entry("employee.change_refused")).containsEntry("reason", "CANNOT_DEACTIVATE_SELF").containsEntry("subject_id", ownerId);
    }

    @Test
    void everyTimeEventIsAudited() throws Exception {
        String email = TestData.uniqueEmail("covered.tracker");
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", email, managerId);
        Cookie employeeCookie = SignIn.session(mockMvc, email).accessCookie();
        Map<String, String> actionByEvent = new TreeMap<>();
        actionByEvent.put("1 START", "time.workday_started");
        actionByEvent.put("2 BREAK", "time.break_started");
        actionByEvent.put("3 RESUME", "time.work_resumed");
        actionByEvent.put("4 END", "time.workday_ended");
        actionByEvent.put("5 START", "time.workday_reopened");
        for (Map.Entry<String, String> event : actionByEvent.entrySet()) {
            AuditTrace trace = new AuditTrace(jdbcTemplate);
            mockMvc.perform(trace.on(SignIn.fromWebApp(post("/api/me/time-events"))).cookie(employeeCookie)
                    .content("{\"type\":\"" + event.getKey().substring(2) + "\"}")).andExpect(status().isCreated());
            assertThat(trace.entry(event.getValue())).as(event.getKey()).containsEntry("actor_id", employeeId).containsEntry("subject_id", employeeId)
                    .containsEntry("business_id", businessId).containsEntry("outcome", "SUCCESS").containsEntry("category", "time")
                    .containsEntry("source", "STANDALONE").containsEntry("request_path", "/api/me/time-events");
        }
        // a repeat changes nothing and writes nothing
        AuditTrace repeatTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(repeatTrace.on(SignIn.fromWebApp(post("/api/me/time-events"))).cookie(employeeCookie).content("{\"type\":\"START\"}"))
                .andExpect(status().isOk());
        assertThat(repeatTrace.entries()).isEmpty();
    }

    @Test
    void correctingAWorkOrBreakIntervalIsAuditedAndARefusalToo() throws Exception {
        String email = TestData.uniqueEmail("covered.corrected");
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", email, managerId);
        Cookie employeeCookie = SignIn.session(mockMvc, email).accessCookie();
        Cookie managerCookie = SignIn.session(mockMvc, managerEmail).accessCookie();
        Instant dayStart = Instant.now().minus(Duration.ofHours(4)).truncatedTo(ChronoUnit.SECONDS);
        String workdayId;
        try {
            clock.setTo(dayStart);
            workdayId = JsonPath.read(timeEvent(employeeCookie, "START"), "$.data.workdayId");
            clock.advance(Duration.ofHours(1));
            timeEvent(employeeCookie, "BREAK");
            clock.advance(Duration.ofMinutes(30));
            timeEvent(employeeCookie, "RESUME");
            clock.advance(Duration.ofHours(1));
            timeEvent(employeeCookie, "END");
        } finally {
            clock.followSystemTime();
        }
        UUID firstWorkSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at LIMIT 1",
                UUID.class, workdayId);
        UUID breakSessionId = jdbcTemplate.queryForObject("SELECT id FROM break_sessions WHERE workday_id = ?::uuid", UUID.class, workdayId);

        AuditTrace workTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(workTrace.on(SignIn.fromWebApp(patch("/api/workdays/" + workdayId + "/work-sessions/" + firstWorkSessionId))).cookie(managerCookie)
                .content("{\"startedAt\":\"" + dayStart.plus(Duration.ofMinutes(5)) + "\",\"reason\":\"Started later\"}")).andExpect(status().isOk());
        assertThat(workTrace.entry("time.session_adjusted")).containsEntry("actor_id", managerId).containsEntry("actor_role", "MANAGER")
                .containsEntry("subject_id", employeeId).containsEntry("business_id", businessId).containsEntry("category", "time")
                .containsEntry("outcome", "SUCCESS").containsEntry("entity_type", "work_session").containsEntry("entity_id", firstWorkSessionId.toString())
                .containsEntry("reason", "Started later").containsEntry("http_method", "PATCH");

        AuditTrace breakTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(breakTrace.on(SignIn.fromWebApp(patch("/api/workdays/" + workdayId + "/break-sessions/" + breakSessionId))).cookie(managerCookie)
                .content("{\"endedAt\":\"" + dayStart.plus(Duration.ofMinutes(80)) + "\",\"reason\":\"Short break\"}")).andExpect(status().isOk());
        assertThat(breakTrace.entry("time.session_adjusted")).containsEntry("entity_type", "break_session").containsEntry("subject_id", employeeId);

        // the break would come before any work: refused (422), nothing changed, the refusal recorded
        AuditTrace refusedTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(refusedTrace.on(SignIn.fromWebApp(patch("/api/workdays/" + workdayId + "/break-sessions/" + breakSessionId))).cookie(managerCookie)
                .content("{\"startedAt\":\"" + dayStart.minus(Duration.ofMinutes(10)) + "\",\"endedAt\":\"" + dayStart.minus(Duration.ofMinutes(5))
                        + "\",\"reason\":\"Wrong break\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(refusedTrace.entry("time.correction_refused")).containsEntry("outcome", "DENIED").containsEntry("category", "time")
                .containsEntry("reason", "WORKDAY_MUST_START_WITH_WORK").containsEntry("actor_id", managerId).containsEntry("subject_id", employeeId)
                .containsEntry("entity_type", "workday").containsEntry("entity_id", workdayId);
        assertThat(refusedTrace.count("time.session_adjusted")).isZero();

        AuditTrace refusedWorkTrace = new AuditTrace(jdbcTemplate);
        UUID secondWorkSessionId = jdbcTemplate.queryForObject("SELECT id FROM work_sessions WHERE workday_id = ?::uuid ORDER BY started_at DESC LIMIT 1",
                UUID.class, workdayId);
        mockMvc.perform(refusedWorkTrace.on(SignIn.fromWebApp(patch("/api/workdays/" + workdayId + "/work-sessions/" + secondWorkSessionId)))
                .cookie(managerCookie).content("{\"startedAt\":\"" + dayStart.plus(Duration.ofMinutes(30)) + "\",\"reason\":\"Overlapping\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(refusedWorkTrace.entry("time.correction_refused")).containsEntry("reason", "TIME_INTERVAL_OVERLAP");
    }

    @Test
    void endingSomebodysOpenWorkdayIsAudited() throws Exception {
        String email = TestData.uniqueEmail("covered.forgotten");
        UUID employeeId = testData.createUser(businessId, "EMPLOYEE", email, managerId);
        Cookie employeeCookie = SignIn.session(mockMvc, email).accessCookie();
        String workdayId = JsonPath.read(timeEvent(employeeCookie, "START"), "$.data.workdayId");
        AuditTrace trace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(trace.on(SignIn.fromWebApp(put("/api/workdays/" + workdayId + "/status"))).cookie(ownerCookie)
                .content("{\"status\":\"DAY_ENDED\",\"reason\":\"Forgot to end\"}")).andExpect(status().isOk());
        assertThat(trace.entry("time.workday_closed")).containsEntry("actor_id", ownerId).containsEntry("actor_role", "ADMIN")
                .containsEntry("subject_id", employeeId).containsEntry("category", "time").containsEntry("outcome", "SUCCESS")
                .containsEntry("entity_type", "workday").containsEntry("entity_id", workdayId).containsEntry("reason", "Forgot to end");
        // ending it again changes nothing and writes nothing
        AuditTrace repeatTrace = new AuditTrace(jdbcTemplate);
        mockMvc.perform(repeatTrace.on(SignIn.fromWebApp(put("/api/workdays/" + workdayId + "/status"))).cookie(ownerCookie)
                .content("{\"status\":\"DAY_ENDED\",\"reason\":\"Forgot to end\"}")).andExpect(status().isOk());
        assertThat(repeatTrace.entries()).isEmpty();
    }

    private String timeEvent(Cookie cookie, String type) throws Exception {
        return mockMvc.perform(SignIn.fromWebApp(post("/api/me/time-events")).cookie(cookie).content("{\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
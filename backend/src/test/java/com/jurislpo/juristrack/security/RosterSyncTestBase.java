package com.jurislpo.juristrack.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.TestData;
import com.jurislpo.juristrack.service.AuditAggregation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Plays JurisOne's roster push in the tests: one JurisOne business per test, bodies built from maps, a fresh service token (and request
 * id) per call, and the stored rows read back with plain SQL.
 */
abstract class RosterSyncTestBase extends EmbeddedIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditAggregation auditAggregation;
    TestData testData;
    UUID jurisoneBusinessId;
    String businessName;
    /** The request id of the latest call (stored with its audit entries). */
    String requestId;

    @BeforeEach
    void setUpRosterSync() {
        testData = new TestData(jdbcTemplate);
        auditAggregation.clear();
        jurisoneBusinessId = UUID.randomUUID();
        businessName = "Roster Business " + jurisoneBusinessId.toString().substring(0, 8);
    }

    /** One employee as JurisOne lists them (mutable: a test adds {@code shiftCode}, {@code schedule}, ...). */
    static Map<String, Object> person(UUID employeeId, String email, String role, UUID managerEmployeeId) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("employeeId", employeeId.toString());
        person.put("email", email);
        person.put("firstName", "Asha");
        person.put("lastName", "Rao");
        person.put("employeeCode", null);
        person.put("role", role);
        person.put("managerEmployeeId", managerEmployeeId == null ? null : managerEmployeeId.toString());
        person.put("isActive", true);
        return person;
    }

    static Map<String, Object> person(UUID employeeId, String role, UUID managerEmployeeId) {
        return person(employeeId, TestData.uniqueEmail("roster"), role, managerEmployeeId);
    }

    static Map<String, Object> shift(String code, String name, String startTime, String endTime) {
        Map<String, Object> shift = new LinkedHashMap<>();
        shift.put("code", code);
        shift.put("name", name);
        shift.put("startTime", startTime);
        shift.put("endTime", endTime);
        return shift;
    }

    static Map<String, Object> scheduleEntry(String date, String shiftCode) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("date", date);
        entry.put("shiftCode", shiftCode);
        return entry;
    }

    @SafeVarargs
    static Map<String, Object> body(List<Map<String, Object>> shifts, Map<String, Object>... employees) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shifts", shifts);
        body.put("employees", List.of(employees));
        return body;
    }

    @SafeVarargs
    static Map<String, Object> body(Map<String, Object>... employees) {
        return body(List.of(), employees);
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    ResultActions sync(Object body) throws Exception {
        return syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName), json(body));
    }

    /** Calls the roster sync with this exact token; a new request id is kept in {@link #requestId}. */
    ResultActions syncAs(String token, String content) throws Exception {
        requestId = "roster-" + UUID.randomUUID();
        return mockMvc.perform(put("/api/integration/employees").header("Authorization", "Bearer " + token).header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    String answerOf(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    int number(String answer, String path) {
        return JsonPath.<Integer>read(answer, path);
    }

    List<String> skippedReasons(String answer) {
        return JsonPath.read(answer, "$.data.skipped[*].reason");
    }

    UUID businessId() {
        return jdbcTemplate.queryForObject("SELECT id FROM businesses WHERE jurisone_business_id = ?", UUID.class, jurisoneBusinessId);
    }

    /** The stored user linked to this JurisOne employee. */
    Map<String, Object> userOf(UUID jurisoneEmployeeId) {
        return jdbcTemplate.queryForMap("SELECT id, business_id, first_name, last_name, email, employee_code, role::text AS role, manager_id, is_active, "
                + "is_login_enabled, is_deactivated_by_roster_sync, jurisone_employee_id FROM users WHERE jurisone_employee_id = ?", jurisoneEmployeeId);
    }

    UUID userIdOf(UUID jurisoneEmployeeId) {
        return (UUID) userOf(jurisoneEmployeeId).get("id");
    }

    /** The audit entries (action names) written by the latest call, oldest first. */
    List<String> auditActionsOfLatestCall() {
        return jdbcTemplate.queryForList("SELECT action FROM audit_logs WHERE request_id = ? ORDER BY created_at, action", String.class, requestId);
    }

    List<String> auditActionsOfLatestCall(String... excludedActions) {
        List<String> actions = new ArrayList<>(auditActionsOfLatestCall());
        for (String excludedAction : excludedActions) actions.removeIf(excludedAction::equals);
        return actions;
    }
}

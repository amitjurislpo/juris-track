package com.jurislpo.juristrack.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.AdjustableClock;
import com.jurislpo.juristrack.IntegrationTest;
import com.jurislpo.juristrack.service.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The shift templates ({@code /api/v1/shifts}), the shift of an employee ({@code PUT /api/v1/employees/{id}/shift}) and the roster
 * ({@code /api/v1/schedule-entries}, {@code /api/v1/me/schedule}, {@code /api/v1/employees/{id}/schedule-entries}): rights, validation, audit,
 * history, precedence (roster entry, then assignment, then the default shift), rotational weeks off, work on a week off, the board's
 * {@code weekOff} figure and the extra time in reports and CSV. Business time zone Asia/Kolkata; "now" is Monday 5 October 2026, 10:00
 * UTC (15:30 in India).
 */
class ShiftApiTest extends IntegrationTest {

    private static final String SETUP_TIME = "2026-10-05T10:00:00Z";
    private static final int HOUR = 3600;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RateLimiter rateLimiter;
    @Autowired AdjustableClock clock;
    TeamFixture team;

    @BeforeEach
    void setUp() {
        team = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        clock.setTo(SETUP_TIME);
    }

    @AfterEach
    void restoreClock() {
        clock.followSystemTime();
    }

    private UUID createShift(String name, String startTime, String endTime) throws Exception {
        String answer = team.postJson(team.ownerId, "/api/v1/shifts", "{\"name\":\"" + name + "\",\"startTime\":\"" + startTime + "\",\"endTime\":\""
                + endTime + "\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(answer, "$.data.id"));
    }

    private UUID defaultShiftId() throws Exception {
        String answer = team.read(team.ownerId, "/api/v1/shifts").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(answer, "$.data[?(@.isDefault == true)].id");
        return UUID.fromString(ids.get(0));
    }

    private int auditEntries(String action) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = ?", Integer.class, team.businessId, action);
    }

    private String dayOf(String answer, String date) {
        List<Map<String, Object>> days = JsonPath.read(answer, "$.data.days[?(@.date == '" + date + "')]");
        assertThat(days).hasSize(1);
        return days.get(0).get("source") + "/" + days.get(0).get("shiftName") + "/" + days.get(0).get("isOff");
    }

    @Test
    void everyBusinessHasTheDefaultCalendarDayShift() throws Exception {
        String answer = team.read(team.employeeId, "/api/v1/shifts").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(answer, "$.data")).hasSize(1);
        assertThat((String) JsonPath.read(answer, "$.data[0].name")).isEqualTo("General day");
        assertThat((String) JsonPath.read(answer, "$.data[0].startTime")).isEqualTo("00:00");
        assertThat((String) JsonPath.read(answer, "$.data[0].endTime")).isEqualTo("00:00");
        assertThat((Boolean) JsonPath.read(answer, "$.data[0].crossesMidnight")).isTrue();
        assertThat((Integer) JsonPath.read(answer, "$.data[0].scheduledMinutes")).isEqualTo(1440);
        assertThat((Integer) JsonPath.read(answer, "$.data[0].earlyStartMinutes")).isZero();
        assertThat((Boolean) JsonPath.read(answer, "$.data[0].isDefault")).isTrue();
        assertThat((Boolean) JsonPath.read(answer, "$.data[0].isActive")).isTrue();
        assertThat((Integer) JsonPath.read(answer, "$.data[0].employeeCount")).as("everybody works the default shift").isEqualTo(7);
    }

    @Test
    void onlyTheOwnerWritesAndEveryoneOfTheBusinessReads() throws Exception {
        UUID shiftId = createShift("Evening", "16:00", "01:00");
        String body = "{\"name\":\"Other\",\"startTime\":\"10:00\",\"endTime\":\"18:00\"}";
        for (UUID userId : List.of(team.managerAId, team.employeeId)) {
            team.postJson(userId, "/api/v1/shifts", body).andExpect(status().isForbidden());
            team.patchJson(userId, "/api/v1/shifts/" + shiftId, "{\"name\":\"X\"}").andExpect(status().isForbidden());
            team.putJson(userId, "/api/v1/shifts/" + shiftId + "/status", "{\"isActive\":false}").andExpect(status().isForbidden());
            team.putJson(userId, "/api/v1/employees/" + team.employeeOfAId + "/shift", "{\"shiftId\":\"" + shiftId + "\"}").andExpect(status().isForbidden());
            team.putJson(userId, "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries/2026-10-12", "{\"shiftId\":null}").andExpect(status().isForbidden());
            team.deleteJson(userId, "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries/2026-10-12").andExpect(status().isForbidden());
            team.putJson(userId, "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries", "{\"entries\":[{\"date\":\"2026-10-12\"}]}")
                    .andExpect(status().isForbidden());
            team.read(userId, "/api/v1/shifts").andExpect(status().isOk());
            team.read(userId, "/api/v1/shifts/" + shiftId).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Evening"))
                    .andExpect(jsonPath("$.data.crossesMidnight").value(true)).andExpect(jsonPath("$.data.scheduledMinutes").value(540));
        }
        team.read(team.employeeId, "/api/v1/schedule-entries").andExpect(status().isForbidden());
        team.read(team.managerAId, "/api/v1/schedule-entries").andExpect(status().isOk());
        team.read(team.employeeId, "/api/v1/me/schedule").andExpect(status().isOk());
        // another business never sees or uses this shift
        TeamFixture other = new TeamFixture(mockMvc, jdbcTemplate, rateLimiter);
        other.read(other.ownerId, "/api/v1/shifts/" + shiftId).andExpect(status().isNotFound());
        other.putJson(other.ownerId, "/api/v1/employees/" + other.employeeId + "/shift", "{\"shiftId\":\"" + shiftId + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_NOT_FOUND"));
        assertThat(JsonPath.<List<?>>read(other.read(other.ownerId, "/api/v1/shifts").andReturn().getResponse().getContentAsString(), "$.data")).hasSize(1);
    }

    @Test
    void creatingAShiftValidatesAndIsAudited() throws Exception {
        team.postJson(team.ownerId, "/api/v1/shifts", "{\"name\":\"\",\"startTime\":\"25:00\",\"endTime\":\"9\",\"earlyStartMinutes\":721}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("name", "startTime", "endTime",
                        "earlyStartMinutes")));
        UUID shiftId = createShift("US shift", "19:30", "04:30");
        team.postJson(team.ownerId, "/api/v1/shifts", "{\"name\":\"us SHIFT\",\"startTime\":\"10:00\",\"endTime\":\"18:00\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_NAME_IN_USE"));
        team.read(team.ownerId, "/api/v1/shifts/" + shiftId).andExpect(jsonPath("$.data.earlyStartMinutes").value(180))
                .andExpect(jsonPath("$.data.lateEndMinutes").value(180)).andExpect(jsonPath("$.data.isDefault").value(false))
                .andExpect(jsonPath("$.data.employeeCount").value(0));
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT actor_id, entity_id, after::text AS after_json FROM audit_logs WHERE action = 'shift.created'"
                + " AND business_id = ?", team.businessId);
        assertThat(entry.get("actor_id")).isEqualTo(team.ownerId);
        assertThat(entry.get("entity_id")).isEqualTo(shiftId.toString());
        assertThat((String) JsonPath.read((String) entry.get("after_json"), "$.startTime")).isEqualTo("19:30");
    }

    @Test
    void changingAShiftIsAuditedAndTheDefaultShiftKeepsItsTimes() throws Exception {
        UUID shiftId = createShift("UK shift", "14:00", "23:00");
        team.patchJson(team.ownerId, "/api/v1/shifts/" + shiftId, "{\"endTime\":\"22:30\",\"lateEndMinutes\":60}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endTime").value("22:30")).andExpect(jsonPath("$.data.scheduledMinutes").value(510));
        assertThat(auditEntries("shift.updated")).isEqualTo(1);
        team.patchJson(team.ownerId, "/api/v1/shifts/" + shiftId, "{\"endTime\":\"22:30\"}").andExpect(status().isOk());
        assertThat(auditEntries("shift.updated")).as("nothing changed, nothing audited").isEqualTo(1);
        team.patchJson(team.ownerId, "/api/v1/shifts/" + shiftId, "{\"startTime\":\"nine\"}").andExpect(status().isBadRequest());

        UUID defaultId = defaultShiftId();
        team.patchJson(team.ownerId, "/api/v1/shifts/" + defaultId, "{\"startTime\":\"06:00\"}").andExpect(status().isBadRequest());
        team.patchJson(team.ownerId, "/api/v1/shifts/" + defaultId, "{\"name\":\"Calendar day\"}").andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/shifts/" + defaultId + "/status", "{\"isActive\":false}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFAULT_SHIFT_REQUIRED"));
    }

    @Test
    void aShiftWithActiveEmployeesCannotBeDeactivatedAndAnInactiveShiftCannotBeGiven() throws Exception {
        UUID shiftId = createShift("US shift", "19:30", "04:30");
        UUID unusedId = createShift("Spare", "08:00", "16:00");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + shiftId + "\"}").andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/shifts/" + shiftId + "/status", "{\"isActive\":false}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHIFT_IN_USE"));
        team.read(team.ownerId, "/api/v1/shifts/" + shiftId).andExpect(jsonPath("$.data.employeeCount").value(1));
        team.putJson(team.ownerId, "/api/v1/shifts/" + unusedId + "/status", "{\"isActive\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
        assertThat(auditEntries("shift.status_changed")).isEqualTo(1);
        team.putJson(team.ownerId, "/api/v1/shifts/" + unusedId + "/status", "{\"isActive\":false}").andExpect(status().isOk());
        assertThat(auditEntries("shift.status_changed")).isEqualTo(1);
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeOfAId + "/shift", "{\"shiftId\":\"" + unusedId + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_INACTIVE"));
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries/2026-10-12", "{\"shiftId\":\"" + unusedId + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_INACTIVE"));
        team.read(team.ownerId, "/api/v1/shifts?isActive=true").andExpect(jsonPath("$.data.length()").value(2));
        team.read(team.ownerId, "/api/v1/shifts?isActive=false").andExpect(jsonPath("$.data.length()").value(1));
        team.read(team.ownerId, "/api/v1/shifts?isActive=maybe").andExpect(status().isBadRequest());
    }

    @Test
    void assigningAShiftKeepsTheHistoryAndOldWorkdays() throws Exception {
        UUID usShiftId = createShift("US shift", "19:30", "04:30");
        // a workday on the default shift today (15:30)
        String oldWorkdayId = team.applyEvent(team.employeeId, "START");
        clock.setTo("2026-10-05T11:00:00Z");
        team.applyEvent(team.employeeId, "END");

        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\",\"effectiveFrom\":\"2026-10-06\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shift.name").value("General day"));
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\",\"effectiveFrom\":\"2026-10-04\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHIFT_EFFECTIVE_DATE_IN_PAST"));
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"effectiveFrom\":\"2026-10-06\"}").andExpect(status().isBadRequest());
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\",\"effectiveFrom\":\"soon\"}")
                .andExpect(status().isBadRequest());
        assertThat(auditEntries("employee.shift_changed")).isEqualTo(1);
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT before::text AS before_json, after::text AS after_json FROM audit_logs"
                + " WHERE action = 'employee.shift_changed' AND subject_id = ?", team.employeeId);
        assertThat((String) JsonPath.read((String) entry.get("before_json"), "$.shiftName")).isEqualTo("General day");
        assertThat((String) JsonPath.read((String) entry.get("after_json"), "$.shiftName")).isEqualTo("US shift");
        assertThat((String) JsonPath.read((String) entry.get("after_json"), "$.effectiveFrom")).isEqualTo("2026-10-06");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\",\"effectiveFrom\":\"2026-10-06\"}")
                .andExpect(status().isOk());
        assertThat(auditEntries("employee.shift_changed")).as("the same shift again changes nothing").isEqualTo(1);

        // the shift applies from its date on: today is still the default shift, tomorrow evening the US shift
        String employeeList = team.read(team.ownerId, "/api/v1/employees/" + team.employeeId).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(employeeList, "$.data.shift.name")).isEqualTo("General day");
        clock.setTo("2026-10-06T16:00:00Z"); // 21:30 on the 6th
        team.read(team.ownerId, "/api/v1/employees/" + team.employeeId).andExpect(jsonPath("$.data.shift.name").value("US shift"))
                .andExpect(jsonPath("$.data.shift.startTime").value("19:30"));
        String tomorrow = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(tomorrow, "$.data.state.workday.shift.name")).isEqualTo("US shift");
        // the old workday keeps the shift it was started with
        assertThat(jdbcTemplate.queryForObject("SELECT shift_name FROM workdays WHERE id = ?::uuid", String.class, oldWorkdayId)).isEqualTo("General day");
        assertThat(jdbcTemplate.queryForObject("SELECT extract(epoch FROM scheduled_end_at - scheduled_start_at)::int FROM workdays WHERE id = ?::uuid",
                Integer.class, oldWorkdayId)).isEqualTo(24 * HOUR);
        // a later assignment back to the default shift leaves the US workday as it was
        UUID defaultId = defaultShiftId();
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + defaultId + "\",\"effectiveFrom\":\"2026-10-20\"}")
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList("SELECT shift_name FROM workdays WHERE employee_id = ? ORDER BY started_at", String.class, team.employeeId))
                .containsExactly("General day", "US shift");
    }

    @Test
    void theRosterEntryBeatsTheAssignmentAndTheAssignmentBeatsTheDefault() throws Exception {
        UUID usShiftId = createShift("US shift", "19:30", "04:30");
        UUID ukShiftId = createShift("UK shift", "14:00", "23:00");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\",\"effectiveFrom\":\"2026-10-07\"}")
                .andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-08", "{\"shiftId\":\"" + ukShiftId + "\",\"reason\":\"Cover\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("ENTRY")).andExpect(jsonPath("$.data.shiftName").value("UK shift"))
                .andExpect(jsonPath("$.data.reason").value("Cover"));
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-09", "{\"shiftId\":null}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isOff").value(true)).andExpect(jsonPath("$.data.shiftId").value(org.hamcrest.Matchers.nullValue()));

        String answer = team.read(team.ownerId, "/api/v1/schedule-entries?employeeId=" + team.employeeId + "&from=2026-10-05&to=2026-10-10")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(answer, "$.data.rows")).hasSize(1);
        assertThat((String) JsonPath.read(answer, "$.data.rows[0].name")).isEqualTo("Esha Iyer");
        assertThat((String) JsonPath.read(answer, "$.data.rows[0].employeeCode")).isEqualTo("EMP-7");
        assertThat((String) JsonPath.read(answer, "$.data.rows[0].shift.name")).as("the assignment that applies today").isEqualTo("General day");
        assertThat((List<?>) JsonPath.read(answer, "$.data.rows[0].days")).hasSize(6);
        assertThat(dayOfRow(answer, "2026-10-06")).isEqualTo("DEFAULT/General day/false");
        assertThat(dayOfRow(answer, "2026-10-07")).isEqualTo("ASSIGNMENT/US shift/false");
        assertThat(dayOfRow(answer, "2026-10-08")).isEqualTo("ENTRY/UK shift/false");
        assertThat(dayOfRow(answer, "2026-10-09")).isEqualTo("ENTRY/null/true");
        assertThat(dayOfRow(answer, "2026-10-10")).isEqualTo("ASSIGNMENT/US shift/false");

        // the employee reads their own schedule; a manager reads reports (and only reports)
        String own = team.read(team.employeeId, "/api/v1/me/schedule?from=2026-10-08&to=2026-10-09").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(dayOf(own, "2026-10-09")).isEqualTo("ENTRY/null/true");
        String managerView = team.read(team.managerAId, "/api/v1/schedule-entries?from=2026-10-08&to=2026-10-08").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(managerView, "$.data.rows[*].employeeId")).contains(team.employeeId.toString(), team.managerBId.toString(),
                team.employeeOfAId.toString()).doesNotContain(team.employeeOfCId.toString());
        team.read(team.ownerId, "/api/v1/schedule-entries?from=2026-10-01&to=2026-12-31").andExpect(status().isBadRequest());
        team.read(team.ownerId, "/api/v1/schedule-entries?from=2026-10-09&to=2026-10-08").andExpect(status().isBadRequest());

        // removing an entry returns the date to the assignment; removing again changes nothing
        team.deleteJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-08").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ASSIGNMENT")).andExpect(jsonPath("$.data.shiftName").value("US shift"));
        team.deleteJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-08").andExpect(status().isOk());
        assertThat(auditEntries("schedule.entry_set")).isEqualTo(2);
        assertThat(auditEntries("schedule.entry_removed")).isEqualTo(1);
    }

    private String dayOfRow(String answer, String date) {
        List<Map<String, Object>> days = JsonPath.read(answer, "$.data.rows[0].days[?(@.date == '" + date + "')]");
        assertThat(days).hasSize(1);
        return days.get(0).get("source") + "/" + days.get(0).get("shiftName") + "/" + days.get(0).get("isOff");
    }

    @Test
    void theRosterIsSetInBulkIdempotentlyWithOneAuditEntryPerChangedDate() throws Exception {
        UUID ukShiftId = createShift("UK shift", "14:00", "23:00");
        // rotational weeks off: Wednesday of the first week, Friday of the second, Sunday of the third
        String body = "{\"entries\":[{\"date\":\"2026-10-07\",\"shiftId\":null},{\"date\":\"2026-10-16\",\"shiftId\":null,\"reason\":\"Rota\"},"
                + "{\"date\":\"2026-10-25\",\"shiftId\":null},{\"date\":\"2026-10-26\",\"shiftId\":\"" + ukShiftId + "\"}]}";
        String employeePath = "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries";
        team.putJson(team.ownerId, employeePath, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].isOff").value(true)).andExpect(jsonPath("$.data[3].shiftName").value("UK shift"));
        assertThat(auditEntries("schedule.entry_set")).isEqualTo(4);
        team.putJson(team.ownerId, employeePath, body).andExpect(status().isOk());
        assertThat(auditEntries("schedule.entry_set")).as("the same call again changes nothing").isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, team.employeeOfAId))
                .isEqualTo(4);
        // changing one date audits that date only
        team.putJson(team.ownerId, employeePath, "{\"entries\":[{\"date\":\"2026-10-07\",\"shiftId\":\"" + ukShiftId + "\"},{\"date\":\"2026-10-16\",\"shiftId\":null,"
                + "\"reason\":\"Rota\"}]}").andExpect(status().isOk());
        assertThat(auditEntries("schedule.entry_set")).isEqualTo(5);
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT before::text AS before_json, after::text AS after_json FROM audit_logs"
                + " WHERE action = 'schedule.entry_set' AND entity_id = ? ORDER BY created_at DESC LIMIT 1", team.employeeOfAId + "/2026-10-07");
        assertThat((Boolean) JsonPath.read((String) entry.get("before_json"), "$.isOff")).isTrue();
        assertThat((Boolean) JsonPath.read((String) entry.get("after_json"), "$.isOff")).isFalse();

        // all or none: one bad entry refuses the call
        team.putJson(team.ownerId, employeePath, "{\"entries\":[{\"date\":\"2026-11-01\",\"shiftId\":null},{\"date\":\"bad\"}]}").andExpect(status().isBadRequest());
        team.putJson(team.ownerId, employeePath, "{\"entries\":[{\"date\":\"2026-11-01\"},{\"date\":\"2026-11-01\"}]}").andExpect(status().isBadRequest());
        team.putJson(team.ownerId, employeePath, "{\"entries\":[]}").andExpect(status().isBadRequest());
        StringBuilder tooMany = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < 63; i++) tooMany.append(i == 0 ? "" : ",").append("{\"date\":\"").append(java.time.LocalDate.of(2026, 11, 1).plusDays(i)).append("\"}");
        team.putJson(team.ownerId, employeePath, tooMany.append("]}").toString()).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, team.employeeOfAId))
                .isEqualTo(4);
        // too far ahead and unknown people
        team.putJson(team.ownerId, employeePath + "/2027-10-05", "{\"shiftId\":null}").andExpect(status().isOk());
        team.putJson(team.ownerId, employeePath + "/2027-10-06", "{\"shiftId\":null}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_DATE_TOO_FAR"));
        team.putJson(team.ownerId, "/api/v1/employees/" + UUID.randomUUID() + "/schedule-entries/2026-10-12", "{\"shiftId\":null}").andExpect(status().isNotFound());
        team.putJson(team.ownerId, employeePath + "/12-10-2026", "{\"shiftId\":null}").andExpect(status().isBadRequest());
    }

    @Test
    void aDateWithAWorkdayCannotChange() throws Exception {
        team.applyEvent(team.employeeId, "START"); // today, 5 October
        String path = "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-05";
        team.putJson(team.ownerId, path, "{\"shiftId\":null}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEDULE_DATE_HAS_WORKDAY"));
        // a past date without a workday is fine
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-03", "{\"shiftId\":null}").andExpect(status().isOk());
    }

    @Test
    void workOnAWeekOffIsAllowedFlaggedAndAllExtraTime() throws Exception {
        UUID usShiftId = createShift("US shift", "19:30", "04:30");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\"}").andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-06", "{\"shiftId\":null}").andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeOfAId + "/schedule-entries/2026-10-06", "{\"shiftId\":null}").andExpect(status().isOk());

        // the board on the week off: both are NOT_STARTED but counted as week off, not as not started
        clock.setTo("2026-10-06T06:30:00Z"); // 12:00 on the 6th
        String board = team.read(team.ownerId, "/api/v1/team/members").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(board, "$.data.metrics.weekOff")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(board, "$.data.metrics.notStarted")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(board, "$.data.metrics.total")).isEqualTo(6);
        List<Map<String, Object>> offRows = JsonPath.read(board, "$.data.rows[?(@.isOffDay == true)]");
        assertThat(offRows).hasSize(2);
        assertThat((Boolean) JsonPath.read(me(team.employeeId), "$.data.isOffDay")).isTrue();
        assertThat((String) JsonPath.read(me(team.employeeId), "$.data.status")).isEqualTo("NOT_STARTED");

        // the employee works anyway at 12:00: allowed, flagged, the whole time is extra
        String startAnswer = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.workday.date")).isEqualTo("2026-10-06");
        assertThat((Boolean) JsonPath.read(startAnswer, "$.data.state.workday.isOffDay")).isTrue();
        assertThat((Boolean) JsonPath.read(startAnswer, "$.data.state.workday.isOutsideShift")).isTrue();
        clock.setTo("2026-10-06T09:30:00Z"); // 15:00
        String running = board();
        assertThat((Integer) JsonPath.read(running, "$.data.metrics.weekOff")).as("the working one is no longer 'week off'").isEqualTo(1);
        assertThat((Integer) JsonPath.read(running, "$.data.metrics.working")).isEqualTo(1);
        team.applyEvent(team.employeeId, "END");
        String ended = me(team.employeeId);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.productiveSeconds")).isEqualTo(3 * HOUR);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.extraSeconds")).isEqualTo(3 * HOUR);
        assertThat((Integer) JsonPath.read(ended, "$.data.workday.varianceSeconds")).as("a week off has nothing scheduled").isEqualTo(3 * HOUR);
        assertThat((Boolean) JsonPath.read(ended, "$.data.workday.isOffDay")).isTrue();
        assertThat(jdbcTemplate.queryForMap("SELECT is_off_day, is_outside_shift, extra_seconds, shift_name FROM workdays WHERE employee_id = ?", team.employeeId))
                .containsEntry("is_off_day", true).containsEntry("is_outside_shift", true).containsEntry("extra_seconds", 3 * HOUR)
                .containsEntry("shift_name", "US shift");

        // the report and the CSV carry the extra time and the week-off marker
        String report = team.read(team.ownerId, "/api/v1/reports/workdays?range=custom&from=2026-10-06&to=2026-10-06").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(report, "$.data.totals.extraSeconds")).isEqualTo(3 * HOUR);
        assertThat((Boolean) JsonPath.read(report, "$.data.rows[0].isOffDay")).isTrue();
        assertThat((Integer) JsonPath.read(report, "$.data.rows[0].extraSeconds")).isEqualTo(3 * HOUR);
        String csv = new String(mockMvc.perform(get("/api/v1/reports/workdays?range=custom&from=2026-10-06&to=2026-10-06").cookie(team.cookieOf(team.ownerId))
                .header("accept", "text/csv")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).endsWith("Span hours,Breaks,Last break (IST),Extra hours,Shift,Scheduled start (IST),Scheduled end (IST),Outside shift,Week off");
        assertThat(lines[1]).endsWith(",3.00,0.00,3.00,0,,3.00,US shift,19:30,04:30,Yes,Yes");
        String summary = team.read(team.ownerId, "/api/v1/reports/summaries?range=custom&from=2026-10-06&to=2026-10-06&groupBy=employee").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(summary, "$.data.rows[0].extraSeconds")).isEqualTo(3 * HOUR);
    }

    private String board() throws Exception {
        return team.read(team.ownerId, "/api/v1/team/members").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String me(UUID userId) throws Exception {
        return team.read(userId, "/api/v1/me/time").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aStartInTheGapIsAllowedFlaggedAndExtra() throws Exception {
        UUID usShiftId = createShift("US shift", "19:30", "04:30");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\"}").andExpect(status().isOk());
        clock.setTo("2026-10-06T05:30:00Z"); // 11:00 on the 6th: between the 5th's shift (ended 04:30) and the 6th's (19:30)
        String startAnswer = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(startAnswer, "$.data.state.workday.isOutsideShift")).isTrue();
        assertThat((Boolean) JsonPath.read(startAnswer, "$.data.state.workday.isOffDay")).isFalse();
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.workday.date")).as("the nearest occurrence").isEqualTo("2026-10-05");
        clock.setTo("2026-10-06T07:30:00Z");
        String workdayId = team.applyEvent(team.employeeId, "END");
        String ended = team.read(team.employeeId, "/api/v1/workdays/" + workdayId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(ended, "$.data.productiveSeconds")).isEqualTo(2 * HOUR);
        assertThat((Integer) JsonPath.read(ended, "$.data.extraSeconds")).isEqualTo(2 * HOUR);
        assertThat((Boolean) JsonPath.read(ended, "$.data.isOutsideShift")).isTrue();
    }

    @Test
    void aPerDateOverrideChangesTheOccurrenceAStartBelongsTo() throws Exception {
        UUID usShiftId = createShift("US shift", "19:30", "04:30");
        UUID ukShiftId = createShift("UK shift", "14:00", "23:00");
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/shift", "{\"shiftId\":\"" + usShiftId + "\"}").andExpect(status().isOk());
        team.putJson(team.ownerId, "/api/v1/employees/" + team.employeeId + "/schedule-entries/2026-10-05", "{\"shiftId\":\"" + ukShiftId + "\"}")
                .andExpect(status().isOk());
        clock.setTo("2026-10-05T08:30:00Z"); // 14:00 today
        String startAnswer = team.sendEvent(team.employeeId, "START").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(startAnswer, "$.data.state.workday.shift.name")).isEqualTo("UK shift");
        assertThat((Boolean) JsonPath.read(startAnswer, "$.data.state.workday.isOutsideShift")).isFalse();
        assertThat((Integer) JsonPath.read(startAnswer, "$.data.state.workday.shift.scheduledSeconds")).isEqualTo(9 * HOUR);
    }

    @Test
    void theDefaultShiftKeepsTheCalendarDayBehaviour() throws Exception {
        clock.setTo("2026-10-05T18:00:00Z"); // 23:30
        String workdayId = team.applyEvent(team.employeeId, "START");
        clock.setTo("2026-10-05T19:30:00Z"); // 01:00 on the 6th: still the same open workday, dated the 5th
        String state = me(team.employeeId);
        assertThat((String) JsonPath.read(state, "$.data.workday.date")).isEqualTo("2026-10-05");
        assertThat((String) JsonPath.read(state, "$.data.shiftDate")).isEqualTo("2026-10-06");
        assertThat((Integer) JsonPath.read(state, "$.data.workday.extraSeconds")).as("the calendar day has no extra time").isZero();
        team.applyEvent(team.employeeId, "END");
        assertThat(jdbcTemplate.queryForObject("SELECT extra_seconds FROM workdays WHERE id = ?::uuid", Integer.class, workdayId)).isZero();
        // once ended, midnight has passed: the person is NOT_STARTED for the 6th (the old calendar-day behaviour)
        assertThat((String) JsonPath.read(me(team.employeeId), "$.data.status")).isEqualTo("NOT_STARTED");
    }
}

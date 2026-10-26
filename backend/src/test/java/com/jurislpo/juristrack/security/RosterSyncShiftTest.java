package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.security.IntegrationTokens.Employee;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The roster sync's shifts and roster: shifts upserted by code (future occurrences only), the shift assigned from today (history kept, the
 * same shift again changes nothing), roster entries and weeks off, and the shift code a first user token carries.
 */
class RosterSyncShiftTest extends RosterSyncTestBase {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static String daysFromToday(int days) {
        return LocalDate.now(BUSINESS_ZONE).plusDays(days).toString();
    }

    private Map<String, Object> shiftRow(String code) {
        return jdbcTemplate.queryForMap("SELECT id, name, start_minute, end_minute, early_start_minutes, late_end_minutes, is_default, is_active, code "
                + "FROM shifts WHERE business_id = ? AND code = ?", businessId(), code);
    }

    private UUID currentShiftIdOf(UUID jurisoneEmployeeId) {
        return jdbcTemplate.queryForObject("SELECT shift_id FROM employee_shift_assignments WHERE employee_id = ? ORDER BY effective_from DESC LIMIT 1",
                UUID.class, userIdOf(jurisoneEmployeeId));
    }

    @Test
    void shiftsAreCreatedUpdatedAndLeftAloneByCode() throws Exception {
        Map<String, Object> us = shift("US", "US shift", "19:30", "04:30");
        us.put("earlyStartMinutes", 120);
        us.put("lateEndMinutes", 60);
        Map<String, Object> uk = shift("UK", "UK shift", "14:00", "23:00");
        String answer = answerOf(sync(body(List.of(us, uk))).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.shifts.created")).isEqualTo(2);
        assertThat(shiftRow("US")).containsEntry("name", "US shift").containsEntry("start_minute", 1170).containsEntry("end_minute", 270)
                .containsEntry("early_start_minutes", 120).containsEntry("late_end_minutes", 60).containsEntry("is_default", false).containsEntry("is_active", true);
        assertThat(shiftRow("UK")).containsEntry("early_start_minutes", 180).containsEntry("late_end_minutes", 180);
        assertThat(auditActionsOfLatestCall()).containsExactlyInAnyOrder("integration.business_provisioned", "shift.created", "shift.created",
                "integration.roster_synced");

        String repeat = answerOf(sync(body(List.of(us, uk))).andExpect(status().isOk()));
        assertThat(number(repeat, "$.data.shifts.unchanged")).isEqualTo(2);
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");

        // JurisOne changed the US shift's name and times; the UK shift is not mentioned and stays as it is
        us.put("name", "US night shift");
        us.put("startTime", "20:00");
        String changed = answerOf(sync(body(List.of(us))).andExpect(status().isOk()));
        assertThat(number(changed, "$.data.shifts.updated")).isEqualTo(1);
        assertThat(shiftRow("US")).containsEntry("name", "US night shift").containsEntry("start_minute", 1200);
        assertThat(shiftRow("UK")).containsEntry("name", "UK shift");
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT \"before\"::text AS before_json, \"after\"::text AS after_json FROM audit_logs "
                + "WHERE request_id = ? AND action = 'shift.updated'", requestId);
        assertThat((String) entry.get("before_json")).contains("\"name\": \"US shift\"").contains("\"code\": \"US\"");
        assertThat((String) entry.get("after_json")).contains("\"name\": \"US night shift\"").contains("\"startTime\": \"20:00\"");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE business_id = ?", Integer.class, businessId())).isEqualTo(3);
    }

    @Test
    void anOwnersShiftOfTheSameNameIsAdoptedAndAClashingNameIsReported() throws Exception {
        sync(body()).andExpect(status().isOk());
        UUID businessId = businessId();
        jdbcTemplate.update("INSERT INTO shifts (business_id, name, start_minute, end_minute) VALUES (?, 'Night', 1320, 360)", businessId);
        jdbcTemplate.update("INSERT INTO shifts (business_id, name, start_minute, end_minute, code) VALUES (?, 'Early', 360, 900, 'OTHER')", businessId);
        String answer = answerOf(sync(body(List.of(shift("N", "night", "22:30", "06:30"), shift("E2", "early", "05:00", "13:00"),
                shift("G", "General day", "09:00", "18:00")))).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.shifts.updated")).isEqualTo(1);
        assertThat(number(answer, "$.data.shifts.created")).isZero();
        assertThat(JsonPath.<List<Map<String, String>>>read(answer, "$.data.skippedShifts")).extracting(skipped -> skipped.get("code"))
                .containsExactlyInAnyOrder("E2", "G");
        assertThat(JsonPath.<List<String>>read(answer, "$.data.skippedShifts[*].reason")).containsOnly("shift_name_in_use");
        assertThat(shiftRow("N")).containsEntry("name", "night").containsEntry("start_minute", 1350);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE business_id = ?", Integer.class, businessId)).isEqualTo(3);
    }

    @Test
    void aFirstShiftReachesTwoDaysBackAndAChangeKeepsTheHistory() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        person.put("shiftCode", "US");
        List<Map<String, Object>> shifts = List.of(shift("US", "US shift", "19:30", "04:30"), shift("UK", "UK shift", "14:00", "23:00"));
        String answer = answerOf(sync(body(shifts, person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(1);
        UUID storedId = userIdOf(employeeId);
        assertThat(currentShiftIdOf(employeeId)).isEqualTo(shiftRow("US").get("id"));
        assertThat(jdbcTemplate.queryForObject("SELECT effective_from FROM employee_shift_assignments WHERE employee_id = ?", java.sql.Date.class, storedId)
                .toLocalDate()).isEqualTo(LocalDate.now(BUSINESS_ZONE).minusDays(2)); // a first assignment reaches back so the running occurrence is on it
        assertThat(auditActionsOfLatestCall()).contains("employee.shift_changed");
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT actor_id, source, \"after\"::text AS after_json FROM audit_logs WHERE request_id = ? "
                + "AND action = 'employee.shift_changed'", requestId);
        assertThat(entry).containsEntry("source", "INTEGRATION");
        assertThat(entry.get("actor_id")).isNull();
        assertThat((String) entry.get("after_json")).contains("US shift");

        // the same shift again changes nothing and writes nothing; an absent or null shiftCode leaves the assignment
        assertThat(number(answerOf(sync(body(shifts, person)).andExpect(status().isOk())), "$.data.employees.unchanged")).isEqualTo(1);
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");
        person.remove("shiftCode");
        sync(body(shifts, person)).andExpect(status().isOk());
        assertThat(currentShiftIdOf(employeeId)).isEqualTo(shiftRow("US").get("id"));
        person.put("shiftCode", null);
        sync(body(shifts, person)).andExpect(status().isOk());
        assertThat(currentShiftIdOf(employeeId)).isEqualTo(shiftRow("US").get("id"));

        // an assignment of an earlier day stays as history when the person moves to another shift
        jdbcTemplate.update("UPDATE employee_shift_assignments SET effective_from = effective_from - 10 WHERE employee_id = ?", storedId);
        person.put("shiftCode", "UK");
        String moved = answerOf(sync(body(shifts, person)).andExpect(status().isOk()));
        assertThat(number(moved, "$.data.employees.updated")).isEqualTo(1);
        assertThat(currentShiftIdOf(employeeId)).isEqualTo(shiftRow("UK").get("id"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_shift_assignments WHERE employee_id = ?", Integer.class, storedId)).isEqualTo(2);
    }

    @Test
    void aPersonNamingAnUnknownOrInactiveShiftIsSkippedWithTheOthersApplied() throws Exception {
        UUID unknownId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        UUID goodId = UUID.randomUUID();
        Map<String, Object> unknown = person(unknownId, "EMPLOYEE", null);
        unknown.put("shiftCode", "NOPE");
        Map<String, Object> inactive = person(inactiveId, "EMPLOYEE", null);
        inactive.put("shiftCode", "OLD");
        Map<String, Object> good = person(goodId, "EMPLOYEE", null);
        good.put("shiftCode", "US");
        List<Map<String, Object>> shifts = List.of(shift("US", "US shift", "19:30", "04:30"), shift("OLD", "Old shift", "10:00", "18:00"));
        sync(body(shifts)).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE shifts SET is_active = false WHERE business_id = ? AND code = 'OLD'", businessId());
        String answer = answerOf(sync(body(shifts, unknown, inactive, good)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactlyInAnyOrder("unknown_shift_code", "shift_inactive");
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(1);
        assertThat(currentShiftIdOf(goodId)).isEqualTo(shiftRow("US").get("id"));
    }

    @Test
    void theRosterSetsDatesAndWeeksOffIdempotently() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        person.put("schedule", List.of(scheduleEntry(daysFromToday(1), "UK"), scheduleEntry(daysFromToday(2), null), scheduleEntry(daysFromToday(3), "US")));
        List<Map<String, Object>> shifts = List.of(shift("US", "US shift", "19:30", "04:30"), shift("UK", "UK shift", "14:00", "23:00"));
        String answer = answerOf(sync(body(shifts, person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.scheduleEntries.set")).isEqualTo(3);
        assertThat(number(answer, "$.data.scheduleEntries.skipped")).isZero();
        UUID storedId = userIdOf(employeeId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, storedId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT shift_id FROM employee_schedule_entries WHERE employee_id = ? AND entry_date = ?::date", UUID.class,
                storedId, daysFromToday(1))).isEqualTo(shiftRow("UK").get("id"));
        assertThat(jdbcTemplate.queryForObject("SELECT shift_id FROM employee_schedule_entries WHERE employee_id = ? AND entry_date = ?::date", UUID.class,
                storedId, daysFromToday(2))).isNull();
        assertThat(auditActionsOfLatestCall()).filteredOn(action -> action.equals("schedule.entry_set")).hasSize(3);
        Map<String, Object> weekOff = jdbcTemplate.queryForMap("SELECT actor_id, source, entity_id, \"after\"::text AS after_json FROM audit_logs "
                + "WHERE request_id = ? AND action = 'schedule.entry_set' AND entity_id = ?", requestId, storedId + "/" + daysFromToday(2));
        assertThat(weekOff).containsEntry("source", "INTEGRATION");
        assertThat(weekOff.get("actor_id")).isNull();
        assertThat((String) weekOff.get("after_json")).contains("\"isOff\": true");

        // the same roster again: nothing changes, nothing is audited beyond the call itself
        String repeat = answerOf(sync(body(shifts, person)).andExpect(status().isOk()));
        assertThat(number(repeat, "$.data.scheduleEntries.set")).isZero();
        assertThat(number(repeat, "$.data.scheduleEntries.unchanged")).isEqualTo(3);
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");

        // a date moves from a shift to a week off and back; dates that are not listed are left as they are
        person.put("schedule", List.of(scheduleEntry(daysFromToday(1), null), scheduleEntry(daysFromToday(2), "UK")));
        String moved = answerOf(sync(body(shifts, person)).andExpect(status().isOk()));
        assertThat(number(moved, "$.data.scheduleEntries.set")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, storedId)).isEqualTo(3);
        // an owner's reason on an entry is kept when JurisOne sets the same shift
        jdbcTemplate.update("UPDATE employee_schedule_entries SET reason = 'swap' WHERE employee_id = ? AND entry_date = ?::date", storedId, daysFromToday(2));
        assertThat(number(answerOf(sync(body(shifts, person)).andExpect(status().isOk())), "$.data.scheduleEntries.unchanged")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM employee_schedule_entries WHERE employee_id = ? AND entry_date = ?::date", String.class,
                storedId, daysFromToday(2))).isEqualTo("swap");
    }

    @Test
    void aDateThatAlreadyHasAWorkdayOrIsTooFarAheadIsSkippedAndReported() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        sync(body(person)).andExpect(status().isOk());
        Cookie employeeCookie = SignIn.session(mockMvc, (String) person.get("email")).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/me/time-events")).cookie(employeeCookie).content("{\"type\":\"START\"}")).andExpect(status().isCreated());
        String workdayDate = jdbcTemplate.queryForObject("SELECT work_date::text FROM workdays WHERE employee_id = ?", String.class, userIdOf(employeeId));

        person.put("schedule", List.of(scheduleEntry(workdayDate, null), scheduleEntry(daysFromToday(1), null), scheduleEntry(daysFromToday(366), null),
                scheduleEntry(daysFromToday(365), null)));
        String answer = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.scheduleEntries.set")).isEqualTo(2);
        assertThat(number(answer, "$.data.scheduleEntries.skipped")).isEqualTo(2);
        assertThat(skippedReasons(answer)).isEmpty(); // the person was applied; only two dates were not
        assertThat(JsonPath.<List<String>>read(answer, "$.data.skippedScheduleEntries[*].reason")).containsExactlyInAnyOrder("schedule_date_has_workday",
                "schedule_date_too_far");
        assertThat(JsonPath.<List<String>>read(answer, "$.data.skippedScheduleEntries[*].date")).containsExactlyInAnyOrder(workdayDate, daysFromToday(366));
        assertThat(JsonPath.<List<String>>read(answer, "$.data.skippedScheduleEntries[*].employeeId")).containsOnly(employeeId.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, userIdOf(employeeId)))
                .isEqualTo(2);
    }

    @Test
    void aRosterWithBadEntriesSkipsThatPersonOnly() throws Exception {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 63; i++) tooMany.add(scheduleEntry(daysFromToday(i), null));
        UUID tooLongId = UUID.randomUUID();
        Map<String, Object> tooLong = person(tooLongId, "EMPLOYEE", null);
        tooLong.put("schedule", tooMany);
        UUID unknownShiftId = UUID.randomUUID();
        Map<String, Object> unknownShift = person(unknownShiftId, "EMPLOYEE", null);
        unknownShift.put("schedule", List.of(scheduleEntry(daysFromToday(1), "NOPE")));
        UUID badDateId = UUID.randomUUID();
        Map<String, Object> badDate = person(badDateId, "EMPLOYEE", null);
        badDate.put("schedule", List.of(scheduleEntry("2026-13-45", null)));
        UUID twiceId = UUID.randomUUID();
        Map<String, Object> twice = person(twiceId, "EMPLOYEE", null);
        twice.put("schedule", List.of(scheduleEntry(daysFromToday(1), null), scheduleEntry(daysFromToday(1), null)));
        UUID sixtyTwoId = UUID.randomUUID();
        Map<String, Object> sixtyTwo = person(sixtyTwoId, "EMPLOYEE", null);
        sixtyTwo.put("schedule", tooMany.subList(0, 62));
        String answer = answerOf(sync(body(tooLong, unknownShift, badDate, twice, sixtyTwo)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactlyInAnyOrder("schedule_too_long", "unknown_shift_code", "invalid_schedule_date",
                "duplicate_schedule_date");
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(1);
        assertThat(number(answer, "$.data.scheduleEntries.set")).isEqualTo(62);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_schedule_entries WHERE employee_id = ?", Integer.class, userIdOf(sixtyTwoId)))
                .isEqualTo(62);
    }

    @Test
    void aFirstUserTokenCanCarryTheShiftCodeButOnlyForAUserItCreates() throws Exception {
        sync(body(List.of(shift("US", "US shift", "19:30", "04:30"), shift("UK", "UK shift", "14:00", "23:00")))).andExpect(status().isOk());
        Employee newcomer = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(newcomer, claims -> claims.claim("shiftCode", "US"))))
                .andExpect(status().isOk());
        assertThat(currentShiftIdOf(newcomer.employeeId())).isEqualTo(shiftRow("US").get("id"));
        UUID newcomerId = userIdOf(newcomer.employeeId());
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT source, subject_id FROM audit_logs WHERE action = 'employee.shift_changed' AND subject_id = ?",
                newcomerId);
        assertThat(entry).containsEntry("source", "INTEGRATION");

        // the same person's later token with another code changes nothing (the roster or the owner decides)
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(newcomer, claims -> claims.claim("shiftCode", "UK"))))
                .andExpect(status().isOk());
        assertThat(currentShiftIdOf(newcomer.employeeId())).isEqualTo(shiftRow("US").get("id"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_shift_assignments WHERE employee_id = ?", Integer.class, newcomerId)).isEqualTo(1);

        // a person the roster created earlier, signing in with a code, keeps what the roster gave
        UUID syncedId = UUID.randomUUID();
        Map<String, Object> synced = person(syncedId, "EMPLOYEE", null);
        sync(body(synced)).andExpect(status().isOk());
        Employee syncedEmployee = new Employee(syncedId, (String) synced.get("email"), "Asha Rao", jurisoneBusinessId, businessName, null, "EMPLOYEE");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(syncedEmployee, claims -> claims.claim("shiftCode", "UK"))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_shift_assignments WHERE employee_id = ?", Integer.class, userIdOf(syncedId))).isZero();

        // an unknown, blank or oversized code is ignored and the sign-in works
        for (String ignoredCode : List.of("NOPE", "  ", "x".repeat(80))) {
            Employee other = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
            mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(other, claims -> claims.claim("shiftCode", ignoredCode))))
                    .andExpect(status().isOk());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM employee_shift_assignments WHERE employee_id = ?", Integer.class,
                    userIdOf(other.employeeId()))).isZero();
        }
        // a shift code that is not text is ignored too
        Employee numeric = IntegrationTokens.newEmployee(jurisoneBusinessId, businessName, "EMPLOYEE");
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(numeric, claims -> claims.claim("shiftCode", 7))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(numeric.email()));
    }

    @Test
    void theOwnersEmployeeScreenShowsTheSyncedShiftOfAPerson() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        person.put("shiftCode", "EVE");
        sync(body(List.of(shift("EVE", "Evening", "14:00", "23:00")), person)).andExpect(status().isOk());
        String ownerEmail = com.jurislpo.juristrack.TestData.uniqueEmail("shift.owner");
        testData.createUser(businessId(), "ADMIN", ownerEmail, null);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();
        String answer = mockMvc.perform(get("/api/employees/" + userIdOf(employeeId)).cookie(ownerCookie)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.data.shift.name")).isEqualTo("Evening");
    }
}

package com.jurislpo.juristrack.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jurislpo.juristrack.SignIn;
import com.jurislpo.juristrack.TestData;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The roster sync's employees: created, linked, updated, idempotent, managers in any order (and seen on the manager's Team tab before anybody
 * signed in), deactivation and reactivation, skipped people with their reason codes, limits and isolation between businesses.
 */
class RosterSyncTest extends RosterSyncTestBase {

    @Test
    void createsThePeopleAndTheBusinessAndAnUnchangedRepeatWritesOnlyTheRosterSyncedEntry() throws Exception {
        UUID managerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Map<String, Object> manager = person(managerId, "MANAGER", null);
        manager.put("employeeCode", "M-1");
        Map<String, Object> report = person(reportId, "EMPLOYEE", managerId);
        // the report comes before its manager
        String answer = answerOf(sync(body(report, manager)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true)));
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(2);
        assertThat(number(answer, "$.data.employees.updated")).isZero();
        assertThat(skippedReasons(answer)).isEmpty();

        UUID businessId = businessId();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId)).isEqualTo(businessName);
        Map<String, Object> storedManager = userOf(managerId);
        Map<String, Object> storedReport = userOf(reportId);
        assertThat(storedManager).containsEntry("business_id", businessId).containsEntry("role", "MANAGER").containsEntry("employee_code", "M-1")
                .containsEntry("first_name", "Asha").containsEntry("last_name", "Rao").containsEntry("is_active", true);
        assertThat(storedReport).containsEntry("role", "EMPLOYEE").containsEntry("manager_id", storedManager.get("id"));
        assertThat(storedManager.get("manager_id")).isNull();

        List<String> actions = auditActionsOfLatestCall();
        assertThat(actions).containsExactlyInAnyOrder("integration.business_provisioned", "integration.user_provisioned", "integration.user_provisioned",
                "integration.roster_synced");
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT category, outcome, source, actor_id, business_id, entity_type, entity_id, request_id, "
                + "\"after\"::text AS after_json FROM audit_logs WHERE request_id = ? AND action = 'integration.roster_synced'", requestId);
        assertThat(entry).containsEntry("category", "integration").containsEntry("outcome", "SUCCESS").containsEntry("source", "INTEGRATION")
                .containsEntry("business_id", businessId).containsEntry("entity_type", "business").containsEntry("entity_id", businessId.toString())
                .containsEntry("request_id", requestId);
        assertThat(entry.get("actor_id")).isNull();
        assertThat((String) entry.get("after_json")).contains("\"receivedEmployees\": 2").contains("\"created\": 2");
        // every change entry is of the integration, and no token or address list is in any audit entry
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT source FROM audit_logs WHERE request_id = ?", String.class, requestId)).containsExactly("INTEGRATION");

        // the same body again changes nothing and writes only the roster_synced entry
        String repeat = answerOf(sync(body(report, manager)).andExpect(status().isOk()));
        assertThat(number(repeat, "$.data.employees.created")).isZero();
        assertThat(number(repeat, "$.data.employees.updated")).isZero();
        assertThat(number(repeat, "$.data.employees.unchanged")).isEqualTo(2);
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, businessId)).isEqualTo(2);
    }

    @Test
    void updatesNameCodeEmailRoleAndManagerAndAuditsTheChange() throws Exception {
        UUID managerId = UUID.randomUUID();
        UUID otherManagerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Map<String, Object> report = person(reportId, "EMPLOYEE", managerId);
        sync(body(person(managerId, "MANAGER", null), person(otherManagerId, "MANAGER", null), report)).andExpect(status().isOk());

        String newEmail = TestData.uniqueEmail("renamed");
        report.put("firstName", "Asha Devi");
        report.put("lastName", "Menon");
        report.put("email", newEmail);
        report.put("employeeCode", "E-77");
        report.put("role", "MANAGER");
        report.put("managerEmployeeId", otherManagerId.toString());
        String answer = answerOf(sync(body(report)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.updated")).isEqualTo(1);
        Map<String, Object> stored = userOf(reportId);
        assertThat(stored).containsEntry("first_name", "Asha Devi").containsEntry("last_name", "Menon").containsEntry("email", newEmail)
                .containsEntry("employee_code", "E-77").containsEntry("role", "MANAGER").containsEntry("manager_id", userIdOf(otherManagerId));
        Map<String, Object> entry = jdbcTemplate.queryForMap("SELECT subject_id, \"before\"::text AS before_json, \"after\"::text AS after_json "
                + "FROM audit_logs WHERE request_id = ? AND action = 'integration.user_updated'", requestId);
        assertThat(entry).containsEntry("subject_id", stored.get("id"));
        assertThat((String) entry.get("before_json")).contains("\"role\": \"EMPLOYEE\"");
        assertThat((String) entry.get("after_json")).contains("\"role\": \"MANAGER\"").contains(newEmail);

        // no manager at all removes the link; an absent role and code keep what is stored
        report.put("managerEmployeeId", null);
        report.put("role", null);
        report.put("employeeCode", null);
        sync(body(report)).andExpect(status().isOk());
        assertThat(userOf(reportId)).containsEntry("role", "MANAGER").containsEntry("employee_code", "E-77");
        assertThat(userOf(reportId).get("manager_id")).isNull();
    }

    @Test
    void aSyncedPersonWhoNeverSignedInIsOnTheManagersTeamTabInAnyOrder() throws Exception {
        UUID topManagerId = UUID.randomUUID();
        UUID middleManagerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID secondReportId = UUID.randomUUID();
        Map<String, Object> topManager = person(topManagerId, "MANAGER", null);
        Map<String, Object> middleManager = person(middleManagerId, "MANAGER", topManagerId);
        Map<String, Object> report = person(reportId, "EMPLOYEE", middleManagerId);
        Map<String, Object> secondReport = person(secondReportId, "EMPLOYEE", topManagerId);
        // the order of the list does not matter: the lowest level first, the top last
        sync(body(report, secondReport, middleManager, topManager)).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id IN (?, ?)", Integer.class, userIdOf(reportId),
                userIdOf(secondReportId))).isZero();

        Cookie topCookie = SignIn.session(mockMvc, (String) topManager.get("email")).accessCookie();
        String board = mockMvc.perform(get("/api/v1/team/members").cookie(topCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(board, "$.data.rows[*].employee.id");
        assertThat(ids).containsExactlyInAnyOrder(userIdOf(middleManagerId).toString(), userIdOf(secondReportId).toString());
        String indirectBoard = mockMvc.perform(get("/api/v1/team/members?scope=indirect").cookie(topCookie)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((List<String>) JsonPath.read(indirectBoard, "$.data.rows[*].employee.id")).containsExactlyInAnyOrder(userIdOf(middleManagerId).toString(),
                userIdOf(secondReportId).toString(), userIdOf(reportId).toString());
        assertThat((List<String>) JsonPath.read(indirectBoard, "$.data.rows[*].status")).containsOnly("NOT_STARTED");
    }

    @Test
    void aManagerAlreadyStoredIsFoundWhenTheyAreNotInTheCall() throws Exception {
        UUID managerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        sync(body(person(managerId, "MANAGER", null))).andExpect(status().isOk());
        String answer = answerOf(sync(body(person(reportId, "EMPLOYEE", managerId))).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(1);
        assertThat(userOf(reportId)).containsEntry("manager_id", userIdOf(managerId));
    }

    @Test
    void aPersonAddedByEmailBeforeIsLinkedNotDuplicated() throws Exception {
        sync(body()).andExpect(status().isOk());
        UUID businessId = businessId();
        String email = TestData.uniqueEmail("added.by.owner");
        UUID existingId = testData.createUser(businessId, "EMPLOYEE", email, null, "Old", "Name", "C-9");
        UUID jurisoneEmployeeId = UUID.randomUUID();
        Map<String, Object> person = person(jurisoneEmployeeId, email, "MANAGER", null);
        String answer = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.updated")).isEqualTo(1);
        assertThat(number(answer, "$.data.employees.created")).isZero();
        assertThat(userOf(jurisoneEmployeeId)).containsEntry("id", existingId).containsEntry("first_name", "Asha").containsEntry("role", "MANAGER")
                .containsEntry("employee_code", "C-9");
        assertThat(auditActionsOfLatestCall()).contains("integration.user_linked", "integration.user_updated", "integration.roster_synced");
    }

    @Test
    void aSyncedPersonCanLaterSignInThroughTheGatewayTokenAndIsTheSamePerson() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        sync(body(person)).andExpect(status().isOk());
        UUID storedId = userIdOf(employeeId);
        IntegrationTokens.Employee employee = new IntegrationTokens.Employee(employeeId, (String) person.get("email"), "Asha Rao", jurisoneBusinessId,
                businessName, null, "EMPLOYEE");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + IntegrationTokens.tokenFor(employee))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(storedId.toString()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, businessId())).isEqualTo(1);
    }

    @Test
    void anExistingOwnerKeepsTheOwnerRoleAndIsNeverDeactivated() throws Exception {
        sync(body()).andExpect(status().isOk());
        UUID businessId = businessId();
        String ownerEmail = TestData.uniqueEmail("the.owner");
        UUID ownerStoredId = testData.createUser(businessId, "ADMIN", ownerEmail, null, "Olivia", "Owner", null);
        UUID ownerJurisoneId = UUID.randomUUID();
        Map<String, Object> owner = person(ownerJurisoneId, ownerEmail, "EMPLOYEE", null);
        sync(body(owner)).andExpect(status().isOk());
        assertThat(userOf(ownerJurisoneId)).containsEntry("id", ownerStoredId).containsEntry("role", "ADMIN");

        owner.put("isActive", false);
        String answer = answerOf(sync(body(owner)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactly("owner_cannot_be_deactivated");
        assertThat(userOf(ownerJurisoneId)).containsEntry("is_active", true);

        // a role of ADMIN is never taken from the call
        Map<String, Object> wouldBeOwner = person(UUID.randomUUID(), "ADMIN", null);
        assertThat(skippedReasons(answerOf(sync(body(wouldBeOwner)).andExpect(status().isOk())))).containsExactly("invalid_role");
    }

    @Test
    void deactivatingClosesTheOpenWorkdayEndsSessionsAndReactivatingRestores() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        sync(body(person)).andExpect(status().isOk());
        Cookie employeeCookie = SignIn.session(mockMvc, (String) person.get("email")).accessCookie();
        mockMvc.perform(SignIn.fromWebApp(post("/api/v1/me/time-events")).cookie(employeeCookie)
                .content("{\"type\":\"START\"}")).andExpect(status().isCreated());
        UUID storedId = userIdOf(employeeId);
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE employee_id = ?", String.class, storedId)).isNotEqualTo("COMPLETED");

        person.put("isActive", false);
        String answer = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.deactivated")).isEqualTo(1);
        assertThat(userOf(employeeId)).containsEntry("is_active", false).containsEntry("is_deactivated_by_roster_sync", true);
        assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM workdays WHERE employee_id = ?", String.class, storedId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, storedId)).isZero();
        mockMvc.perform(get("/api/v1/me").cookie(employeeCookie)).andExpect(status().isUnauthorized());
        assertThat(auditActionsOfLatestCall()).containsExactlyInAnyOrder("employee.deactivated", "workday.closed", "integration.roster_synced");
        Map<String, Object> deactivation = jdbcTemplate.queryForMap("SELECT actor_id, subject_id, source, reason FROM audit_logs WHERE request_id = ? "
                + "AND action = 'employee.deactivated'", requestId);
        assertThat(deactivation).containsEntry("subject_id", storedId).containsEntry("source", "INTEGRATION").containsEntry("reason", "jurisone_roster_sync");
        assertThat(deactivation.get("actor_id")).isNull();

        // sending the same deactivation again changes nothing
        String repeat = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(repeat, "$.data.employees.deactivated")).isZero();
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");

        person.put("isActive", true);
        String reactivated = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(reactivated, "$.data.employees.reactivated")).isEqualTo(1);
        assertThat(userOf(employeeId)).containsEntry("is_active", true).containsEntry("is_deactivated_by_roster_sync", false);
        assertThat(auditActionsOfLatestCall()).containsExactlyInAnyOrder("employee.reactivated", "integration.roster_synced");
    }

    @Test
    void aPersonTheOwnerDeactivatedOrWhoseLoginTheOwnerSwitchedOffIsNotReactivated() throws Exception {
        UUID byOwnerId = UUID.randomUUID();
        UUID loginOffId = UUID.randomUUID();
        UUID byOwnerAfterSyncId = UUID.randomUUID();
        Map<String, Object> byOwner = person(byOwnerId, "EMPLOYEE", null);
        Map<String, Object> loginOff = person(loginOffId, "EMPLOYEE", null);
        Map<String, Object> byOwnerAfterSync = person(byOwnerAfterSyncId, "EMPLOYEE", null);
        sync(body(byOwner, loginOff, byOwnerAfterSync)).andExpect(status().isOk());
        UUID businessId = businessId();
        String ownerEmail = TestData.uniqueEmail("status.owner");
        testData.createUser(businessId, "ADMIN", ownerEmail, null);
        Cookie ownerCookie = SignIn.session(mockMvc, ownerEmail).accessCookie();

        // deactivated by the owner in JurisTrack
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + userIdOf(byOwnerId) + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        // login switched off by the owner, and (for the case) deactivated by an earlier sync
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + userIdOf(loginOffId) + "/login-access")).cookie(ownerCookie).content("{\"isEnabled\":false}"))
                .andExpect(status().isOk());
        loginOff.put("isActive", false);
        sync(body(loginOff)).andExpect(status().isOk());
        assertThat(userOf(loginOffId)).containsEntry("is_active", false).containsEntry("is_deactivated_by_roster_sync", true);
        // deactivated by the sync, then reactivated and deactivated again by the owner: the owner's decision stands
        byOwnerAfterSync.put("isActive", false);
        sync(body(byOwnerAfterSync)).andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + userIdOf(byOwnerAfterSyncId) + "/status")).cookie(ownerCookie).content("{\"isActive\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(SignIn.fromWebApp(put("/api/v1/employees/" + userIdOf(byOwnerAfterSyncId) + "/status")).cookie(ownerCookie).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        assertThat(userOf(byOwnerAfterSyncId)).containsEntry("is_deactivated_by_roster_sync", false);

        byOwner.put("isActive", true);
        loginOff.put("isActive", true);
        byOwnerAfterSync.put("isActive", true);
        String answer = answerOf(sync(body(byOwner, loginOff, byOwnerAfterSync)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.reactivated")).isZero();
        assertThat(userOf(byOwnerId)).containsEntry("is_active", false);
        assertThat(userOf(loginOffId)).containsEntry("is_active", false);
        assertThat(userOf(byOwnerAfterSyncId)).containsEntry("is_active", false);
        assertThat(auditActionsOfLatestCall()).containsExactly("integration.roster_synced");
    }

    @Test
    void aNewPersonWhoIsInactiveIsNotCreatedAndAOneWordNameIsFine() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        person.put("isActive", false);
        String answer = answerOf(sync(body(person)).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.created")).isZero();
        assertThat(number(answer, "$.data.employees.unchanged")).isEqualTo(1);
        assertThat(skippedReasons(answer)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id = ?", Integer.class, employeeId)).isZero();
        person.put("isActive", true);
        person.put("lastName", "");
        assertThat(number(answerOf(sync(body(person)).andExpect(status().isOk())), "$.data.employees.created")).isEqualTo(1);
        assertThat(userOf(employeeId)).containsEntry("last_name", "").containsEntry("is_active", true);
    }

    @Test
    void eachProblemPersonIsSkippedWithItsReasonAndTheRestIsApplied() throws Exception {
        sync(body()).andExpect(status().isOk());
        UUID businessId = businessId();
        UUID standaloneBusinessId = testData.createBusiness("Standalone " + UUID.randomUUID());
        String standaloneEmail = TestData.uniqueEmail("standalone.person");
        testData.createUser(standaloneBusinessId, "EMPLOYEE", standaloneEmail, null);
        String superAdminEmail = TestData.uniqueEmail("platform.admin");
        testData.createUser(null, "SUPER_ADMIN", superAdminEmail, null);
        String codeHolderEmail = TestData.uniqueEmail("code.holder");
        testData.createUser(businessId, "EMPLOYEE", codeHolderEmail, null, "Cody", "Holder", "TAKEN-1");
        String linkedEmail = TestData.uniqueEmail("already.linked");
        UUID linkedUserId = testData.createUser(businessId, "EMPLOYEE", linkedEmail, null);
        jdbcTemplate.update("UPDATE users SET jurisone_employee_id = ? WHERE id = ?", UUID.randomUUID(), linkedUserId);

        UUID goodId = UUID.randomUUID();
        Map<String, Object> good = person(goodId, "EMPLOYEE", null);
        Map<String, Object> badEmail = person(UUID.randomUUID(), "not-an-email", "EMPLOYEE", null);
        UUID anotherBusinessId = UUID.randomUUID();
        Map<String, Object> anotherBusiness = person(anotherBusinessId, standaloneEmail, "EMPLOYEE", null);
        UUID superAdminId = UUID.randomUUID();
        Map<String, Object> superAdmin = person(superAdminId, superAdminEmail, "MANAGER", null);
        UUID linkedToAnotherId = UUID.randomUUID();
        Map<String, Object> linkedToAnother = person(linkedToAnotherId, linkedEmail, "EMPLOYEE", null);
        UUID unknownShiftId = UUID.randomUUID();
        Map<String, Object> unknownShift = person(unknownShiftId, "EMPLOYEE", null);
        unknownShift.put("shiftCode", "NO-SUCH-SHIFT");
        UUID unknownManagerEmployeeId = UUID.randomUUID();
        Map<String, Object> unknownManager = person(unknownManagerEmployeeId, "EMPLOYEE", UUID.randomUUID());
        UUID duplicateId = UUID.randomUUID();
        Map<String, Object> duplicate = person(duplicateId, "EMPLOYEE", null);
        Map<String, Object> duplicateAgain = person(duplicateId, "EMPLOYEE", null);
        UUID invalidRoleId = UUID.randomUUID();
        Map<String, Object> invalidRole = person(invalidRoleId, "CEO", null);
        UUID noNameId = UUID.randomUUID();
        Map<String, Object> noName = person(noNameId, "EMPLOYEE", null);
        noName.put("firstName", " ");
        UUID codeInUseId = UUID.randomUUID();
        Map<String, Object> codeInUse = person(codeInUseId, "EMPLOYEE", null);
        codeInUse.put("employeeCode", "TAKEN-1");
        UUID badCodeId = UUID.randomUUID();
        Map<String, Object> badCode = person(badCodeId, "EMPLOYEE", null);
        badCode.put("employeeCode", "no spaces allowed");
        UUID sameEmailTwiceId = UUID.randomUUID();
        Map<String, Object> sameEmailTwice = person(sameEmailTwiceId, (String) good.get("email"), "EMPLOYEE", null);
        UUID badManagerIdEmployee = UUID.randomUUID();
        Map<String, Object> badManagerId = person(badManagerIdEmployee, "EMPLOYEE", null);
        badManagerId.put("managerEmployeeId", "not-a-uuid");

        String answer = answerOf(sync(body(good, badEmail, anotherBusiness, superAdmin, linkedToAnother, unknownShift, unknownManager, duplicate, duplicateAgain,
                invalidRole, noName, codeInUse, badCode, sameEmailTwice, badManagerId)).andExpect(status().isOk()));
        Map<String, String> reasonByEmployee = new java.util.HashMap<>();
        List<Map<String, String>> skipped = JsonPath.read(answer, "$.data.skipped");
        for (Map<String, String> skippedEmployee : skipped) reasonByEmployee.put(skippedEmployee.get("employeeId"), skippedEmployee.get("reason"));
        assertThat(reasonByEmployee).containsEntry(badEmail.get("employeeId").toString(), "invalid_email")
                .containsEntry(anotherBusinessId.toString(), "email_of_another_business")
                .containsEntry(superAdminId.toString(), "email_of_super_admin")
                .containsEntry(linkedToAnotherId.toString(), "email_linked_to_another_employee")
                .containsEntry(unknownShiftId.toString(), "unknown_shift_code")
                .containsEntry(unknownManagerEmployeeId.toString(), "unknown_manager")
                .containsEntry(duplicateId.toString(), "duplicate_employee_in_request")
                .containsEntry(invalidRoleId.toString(), "invalid_role")
                .containsEntry(noNameId.toString(), "invalid_name")
                .containsEntry(codeInUseId.toString(), "employee_code_in_use")
                .containsEntry(badCodeId.toString(), "invalid_employee_code")
                .containsEntry(sameEmailTwiceId.toString(), "duplicate_email_in_request")
                .containsEntry(badManagerIdEmployee.toString(), "invalid_manager_id");
        assertThat(reasonByEmployee).doesNotContainKey(goodId.toString());
        // the rest was applied: the good person and the first of the duplicated ids
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(2);
        assertThat(userOf(goodId)).containsEntry("business_id", businessId);
        assertThat(userOf(duplicateId)).containsEntry("business_id", businessId);
        // the other business's person and the platform's super admin were not touched
        assertThat(jdbcTemplate.queryForObject("SELECT business_id FROM users WHERE email = ?", UUID.class, standaloneEmail)).isEqualTo(standaloneBusinessId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE email = ? AND jurisone_employee_id IS NULL", Integer.class, superAdminEmail))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, businessId)).isEqualTo(2 + 2);
        assertThat((String) jdbcTemplate.queryForObject("SELECT \"after\"::text FROM audit_logs WHERE request_id = ? AND action = 'integration.roster_synced'",
                String.class, requestId)).contains("\"unknown_manager\": 1").contains("\"skippedEmployees\": 13");
    }

    @Test
    void aReportingLoopIsRefusedForThePersonWhoWouldCloseIt() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Map<String, Object> first = person(firstId, "MANAGER", null);
        Map<String, Object> second = person(secondId, "MANAGER", firstId);
        sync(body(first, second)).andExpect(status().isOk());
        first.put("managerEmployeeId", secondId.toString());
        String answer = answerOf(sync(body(first)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactly("manager_cycle");
        assertThat(userOf(firstId).get("manager_id")).isNull();
        assertThat(userOf(secondId)).containsEntry("manager_id", userIdOf(firstId));

        // somebody being their own manager means no manager
        first.put("managerEmployeeId", firstId.toString());
        assertThat(skippedReasons(answerOf(sync(body(first)).andExpect(status().isOk())))).isEmpty();
        assertThat(userOf(firstId).get("manager_id")).isNull();
    }

    @Test
    void aNewPersonWhoseManagerIsSkippedIsSkippedToo() throws Exception {
        UUID managerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Map<String, Object> manager = person(managerId, "not-an-email", "MANAGER", null);
        Map<String, Object> report = person(reportId, "EMPLOYEE", managerId);
        String answer = answerOf(sync(body(manager, report)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactlyInAnyOrder("invalid_email", "unknown_manager");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id IN (?, ?)", Integer.class, managerId, reportId)).isZero();
    }

    @Test
    void aMalformedBodyIsRefusedWholeWithFieldDetails() throws Exception {
        Map<String, Object> noId = person(UUID.randomUUID(), "EMPLOYEE", null);
        noId.put("employeeId", null);
        sync(body(noId)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("employees[0].employeeId"));
        sync(body(List.of(shift("US", "US shift", "7pm", "04:30")), person(UUID.randomUUID(), "EMPLOYEE", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("shifts[0].startTime"));
        sync(body(List.of(shift("US", "US shift", "19:30", "04:30"), shift("US", "Another", "10:00", "18:00")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("shifts[1].code"));
        // nothing of it was applied: not even the business
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isZero();
        syncAs(IntegrationTokens.serviceTokenFor(jurisoneBusinessId, businessName), "{\"employees\":\"nope\"}").andExpect(status().isBadRequest());
    }

    @Test
    void moreThanFiveThousandEmployeesAreRefused() throws Exception {
        List<Map<String, Object>> employees = new ArrayList<>();
        for (int i = 0; i < 5001; i++) employees.add(person(UUID.randomUUID(), "EMPLOYEE", null));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("employees", employees);
        sync(body).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM businesses WHERE jurisone_business_id = ?", Integer.class, jurisoneBusinessId)).isZero();
    }

    @Test
    void aBatchOfThreeHundredWithAChainOfManagersIsApplied() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) ids.add(UUID.randomUUID());
        List<Map<String, Object>> employees = new ArrayList<>();
        // the last one is listed first: every person's manager comes after them
        for (int i = 299; i >= 0; i--) employees.add(person(ids.get(i), i == 0 ? "MANAGER" : "MANAGER", i == 0 ? null : ids.get(i - 1)));
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("employees", employees);
        String answer = answerOf(sync(body).andExpect(status().isOk()));
        assertThat(number(answer, "$.data.employees.created")).isEqualTo(300);
        assertThat(skippedReasons(answer)).isEmpty();
        assertThat(userOf(ids.get(299))).containsEntry("manager_id", userIdOf(ids.get(298)));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ? AND manager_id IS NULL", Integer.class, businessId())).isEqualTo(1);
    }

    @Test
    void aSuspendedBusinessIsRefusedWith403AndNothingChanges() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        sync(body(person)).andExpect(status().isOk());
        testData.suspendBusiness(businessId());
        businessName = businessName + " renamed";
        person.put("firstName", "Changed");
        UUID secondId = UUID.randomUUID();
        sync(body(person, person(secondId, "EMPLOYEE", null))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"));
        assertThat(userOf(employeeId)).containsEntry("first_name", "Asha");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE jurisone_employee_id = ?", Integer.class, secondId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId())).doesNotContain("renamed");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE business_id = ? AND action = 'integration.user_refused' AND reason = 'business_suspended'",
                Integer.class, businessId())).isEqualTo(1);
    }

    @Test
    void theBusinessIsCreatedRenamedAndNeverAdoptedFromAStandaloneOne() throws Exception {
        UUID standaloneBusinessId = testData.createBusiness(businessName);
        String ownerEmail = TestData.uniqueEmail("standalone.owner");
        testData.createUser(standaloneBusinessId, "ADMIN", ownerEmail, null);
        UUID employeeId = UUID.randomUUID();
        // the same name and even the owner's email: a new business is made, the standalone one is untouched
        String answer = answerOf(sync(body(person(employeeId, ownerEmail, "MANAGER", null), person(UUID.randomUUID(), "EMPLOYEE", null))).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactly("email_of_another_business");
        UUID businessId = businessId();
        assertThat(businessId).isNotEqualTo(standaloneBusinessId);
        assertThat(jdbcTemplate.queryForObject("SELECT jurisone_business_id FROM businesses WHERE id = ?", UUID.class, standaloneBusinessId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, standaloneBusinessId)).isEqualTo(1);
        // a default shift came with the new business, and a renamed business follows the token
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM shifts WHERE business_id = ? AND is_default", Integer.class, businessId)).isEqualTo(1);
        businessName = businessName + " Ltd";
        sync(body()).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId)).isEqualTo(businessName);
        assertThat(auditActionsOfLatestCall()).containsExactlyInAnyOrder("integration.business_updated", "integration.roster_synced");
    }

    @Test
    void aTokenForOneBusinessNeverTouchesAnotherBusiness() throws Exception {
        UUID employeeId = UUID.randomUUID();
        Map<String, Object> person = person(employeeId, "EMPLOYEE", null);
        sync(body(person)).andExpect(status().isOk());
        UUID firstBusinessId = businessId();
        UUID firstBusinessJurisoneId = jurisoneBusinessId;
        String firstBusinessName = businessName;

        // a second JurisOne business pushes the same employee id and the same email, and a manager of the first business
        jurisoneBusinessId = UUID.randomUUID();
        businessName = "Second " + firstBusinessName;
        Map<String, Object> sameId = person(employeeId, TestData.uniqueEmail("elsewhere"), "MANAGER", null);
        Map<String, Object> sameEmail = person(UUID.randomUUID(), (String) person.get("email"), "EMPLOYEE", null);
        String answer = answerOf(sync(body(sameId, sameEmail)).andExpect(status().isOk()));
        assertThat(skippedReasons(answer)).containsExactlyInAnyOrder("employee_of_another_business", "email_of_another_business");
        assertThat(userOf(employeeId)).containsEntry("business_id", firstBusinessId).containsEntry("role", "EMPLOYEE").containsEntry("email", person.get("email"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE business_id = ?", Integer.class, businessId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM businesses WHERE jurisone_business_id = ?", String.class, firstBusinessJurisoneId))
                .isEqualTo(firstBusinessName);

        // a manager of the first business cannot be named as a manager in the second
        UUID reportId = UUID.randomUUID();
        String second = answerOf(sync(body(person(reportId, "EMPLOYEE", employeeId))).andExpect(status().isOk()));
        assertThat(skippedReasons(second)).containsExactly("unknown_manager");
    }
}

package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.EmployeeSchedule;
import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.engine.ShiftSummary;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Shift;
import com.jurislpo.juristrack.entity.ShiftAssignment;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.repository.ShiftAssignmentRepository;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.EmployeeFilter;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.EmployeeRow;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.UserProfileDetails;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's (ADMIN's) employees: list, add, edit, deactivate and reactivate, always inside the owner's own business (another
 * business's user answers 404). Every write is audited in the same transaction with the record before and after.
 * <ul>
 *   <li>Emails are unique across every business (409 {@code EMAIL_IN_USE}); employee codes are optional and unique inside the
 *       business (409 {@code EMPLOYEE_CODE_IN_USE}).</li>
 *   <li>The owner adds EMPLOYEEs and MANAGERs only; ADMIN and SUPER_ADMIN come from the platform (422 {@code ROLE_NOT_ASSIGNABLE}),
 *       and an ADMIN's role is not changed here.</li>
 *   <li>A manager must be an active MANAGER or ADMIN of the same business, not the employee, and not someone who reports
 *       (directly or further down) to the employee (422 {@code MANAGER_*}).</li>
 *   <li>A MANAGER or ADMIN with active reports cannot become an EMPLOYEE (422 {@code MANAGER_HAS_REPORTS}): move the reports first.</li>
 *   <li>Deactivating ends every session of the person at once (they also get no new sign-in codes) and closes their open workday
 *       at the current time; the owner cannot deactivate themself, and the last active owner of the business cannot be deactivated.</li>
 *   <li>Login access is separate from deactivation: switching it off keeps the person in the business, the list, the board and the
 *       reports, but ends every session at once, closes their open workday (reason {@code login_disabled}) and refuses every sign-in
 *       (a code request and a code check answer as for an unknown account; a JurisOne token is refused with 403) until the owner
 *       switches it on again, which restores sign-in only. The owner cannot switch off their own login (422).</li>
 *   <li>A change a business rule refuses (422) is audited as {@code employee.change_refused} (outcome DENIED, the code as reason) in a
 *       transaction of its own, so the entry stays although the change is rolled back.</li>
 *   <li>Reading the list or another person's record is audited ({@code access.employee_list_viewed} with the filters,
 *       {@code access.employee_viewed}); the owner reading their own record is not.</li>
 * </ul>
 */
@Service
public class EmployeeService {

    /** {@code POST /api/v1/employees} body; {@code role} defaults to EMPLOYEE, {@code employeeCode} and {@code managerId} are optional. */
    public record EmployeeInput(String employeeCode, String firstName, String lastName, String email, Role role, UUID managerId) {}

    /**
     * {@code PATCH /api/v1/employees/{id}} body: a field that is left out stays as it is ({@code null} here); a field sent as
     * {@code null} is cleared ({@code Optional.empty()}), which only {@code employeeCode} and {@code managerId} allow.
     */
    public record EmployeeChanges(Optional<String> employeeCode, Optional<String> firstName, Optional<String> lastName, Optional<String> email,
            Optional<Role> role, Optional<UUID> managerId) {

        /** Reads the fields of a PATCH body (400 for a value of the wrong type, such as an unknown role or a malformed id). */
        public static EmployeeChanges from(PatchBody body) {
            EmployeeChanges changes = new EmployeeChanges(body.field("employeeCode", String.class), body.field("firstName", String.class),
                    body.field("lastName", String.class), body.field("email", String.class), body.field("role", Role.class),
                    body.field("managerId", UUID.class));
            body.throwIfInvalid();
            return changes;
        }
    }

    /** {@code PUT /api/v1/employees/{id}/status} body; {@code reason} is optional (at most 500 characters) and goes to the audit log. */
    public record EmployeeStatusChange(@JsonProperty("isActive") Boolean isActive, String reason) {}

    /** {@code PUT /api/v1/employees/{id}/shift} body: the shift and the first shift date it applies to (default today in the business zone). */
    public record ShiftAssignmentInput(UUID shiftId, String effectiveFrom) {}
    /** {@code PUT /api/v1/employees/{id}/login-access} body; {@code reason} is optional (at most 500 characters) and goes to the audit log. */
    public record LoginAccessChange(@JsonProperty("isEnabled") Boolean isEnabled, String reason) {}

    /** The sort keys of {@code GET /api/v1/employees}, in the order the API documents them. */
    public static final List<String> SORT_KEYS = List.of("name", "employeeCode", "email", "role", "status", "lastLoginAt");
    private static final List<Role> LISTED_ROLES = List.of(Role.ADMIN, Role.MANAGER, Role.EMPLOYEE);
    private static final String USER_ENTITY = "user";
    private static final String NOT_FOUND = "Employee not found.";
    /** The reason of the {@code workday.closed} entry written when an employee's login is switched off. */
    private static final String LOGIN_DISABLED_REASON = "login_disabled";
    /** The query parameters of {@code GET /api/v1/employees} that the access entry keeps. */
    private static final List<String> LIST_PARAMETERS = List.of("search", "status", "role", "managerId", "sort", "sortDirection", "page", "pageSize");

    private final UserRepository userRepository;
    private final EmployeeQueryRepository employeeQueryRepository;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final TimeTrackingService timeTrackingService;
    private final EmployeeLockRepository employeeLockRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final BusinessRepository businessRepository;
    private final ShiftService shiftService;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ShiftResolver shiftResolver;

    public EmployeeService(UserRepository userRepository, EmployeeQueryRepository employeeQueryRepository, SessionService sessionService,
            AuditService auditService, TimeTrackingService timeTrackingService, EmployeeLockRepository employeeLockRepository,
            TransactionTemplate transactionTemplate, Clock clock, BusinessRepository businessRepository, ShiftService shiftService,
            ShiftAssignmentRepository shiftAssignmentRepository, ShiftResolver shiftResolver) {
        this.clock = clock;
        this.businessRepository = businessRepository;
        this.shiftService = shiftService;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.shiftResolver = shiftResolver;
        this.userRepository = userRepository;
        this.employeeQueryRepository = employeeQueryRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.timeTrackingService = timeTrackingService;
        this.employeeLockRepository = employeeLockRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * One page of the owner's business: {@code search} (name, email, employee code), {@code status} ({@code active} (default),
     * {@code inactive}, {@code all}), {@code role} (one or more of EMPLOYEE, MANAGER, ADMIN, comma-separated), {@code managerId}
     * (direct reports of that user), {@code sort} ({@link #SORT_KEYS}, default {@code name}), {@code sortDirection}, {@code page},
     * {@code pageSize}. 400 for an unknown value.
     */
    @Transactional
    public ListPage<EmployeeRow> list(CurrentUser owner, Map<String, String> queryParameters) {
        ListPage<EmployeeRow> page = findPage(owner, queryParameters);
        Map<String, Object> details = new LinkedHashMap<>();
        for (String parameter : LIST_PARAMETERS) if (queryParameters.containsKey(parameter)) details.put(parameter, queryParameters.get(parameter));
        details.put("rowCount", page.rows().size());
        details.put("total", page.total());
        auditService.recordAccess(AuditAction.EMPLOYEE_LIST_VIEWED, owner, null, USER_ENTITY, null, details);
        return page;
    }

    private ListPage<EmployeeRow> findPage(CurrentUser owner, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(queryParameters, SORT_KEYS, "name", validator);
        Boolean isActive = switch (queryParameters.getOrDefault("status", "active").trim().toLowerCase(Locale.ROOT)) {
            case "", "active" -> Boolean.TRUE;
            case "inactive" -> Boolean.FALSE;
            case "all" -> null;
            default -> {
                validator.reject("status", "The status is active, inactive or all.");
                yield null;
            }
        };
        List<Role> roles = new ArrayList<>();
        for (String roleName : queryParameters.getOrDefault("role", "").split(",")) {
            String trimmedRoleName = roleName.trim().toUpperCase(Locale.ROOT);
            if (trimmedRoleName.isEmpty()) continue;
            Optional<Role> role = LISTED_ROLES.stream().filter(listedRole -> listedRole.name().equals(trimmedRoleName)).findFirst();
            if (role.isPresent()) roles.add(role.get());
            else validator.reject("role", "The role is EMPLOYEE, MANAGER or ADMIN.");
        }
        UUID managerId = null;
        String rawManagerId = queryParameters.getOrDefault("managerId", "").trim();
        if (!rawManagerId.isEmpty()) {
            try {
                managerId = UUID.fromString(rawManagerId);
            } catch (IllegalArgumentException e) {
                validator.reject("managerId", "The manager id is not valid.");
            }
        }
        validator.throwIfInvalid();
        EmployeeFilter filter = new EmployeeFilter(isActive, roles, managerId);
        return new ListPage<>(withShifts(owner.businessId(), employeeQueryRepository.findPage(owner.businessId(), filter, listQuery)),
                employeeQueryRepository.count(owner.businessId(), filter, listQuery), listQuery.page(), listQuery.pageSize());
    }

    /** One user of the owner's business; 404 when unknown or in another business. Reading somebody else's record is audited. */
    @Transactional
    public EmployeeRow find(CurrentUser owner, UUID employeeId) {
        EmployeeRow employee = findRow(owner, employeeId);
        auditService.recordAccess(AuditAction.EMPLOYEE_VIEWED, owner, employeeId, USER_ENTITY, employeeId.toString(), null);
        return employee;
    }

    private EmployeeRow findRow(CurrentUser owner, UUID employeeId) {
        return withShifts(owner.businessId(), List.of(employeeQueryRepository.findRow(owner.businessId(), employeeId)
                .orElseThrow(() -> new OutOfScopeException(USER_ENTITY, NOT_FOUND)))).get(0);
    }

    /** The rows with each person's current shift (the assignment that applies today in the business time zone). */
    private List<EmployeeRow> withShifts(UUID businessId, List<EmployeeRow> rows) {
        if (rows.isEmpty()) return rows;
        LocalDate today = todayOf(businessId);
        Map<UUID, EmployeeSchedule> schedules = shiftResolver.schedulesOf(businessId, rows.stream().map(EmployeeRow::id).toList(), today, today);
        return rows.stream().map(row -> row.withShift(ShiftSummary.of(schedules.get(row.id()).assignedOn(today)))).toList();
    }

    /**
     * The date a shift given by JurisOne (roster sync, or the first token) takes effect: today, except for a person who has no shift
     * assignment yet, whose first one reaches two days back. Without that, a person added late in the evening would still be on the
     * default shift for the occurrence that is running right now (the one that started yesterday), and work done before the new
     * shift's own window would be attributed to the default shift. Nothing is rewritten: existing workdays keep their snapshot.
     */
    LocalDate shiftEffectiveDateFromJurisOne(UUID employeeId, LocalDate today) {
        return shiftAssignmentRepository.findByEmployeeIdIn(List.of(employeeId)).isEmpty() ? today.minusDays(2) : today;
    }

    LocalDate todayOf(UUID businessId) {
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
        return BusinessCalendar.toDateKey(clock.instant(), ZoneId.of(business.getTimeZone()));
    }

    /**
     * Gives the employee a shift from {@code effectiveFrom} (default today; a past date is refused: 422
     * {@code SHIFT_EFFECTIVE_DATE_IN_PAST}). The assignment history is kept and no workday is rewritten. The same shift again changes
     * nothing and writes no audit entry. Answers the employee with the shift now assigned.
     */
    public EmployeeRow assignShift(CurrentUser owner, UUID employeeId, ShiftAssignmentInput input) {
        return auditRefusals(owner, employeeId, "shift", () -> assignShiftInTransaction(owner, employeeId, input));
    }

    private EmployeeRow assignShiftInTransaction(CurrentUser owner, UUID employeeId, ShiftAssignmentInput input) {
        lockEmployeeRow(employeeId);
        loadEmployee(owner, employeeId);
        InputValidator validator = new InputValidator();
        if (input == null || input.shiftId() == null) validator.reject("shiftId", "Choose a shift.");
        LocalDate today = todayOf(owner.businessId());
        LocalDate effectiveFrom = today;
        if (input != null && input.effectiveFrom() != null && !input.effectiveFrom().isBlank()) {
            effectiveFrom = BusinessCalendar.parseDateKey(input.effectiveFrom().trim()).orElse(null);
            if (effectiveFrom == null) validator.reject("effectiveFrom", "The date is a date (YYYY-MM-DD).");
        }
        validator.throwIfInvalid();
        if (effectiveFrom.isBefore(today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SHIFT_EFFECTIVE_DATE_IN_PAST, "The shift cannot start on a date that has passed.");
        }
        Shift shift = shiftService.requireActiveShift(owner.businessId(), input.shiftId());
        applyShiftAssignment(owner.businessId(), owner.userId(), employeeId, shift, effectiveFrom);
        return findRow(owner, employeeId);
    }

    /**
     * Gives the employee {@code shift} from {@code effectiveFrom} on (the caller has checked that the shift is active and the date is not
     * in the past) and audits it as {@code employee.shift_changed} with {@code actorId} (null when JurisOne's roster sync does it). The
     * same shift again changes nothing and writes nothing. Returns whether the assignment changed. Never throws a business-rule
     * refusal, so it may run inside a larger transaction.
     */
    boolean applyShiftAssignment(UUID businessId, UUID actorId, UUID employeeId, Shift shift, LocalDate effectiveFrom) {
        EmployeeSchedule schedule = shiftResolver.schedulesOf(businessId, List.of(employeeId), effectiveFrom, effectiveFrom).get(employeeId);
        Optional<ShiftAssignment> sameDate = shiftAssignmentRepository.findByEmployeeIdAndEffectiveFrom(employeeId, effectiveFrom);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("shiftId", schedule.assignedOn(effectiveFrom).id() == null ? null : schedule.assignedOn(effectiveFrom).id().toString());
        before.put("shiftName", schedule.assignedOn(effectiveFrom).name());
        if (shift.getId().equals(schedule.assignedOn(effectiveFrom).id())) return false;
        ShiftAssignment assignment = sameDate.orElseGet(ShiftAssignment::new);
        assignment.setBusinessId(businessId);
        assignment.setEmployeeId(employeeId);
        assignment.setShiftId(shift.getId());
        assignment.setEffectiveFrom(effectiveFrom);
        shiftAssignmentRepository.saveAndFlush(assignment);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("shiftId", shift.getId().toString());
        after.put("shiftName", shift.getName());
        after.put("effectiveFrom", effectiveFrom.toString());
        auditService.record(new AuditEntry(businessId, actorId, employeeId, AuditAction.EMPLOYEE_SHIFT_CHANGED, USER_ENTITY,
                employeeId.toString(), before, after, null));
        return true;
    }

    /** Adds an employee (active, signs in with an emailed code); returns the new id. */
    public UUID create(CurrentUser owner, EmployeeInput input) {
        return auditRefusals(owner, null, "create", () -> createEmployee(owner, input));
    }

    private UUID createEmployee(CurrentUser owner, EmployeeInput input) {
        EmployeeInput employeeInput = input == null ? new EmployeeInput(null, null, null, null, null, null) : input;
        InputValidator validator = new InputValidator();
        String employeeCode = validator.employeeCode("employeeCode", employeeInput.employeeCode());
        String firstName = validator.requiredText("firstName", "First name", employeeInput.firstName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String lastName = validator.requiredText("lastName", "Last name", employeeInput.lastName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String email = validator.email("email", employeeInput.email());
        validator.throwIfInvalid();
        Role role = employeeInput.role() == null ? Role.EMPLOYEE : employeeInput.role();
        requireAssignableRole(role);
        requireUniqueEmail(email, null);
        requireUniqueEmployeeCode(owner.businessId(), employeeCode, null);
        requireValidManager(owner.businessId(), null, employeeInput.managerId());

        User employee = new User();
        employee.setBusinessId(owner.businessId());
        employee.setEmployeeCode(employeeCode);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setRole(role);
        employee.setManagerId(employeeInput.managerId());
        userRepository.saveAndFlush(employee);
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employee.getId(), AuditAction.EMPLOYEE_CREATED, USER_ENTITY,
                employee.getId().toString(), null, employeeDetails(employee), null));
        return employee.getId();
    }

    /**
     * Changes the fields that were sent; nothing changed writes no audit entry, a change writes the whole record before and after
     * (every field, role and manager included). Returns the employee as now stored.
     */
    public EmployeeRow update(CurrentUser owner, UUID employeeId, EmployeeChanges changes) {
        return auditRefusals(owner, employeeId, "update", () -> updateEmployee(owner, employeeId, changes));
    }

    private EmployeeRow updateEmployee(CurrentUser owner, UUID employeeId, EmployeeChanges changes) {
        lockEmployeeRow(employeeId);
        User employee = loadEmployee(owner, employeeId);
        if (changes == null) return findRow(owner, employeeId);
        Map<String, Object> before = employeeDetails(employee);

        InputValidator validator = new InputValidator();
        String employeeCode = changes.employeeCode() == null ? employee.getEmployeeCode()
                : validator.employeeCode("employeeCode", changes.employeeCode().orElse(null));
        String firstName = changes.firstName() == null ? employee.getFirstName()
                : validator.requiredText("firstName", "First name", changes.firstName().orElse(null), InputValidator.MAXIMUM_NAME_LENGTH);
        String lastName = changes.lastName() == null ? employee.getLastName()
                : validator.requiredText("lastName", "Last name", changes.lastName().orElse(null), InputValidator.MAXIMUM_NAME_LENGTH);
        String email = changes.email() == null ? employee.getEmail() : validator.email("email", changes.email().orElse(null));
        if (changes.role() != null && changes.role().isEmpty()) validator.reject("role", "Role is required.");
        validator.throwIfInvalid();
        Role role = changes.role() == null ? employee.getRole() : changes.role().get();
        UUID managerId = changes.managerId() == null ? employee.getManagerId() : changes.managerId().orElse(null);

        if (role != employee.getRole()) {
            if (employee.getRole() == Role.ADMIN) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.ROLE_NOT_ASSIGNABLE, "The owner's role cannot be changed here.");
            }
            requireAssignableRole(role);
            if (role == Role.EMPLOYEE && employeeQueryRepository.findRow(owner.businessId(), employeeId).orElseThrow().activeReportCount() > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_HAS_REPORTS,
                        "This manager still has active reports. Give them another manager first.");
            }
        }
        if (!email.equals(employee.getEmail())) requireUniqueEmail(email, employeeId);
        if (employeeCode != null && !employeeCode.equals(employee.getEmployeeCode())) requireUniqueEmployeeCode(owner.businessId(), employeeCode, employeeId);
        if (!Objects.equals(managerId, employee.getManagerId())) requireValidManager(owner.businessId(), employeeId, managerId);

        employee.setEmployeeCode(employeeCode);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setRole(role);
        employee.setManagerId(managerId);
        Map<String, Object> after = employeeDetails(employee);
        if (!after.equals(before)) {
            userRepository.saveAndFlush(employee);
            auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId, AuditAction.EMPLOYEE_UPDATED, USER_ENTITY,
                    employeeId.toString(), before, after, null));
        }
        return findRow(owner, employeeId);
    }

    /**
     * Deactivates (ends every session at once) or reactivates an employee. Setting the state the employee already has changes
     * nothing and writes no audit entry.
     */
    public EmployeeRow setStatus(CurrentUser owner, UUID employeeId, EmployeeStatusChange statusChange) {
        return auditRefusals(owner, employeeId, "status", () -> changeStatus(owner, employeeId, statusChange));
    }

    private EmployeeRow changeStatus(CurrentUser owner, UUID employeeId, EmployeeStatusChange statusChange) {
        lockEmployeeRow(employeeId);
        User employee = loadEmployee(owner, employeeId);
        InputValidator validator = new InputValidator();
        if (statusChange == null || statusChange.isActive() == null) validator.reject("isActive", "Say whether the employee is active (isActive).");
        String reason = validator.optionalText("reason", "Reason", statusChange == null ? null : statusChange.reason(), InputValidator.MAXIMUM_REASON_LENGTH);
        validator.throwIfInvalid();
        boolean isActive = statusChange.isActive();
        if (isActive == employee.isActive()) return findRow(owner, employeeId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("isActive", isActive);
        if (!isActive) {
            if (employeeId.equals(owner.userId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.CANNOT_DEACTIVATE_SELF, "You cannot deactivate your own account.");
            }
            if (employee.getRole() == Role.ADMIN && employeeQueryRepository.countOtherActiveOwners(owner.businessId(), employeeId) == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.LAST_ACTIVE_OWNER, "The business must keep at least one active owner.");
            }
        }
        if (!isActive) {
            // no record is left running for a deactivated account: the open workday ends now (audited as workday.closed)
            timeTrackingService.closeOpenWorkday(owner.businessId(), owner.userId(), employeeId, reason)
                    .ifPresent(workdayId -> after.put("closedWorkdayId", workdayId.toString()));
        }
        employee.setActive(isActive);
        employee.setDeactivatedByRosterSync(false); // the owner decided: JurisOne's roster sync no longer reactivates this person
        userRepository.saveAndFlush(employee);
        if (!isActive) after.put("endedSessions", sessionService.revokeAllOfUser(employeeId));
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId,
                isActive ? AuditAction.EMPLOYEE_REACTIVATED : AuditAction.EMPLOYEE_DEACTIVATED, USER_ENTITY, employeeId.toString(),
                Map.of("isActive", !isActive), after, reason));
        return findRow(owner, employeeId);
    }

    /**
     * Switches an employee's sign-in off or on (the person stays active and visible). Setting the state the employee already has
     * changes nothing and writes no audit entry. Switching off ends every session, closes the open workday and is refused (422
     * {@code CANNOT_DISABLE_OWN_LOGIN}) for the owner's own account; switching on restores sign-in only.
     */
    public EmployeeRow setLoginAccess(CurrentUser owner, UUID employeeId, LoginAccessChange loginAccessChange) {
        return auditRefusals(owner, employeeId, "loginAccess", () -> changeLoginAccess(owner, employeeId, loginAccessChange));
    }

    private EmployeeRow changeLoginAccess(CurrentUser owner, UUID employeeId, LoginAccessChange loginAccessChange) {
        lockEmployeeRow(employeeId);
        User employee = loadEmployee(owner, employeeId);
        InputValidator validator = new InputValidator();
        if (loginAccessChange == null || loginAccessChange.isEnabled() == null) validator.reject("isEnabled", "Say whether sign-in is enabled (isEnabled).");
        String reason = validator.optionalText("reason", "Reason", loginAccessChange == null ? null : loginAccessChange.reason(),
                InputValidator.MAXIMUM_REASON_LENGTH);
        validator.throwIfInvalid();
        boolean isEnabled = loginAccessChange.isEnabled();
        if (isEnabled == employee.isLoginEnabled()) return findRow(owner, employeeId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("isLoginEnabled", isEnabled);
        if (!isEnabled) {
            if (employeeId.equals(owner.userId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.CANNOT_DISABLE_OWN_LOGIN, "You cannot switch off your own sign-in.");
            }
            // no record is left running for a person who can no longer sign in: the open workday ends now (audited as workday.closed)
            timeTrackingService.closeOpenWorkday(owner.businessId(), owner.userId(), employeeId, LOGIN_DISABLED_REASON)
                    .ifPresent(workdayId -> after.put("closedWorkdayId", workdayId.toString()));
        }
        employee.setLoginEnabled(isEnabled);
        userRepository.saveAndFlush(employee);
        if (!isEnabled) after.put("endedSessions", sessionService.revokeAllOfUserForDisabledLogin(employeeId));
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId,
                isEnabled ? AuditAction.EMPLOYEE_LOGIN_ENABLED : AuditAction.EMPLOYEE_LOGIN_DISABLED, USER_ENTITY, employeeId.toString(),
                Map.of("isLoginEnabled", !isEnabled), after, reason));
        return findRow(owner, employeeId);
    }

    /**
     * Runs a write; a business rule's refusal (422) is audited as {@code employee.change_refused} with the code as reason, in its own
     * transaction (the write's transaction rolls back), and then answered as before.
     */
    private <T> T auditRefusals(CurrentUser owner, UUID employeeId, String operation, Supplier<T> write) {
        try {
            return transactionTemplate.execute(transactionStatus -> write.get());
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNPROCESSABLE_ENTITY) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("operation", operation);
                details.put("code", e.code());
                auditService.recordIndependently(new AuditEntry(owner.businessId(), owner.userId(), employeeId, AuditAction.EMPLOYEE_CHANGE_REFUSED,
                        USER_ENTITY, employeeId == null ? null : employeeId.toString(), null, details, e.code()));
            }
            throw e;
        }
    }

    /** What {@code GET /api/v1/me} adds for the signed-in user: employee code, manager name, business time zone. */
    @Transactional(readOnly = true)
    public UserProfileDetails findProfileDetails(CurrentUser currentUser) {
        return employeeQueryRepository.findProfileDetails(currentUser.userId());
    }

    /**
     * Takes the employee's row lock before the employee is read, so a change waits for a concurrent edit, deactivation or time event
     * of the same person and then works on what they left (no lost update, no second deactivation). 404 for somebody who is not a
     * user of a business visible to the caller.
     */
    private void lockEmployeeRow(UUID employeeId) {
        employeeLockRepository.lockEmployee(employeeId).orElseThrow(() -> new OutOfScopeException(USER_ENTITY, NOT_FOUND));
    }

    private User loadEmployee(CurrentUser owner, UUID employeeId) {
        return userRepository.findById(employeeId)
                .filter(user -> owner.businessId().equals(user.getBusinessId()) && user.getRole() != Role.SUPER_ADMIN)
                .orElseThrow(() -> new OutOfScopeException(USER_ENTITY, NOT_FOUND));
    }

    private static void requireAssignableRole(Role role) {
        if (role != Role.EMPLOYEE && role != Role.MANAGER) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.ROLE_NOT_ASSIGNABLE, "An employee's role is EMPLOYEE or MANAGER.");
        }
    }

    /** 409 when another user (any business) has the email. Also used for a new business's owner. */
    void requireUniqueEmail(String email, UUID excludedUserId) {
        if (employeeQueryRepository.isEmailInUse(email, excludedUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.EMAIL_IN_USE, "An account with this email already exists.");
        }
    }

    private void requireUniqueEmployeeCode(UUID businessId, String employeeCode, UUID excludedUserId) {
        if (employeeCode != null && employeeQueryRepository.isEmployeeCodeInUse(businessId, employeeCode, excludedUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.EMPLOYEE_CODE_IN_USE, "This employee code is already in use.");
        }
    }

    /** {@code employeeId} null for a new employee (no cycle possible yet); {@code managerId} null = no manager (always fine). */
    private void requireValidManager(UUID businessId, UUID employeeId, UUID managerId) {
        if (managerId == null) return;
        if (managerId.equals(employeeId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_IS_SELF, "An employee cannot be their own manager.");
        }
        User manager = userRepository.findById(managerId).filter(user -> businessId.equals(user.getBusinessId()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_NOT_FOUND, "Choose a manager from this business."));
        if (!manager.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_INACTIVE, "The chosen manager is deactivated.");
        }
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_ROLE_REQUIRED, "The chosen manager needs the MANAGER role.");
        }
        // a loop: the chosen manager is somewhere under the employee (every depth, so ReportingLine walks the whole line down)
        if (employeeId != null && new ReportingLine(employeeQueryRepository.findManagerIdsByEmployeeId(businessId))
                .visibleEmployeeIds(employeeId, Integer.MAX_VALUE).contains(managerId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_CYCLE,
                    "The chosen manager reports to this employee, so the reporting line would go round in a circle.");
        }
    }

    /** The audited fields of a user (before / after). */
    static Map<String, Object> employeeDetails(User user) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeCode", user.getEmployeeCode());
        details.put("firstName", user.getFirstName());
        details.put("lastName", user.getLastName());
        details.put("email", user.getEmail());
        details.put("role", user.getRole().name());
        details.put("isActive", user.isActive());
        details.put("managerId", user.getManagerId() == null ? null : user.getManagerId().toString());
        return details;
    }
}

package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.Shift;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.BusinessSuspendedException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.repository.ShiftRepository;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.security.IntegrationServiceClaims;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import com.jurislpo.juristrack.service.ScheduleService.RosterEntryOutcome;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JurisOne pushes its employees and shifts ({@code PUT /api/v1/integration/employees}, caller: JurisOne's service token) so that a manager
 * sees every report even before those people ever opened JurisTrack, each person is on the right shift, and people deactivated in
 * JurisOne are deactivated here. One business per call (the one the token names); any subset of its employees is fine, the ones not sent
 * are left untouched; the same body twice changes nothing.
 * <ol>
 *   <li><b>Business:</b> found by {@code jurisone_business_id}, else created (never an existing standalone business: that moves to
 *       JurisOne only through the super admin's explicit link); its name follows the token; a suspended business is refused (403).</li>
 *   <li><b>Shifts</b> are upserted by {@code code}: created, or updated (future occurrences only); an owner's shift of the same name
 *       without a code is adopted; a shift missing from the call is left as it is.</li>
 *   <li><b>Employees</b> are matched by {@code jurisone_employee_id}, else by email inside the business (linked), else created.
 *       A person who cannot be applied is <b>skipped with a reason code</b> and the rest is applied. Role: MANAGER or EMPLOYEE, never
 *       ADMIN, and an existing ADMIN stays the owner. Managers are resolved by JurisOne id in the same call or already stored, in any order
 *       (a person is never their own manager; a loop is refused for the person who would close it). {@code isActive=false} deactivates exactly like the owner
 *       does (the open workday is closed, sessions end); {@code isActive=true} reactivates only a person this sync deactivated.</li>
 *   <li><b>Shift assignment and roster:</b> {@code shiftCode} assigns that shift from today (history kept; the same shift again changes
 *       nothing); {@code schedule} sets roster entries (a week off = no shift code), skipping dates that already have a workday.</li>
 * </ol>
 * Everything runs in one transaction in system mode (the business is named in every query), under a lock on the business row so two
 * syncs of one business never interleave. Audit: one {@code integration.roster_synced} entry per call (counts only) plus the usual entry
 * of each change, without an actor, with source INTEGRATION; a repeat that changes nothing writes only the first.
 */
@Service
public class RosterSyncService {

    public static final int MAXIMUM_EMPLOYEES = 5000;
    public static final int MAXIMUM_SHIFTS = 200;
    public static final int MAXIMUM_SCHEDULE_ENTRIES = ScheduleService.MAXIMUM_DAYS;

    // ── the reason codes of a skipped employee, shift or roster date ──
    public static final String INVALID_EMAIL = "invalid_email";
    public static final String INVALID_NAME = "invalid_name";
    public static final String INVALID_EMPLOYEE_CODE = "invalid_employee_code";
    public static final String INVALID_ROLE = "invalid_role";
    public static final String INVALID_MANAGER_ID = "invalid_manager_id";
    public static final String INVALID_SCHEDULE_DATE = "invalid_schedule_date";
    public static final String DUPLICATE_SCHEDULE_DATE = "duplicate_schedule_date";
    public static final String SCHEDULE_TOO_LONG = "schedule_too_long";
    public static final String DUPLICATE_EMPLOYEE = "duplicate_employee_in_request";
    public static final String DUPLICATE_EMAIL = "duplicate_email_in_request";
    public static final String DUPLICATE_EMPLOYEE_CODE = "duplicate_employee_code_in_request";
    public static final String EMAIL_OF_ANOTHER_BUSINESS = "email_of_another_business";
    public static final String EMAIL_OF_SUPER_ADMIN = "email_of_super_admin";
    public static final String EMAIL_LINKED_TO_ANOTHER_EMPLOYEE = "email_linked_to_another_employee";
    public static final String EMAIL_IN_USE = "email_in_use";
    public static final String EMPLOYEE_OF_ANOTHER_BUSINESS = "employee_of_another_business";
    public static final String EMPLOYEE_CODE_IN_USE = "employee_code_in_use";
    public static final String UNKNOWN_SHIFT_CODE = "unknown_shift_code";
    public static final String SHIFT_INACTIVE = "shift_inactive";
    public static final String UNKNOWN_MANAGER = "unknown_manager";
    public static final String MANAGER_CYCLE = "manager_cycle";
    public static final String OWNER_CANNOT_BE_DEACTIVATED = "owner_cannot_be_deactivated";
    public static final String SHIFT_NAME_IN_USE = "shift_name_in_use";
    public static final String SCHEDULE_DATE_HAS_WORKDAY = "schedule_date_has_workday";
    public static final String SCHEDULE_DATE_TOO_FAR = "schedule_date_too_far";
    /** Not an error: an inactive person who does not exist yet is not created; counted as unchanged and left out of {@code skipped}. */
    static final String INACTIVE_NOT_CREATED = "inactive_not_created";

    /** The reason written on the audit entries of a deactivation by the sync. */
    static final String DEACTIVATION_REASON = "jurisone_roster_sync";

    /** The request body: {@code shifts} and {@code employees} may each be absent or empty. */
    public record RosterSyncInput(List<RosterShiftInput> shifts, List<RosterEmployeeInput> employees) {}

    /** One shift of the business: {@code code} is JurisOne's, times are {@code HH:mm} on the business time zone's wall clock, the grace defaults to 180. */
    public record RosterShiftInput(String code, String name, String startTime, String endTime, Integer earlyStartMinutes, Integer lateEndMinutes) {}

    /**
     * One employee: {@code role} MANAGER or EMPLOYEE (absent: EMPLOYEE for a new person, unchanged for an existing one), {@code employeeCode}
     * absent or blank leaves the stored code, {@code isActive} absent means true, {@code shiftCode} absent leaves the assignment, {@code schedule}
     * absent leaves the roster.
     */
    public record RosterEmployeeInput(String employeeId, String email, String firstName, String lastName, String employeeCode, String role,
            String managerEmployeeId, @JsonProperty("isActive") Boolean isActive, String shiftCode, List<RosterScheduleEntryInput> schedule) {}

    /** One roster date: {@code date} {@code YYYY-MM-DD}, {@code shiftCode} null = a week off. */
    public record RosterScheduleEntryInput(String date, String shiftCode) {}

    public record ShiftCounts(int created, int updated, int unchanged) {}

    public record EmployeeCounts(int created, int updated, int deactivated, int reactivated, int unchanged) {}

    public record ScheduleEntryCounts(int set, int unchanged, int skipped) {}

    public record SkippedEmployee(UUID employeeId, String reason) {}

    public record SkippedShift(String code, String reason) {}

    public record SkippedScheduleEntry(UUID employeeId, String date, String reason) {}

    /**
     * The answer. {@code skipped} lists the employees that were not applied at all; {@code skippedShifts} the shifts that were not applied
     * (their employees then fail with {@code unknown_shift_code}); {@code skippedScheduleEntries} the roster dates that were not set (the
     * employee itself was applied; {@code scheduleEntries.skipped} counts them).
     */
    public record RosterSyncResult(ShiftCounts shifts, EmployeeCounts employees, ScheduleEntryCounts scheduleEntries, List<SkippedEmployee> skipped,
            List<SkippedShift> skippedShifts, List<SkippedScheduleEntry> skippedScheduleEntries) {}

    private static final String SHIFT_ENTITY = "shift";
    private static final String USER_ENTITY = "user";
    private static final String BUSINESS_ENTITY = "business";

    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeLockRepository employeeLockRepository;
    private final IntegrationProvisioningService provisioningService;
    private final EmployeeService employeeService;
    private final ScheduleService scheduleService;
    private final TimeTrackingService timeTrackingService;
    private final SessionService sessionService;
    private final AuditService auditService;

    public RosterSyncService(Clock clock, TransactionTemplate transactionTemplate, BusinessRepository businessRepository, UserRepository userRepository,
            ShiftRepository shiftRepository, EmployeeLockRepository employeeLockRepository, IntegrationProvisioningService provisioningService,
            EmployeeService employeeService, ScheduleService scheduleService, TimeTrackingService timeTrackingService, SessionService sessionService,
            AuditService auditService) {
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.shiftRepository = shiftRepository;
        this.employeeLockRepository = employeeLockRepository;
        this.provisioningService = provisioningService;
        this.employeeService = employeeService;
        this.scheduleService = scheduleService;
        this.timeTrackingService = timeTrackingService;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    // ── the request, read and checked before anything is written ──

    private record ParsedShift(String code, String name, LocalTime startTime, LocalTime endTime, int earlyStartMinutes, int lateEndMinutes) {}

    private record ParsedScheduleEntry(LocalDate date, String shiftCode) {}

    /** One employee of the call while it is planned and applied. */
    private static final class Candidate {
        final UUID jurisoneEmployeeId;
        String email;
        String firstName;
        String lastName;
        String employeeCode;
        Role role;
        UUID managerJurisoneId;
        boolean isActive = true;
        String shiftCode;
        List<ParsedScheduleEntry> schedule = List.of();
        String skipReason;
        /** The reason found while reading the request: it does not depend on the stored data, so a retry keeps it. */
        String readingSkipReason;
        // filled while planning
        User user;
        boolean isNew;
        boolean isLink;
        Shift shift;
        final Map<LocalDate, Shift> shiftByDate = new LinkedHashMap<>();

        Candidate(UUID jurisoneEmployeeId) {
            this.jurisoneEmployeeId = jurisoneEmployeeId;
        }

        boolean isLive() {
            return skipReason == null;
        }

        void skip(String reason) {
            if (skipReason == null) skipReason = reason;
        }
    }

    /**
     * Applies the roster of the business the service token names. 400 {@code VALIDATION_FAILED} (field details) for a malformed body or shift,
     * 413 for more than {@value #MAXIMUM_EMPLOYEES} employees, 403 {@code BUSINESS_SUSPENDED}; a person who cannot be applied is skipped and
     * reported in the answer instead.
     */
    public RosterSyncResult sync(IntegrationServiceClaims caller, RosterSyncInput input) {
        List<RosterShiftInput> shiftInputs = input == null || input.shifts() == null ? List.of() : input.shifts();
        List<RosterEmployeeInput> employeeInputs = input == null || input.employees() == null ? List.of() : input.employees();
        if (employeeInputs.size() > MAXIMUM_EMPLOYEES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, Problems.PAYLOAD_TOO_LARGE, "Send at most " + MAXIMUM_EMPLOYEES + " employees in one call.");
        }
        InputValidator validator = new InputValidator();
        if (shiftInputs.size() > MAXIMUM_SHIFTS) validator.reject("shifts", "Send at most " + MAXIMUM_SHIFTS + " shifts in one call.");
        List<ParsedShift> shifts = readShifts(validator, shiftInputs);
        List<Candidate> candidates = readEmployees(validator, employeeInputs);
        for (Candidate candidate : candidates) candidate.readingSkipReason = candidate.skipReason;
        validator.throwIfInvalid();
        try {
            return attempt(caller, shifts, candidates, employeeInputs.size(), shiftInputs.size());
        } catch (DataIntegrityViolationException e) {
            // two first calls of a new business (or a concurrent change of the same person) at the same moment: the loser's write fails once
            // and the retry finds the rows
            return attempt(caller, shifts, candidates, employeeInputs.size(), shiftInputs.size());
        }
    }

    private RosterSyncResult attempt(IntegrationServiceClaims caller, List<ParsedShift> shifts, List<Candidate> candidates, int receivedEmployees,
            int receivedShifts) {
        for (Candidate candidate : candidates) resetPlan(candidate);
        try {
            return transactionTemplate.execute(status -> syncInTransaction(caller, shifts, candidates, receivedEmployees, receivedShifts));
        } catch (BusinessSuspendedException e) {
            UUID businessId = businessRepository.findByJurisoneBusinessId(caller.jurisoneBusinessId()).map(Business::getId).orElse(null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", IntegrationProvisioningService.REFUSED_BUSINESS_SUSPENDED);
            details.put("aggregationWindowMinutes", AuditAggregation.WINDOW.toMinutes());
            auditService.recordOncePerWindow("roster|" + businessId, new AuditEntry(businessId, null, null, AuditAction.INTEGRATION_USER_REFUSED,
                    BUSINESS_ENTITY, businessId == null ? null : businessId.toString(), null, details, IntegrationProvisioningService.REFUSED_BUSINESS_SUSPENDED));
            throw e;
        }
    }

    /** A retry starts from the request again: whatever planning wrote on a candidate is dropped (parse-time skips stay). */
    private static void resetPlan(Candidate candidate) {
        candidate.skipReason = candidate.readingSkipReason;
        candidate.user = null;
        candidate.isNew = false;
        candidate.isLink = false;
        candidate.shift = null;
        candidate.shiftByDate.clear();
    }

    private static List<ParsedShift> readShifts(InputValidator validator, List<RosterShiftInput> shiftInputs) {
        List<ParsedShift> shifts = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        for (int i = 0; i < shiftInputs.size(); i++) {
            RosterShiftInput shiftInput = shiftInputs.get(i);
            String prefix = "shifts[" + i + "].";
            if (shiftInput == null) {
                validator.reject("shifts[" + i + "]", "A shift is an object.");
                continue;
            }
            String code = shiftInput.code() == null ? "" : shiftInput.code().trim();
            if (code.isEmpty() || code.length() > 40) validator.reject(prefix + "code", "The shift code is required (1 to 40 characters).");
            else if (!seenCodes.add(code)) validator.reject(prefix + "code", "A shift code can only be listed once.");
            String name = validator.requiredText(prefix + "name", "Name", shiftInput.name(), InputValidator.MAXIMUM_NAME_LENGTH);
            LocalTime startTime = ShiftService.readTime(validator, prefix + "startTime", shiftInput.startTime());
            LocalTime endTime = ShiftService.readTime(validator, prefix + "endTime", shiftInput.endTime());
            int earlyStartMinutes = ShiftService.readGrace(validator, prefix + "earlyStartMinutes", shiftInput.earlyStartMinutes());
            int lateEndMinutes = ShiftService.readGrace(validator, prefix + "lateEndMinutes", shiftInput.lateEndMinutes());
            shifts.add(new ParsedShift(code, name, startTime, endTime, earlyStartMinutes, lateEndMinutes));
        }
        return shifts;
    }

    /** An unreadable {@code employeeId} fails the whole call (400: nobody could be named); every other problem skips that one employee. */
    private static List<Candidate> readEmployees(InputValidator validator, List<RosterEmployeeInput> employeeInputs) {
        List<Candidate> candidates = new ArrayList<>();
        Set<UUID> seenEmployeeIds = new HashSet<>();
        for (int i = 0; i < employeeInputs.size(); i++) {
            RosterEmployeeInput employeeInput = employeeInputs.get(i);
            UUID employeeId = employeeInput == null ? null : readUuid(employeeInput.employeeId());
            if (employeeId == null) {
                validator.reject("employees[" + i + "].employeeId", "The employee id is required and is a UUID.");
                continue;
            }
            Candidate candidate = new Candidate(employeeId);
            candidates.add(candidate);
            if (!seenEmployeeIds.add(employeeId)) {
                candidate.skip(DUPLICATE_EMPLOYEE);
                continue;
            }
            readEmployeeFields(candidate, employeeInput);
        }
        return candidates;
    }

    private static void readEmployeeFields(Candidate candidate, RosterEmployeeInput employeeInput) {
        InputValidator emailValidator = new InputValidator();
        candidate.email = emailValidator.email("email", employeeInput.email());
        if (emailValidator.hasErrors()) {
            candidate.skip(INVALID_EMAIL);
            return;
        }
        InputValidator nameValidator = new InputValidator();
        candidate.firstName = nameValidator.requiredText("firstName", "First name", employeeInput.firstName(), InputValidator.MAXIMUM_NAME_LENGTH);
        candidate.lastName = employeeInput.lastName() == null || employeeInput.lastName().isBlank() ? ""
                : nameValidator.requiredText("lastName", "Last name", employeeInput.lastName(), InputValidator.MAXIMUM_NAME_LENGTH);
        if (nameValidator.hasErrors()) {
            candidate.skip(INVALID_NAME);
            return;
        }
        InputValidator codeValidator = new InputValidator();
        candidate.employeeCode = codeValidator.employeeCode("employeeCode", employeeInput.employeeCode());
        if (codeValidator.hasErrors()) {
            candidate.skip(INVALID_EMPLOYEE_CODE);
            return;
        }
        if (employeeInput.role() != null && !employeeInput.role().isBlank()) {
            switch (employeeInput.role().trim().toUpperCase(Locale.ROOT)) {
                case "MANAGER" -> candidate.role = Role.MANAGER;
                case "EMPLOYEE" -> candidate.role = Role.EMPLOYEE;
                default -> {
                    candidate.skip(INVALID_ROLE);
                    return;
                }
            }
        }
        if (employeeInput.managerEmployeeId() != null && !employeeInput.managerEmployeeId().isBlank()) {
            candidate.managerJurisoneId = readUuid(employeeInput.managerEmployeeId());
            if (candidate.managerJurisoneId == null) {
                candidate.skip(INVALID_MANAGER_ID);
                return;
            }
        }
        candidate.isActive = employeeInput.isActive() == null || employeeInput.isActive();
        candidate.shiftCode = employeeInput.shiftCode() == null || employeeInput.shiftCode().isBlank() ? null : employeeInput.shiftCode().trim();
        readSchedule(candidate, employeeInput.schedule());
    }

    private static void readSchedule(Candidate candidate, List<RosterScheduleEntryInput> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (entries.size() > MAXIMUM_SCHEDULE_ENTRIES) {
            candidate.skip(SCHEDULE_TOO_LONG);
            return;
        }
        List<ParsedScheduleEntry> parsed = new ArrayList<>();
        Set<LocalDate> seenDates = new HashSet<>();
        for (RosterScheduleEntryInput entry : entries) {
            Optional<LocalDate> date = entry == null || entry.date() == null ? Optional.empty() : BusinessCalendar.parseDateKey(entry.date().trim());
            if (date.isEmpty()) {
                candidate.skip(INVALID_SCHEDULE_DATE);
                return;
            }
            if (!seenDates.add(date.get())) {
                candidate.skip(DUPLICATE_SCHEDULE_DATE);
                return;
            }
            String shiftCode = entry.shiftCode() == null || entry.shiftCode().isBlank() ? null : entry.shiftCode().trim();
            parsed.add(new ParsedScheduleEntry(date.get(), shiftCode));
        }
        candidate.schedule = parsed;
    }

    private static UUID readUuid(String text) {
        try {
            return text == null || text.isBlank() ? null : UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── the call, in one transaction ──

    private RosterSyncResult syncInTransaction(IntegrationServiceClaims caller, List<ParsedShift> parsedShifts, List<Candidate> candidates,
            int receivedEmployees, int receivedShifts) {
        Business business = findOrCreateBusiness(caller);
        UUID businessId = business.getId();
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        LocalDate today = BusinessCalendar.toDateKey(clock.instant(), zone);

        List<SkippedShift> skippedShifts = new ArrayList<>();
        int[] shiftCounts = new int[3]; // created, updated, unchanged
        Map<String, Shift> shiftsByCode = upsertShifts(businessId, parsedShifts, shiftCounts, skippedShifts);

        List<User> businessUsers = userRepository.findByBusinessId(businessId);
        planEmployees(businessId, businessUsers, candidates, shiftsByCode);

        int created = 0, updated = 0, deactivated = 0, reactivated = 0, unchanged = 0;
        int entriesSet = 0, entriesUnchanged = 0;
        List<SkippedScheduleEntry> skippedEntries = new ArrayList<>();
        List<Candidate> applied = candidates.stream().filter(Candidate::isLive).toList();

        Map<UUID, User> userByJurisoneId = new HashMap<>();
        for (User user : businessUsers) if (user.getJurisoneEmployeeId() != null) userByJurisoneId.put(user.getJurisoneEmployeeId(), user);
        Map<Candidate, Map<String, Object>> profileBefore = new HashMap<>();
        // pass 1: every person exists with their own details (managers are set in pass 2, when everybody exists)
        for (Candidate candidate : applied) {
            profileBefore.put(candidate, candidate.isNew ? null : profileOf(candidate.user));
            applyProfile(businessId, candidate);
            userByJurisoneId.put(candidate.jurisoneEmployeeId, candidate.user);
        }
        // pass 2: the reporting line, then the audit entry of each created or changed person
        Map<Candidate, Boolean> changedProfile = new HashMap<>();
        for (Candidate candidate : applied) {
            User user = candidate.user;
            user.setManagerId(candidate.managerJurisoneId == null || candidate.managerJurisoneId.equals(candidate.jurisoneEmployeeId) ? null
                    : userByJurisoneId.get(candidate.managerJurisoneId).getId());
            userRepository.saveAndFlush(user);
            Map<String, Object> before = profileBefore.get(candidate);
            Map<String, Object> after = profileOf(user);
            if (candidate.isNew) {
                auditService.record(new AuditEntry(businessId, null, user.getId(), AuditAction.USER_PROVISIONED, USER_ENTITY, user.getId().toString(), null,
                        provisionedDetails(user, candidate), null));
            } else if (!after.equals(before)) {
                auditService.record(new AuditEntry(businessId, null, user.getId(), AuditAction.USER_UPDATED_FROM_TOKEN, USER_ENTITY, user.getId().toString(),
                        before, after, null));
            }
            changedProfile.put(candidate, candidate.isNew || candidate.isLink || !after.equals(before));
        }
        // pass 3: active or deactivated, the shift from today, the roster
        for (Candidate candidate : applied) {
            User user = candidate.user;
            boolean isChanged = changedProfile.get(candidate);
            boolean wasDeactivated = false;
            boolean wasReactivated = false;
            if (!candidate.isActive && user.isActive()) {
                deactivate(businessId, user);
                wasDeactivated = true;
            } else if (candidate.isActive && !user.isActive() && user.isDeactivatedByRosterSync() && user.isLoginEnabled()) {
                reactivate(businessId, user);
                wasReactivated = true;
            }
            if (candidate.shift != null && employeeService.applyShiftAssignment(businessId, null, user.getId(), candidate.shift, employeeService.shiftEffectiveDateFromJurisOne(user.getId(), today))) isChanged = true;
            for (Map.Entry<LocalDate, Shift> entry : candidate.shiftByDate.entrySet()) {
                RosterEntryOutcome outcome = scheduleService.applyRosterEntry(businessId, user.getId(), entry.getKey(), entry.getValue(), today);
                switch (outcome) {
                    case SET -> entriesSet++;
                    case UNCHANGED -> entriesUnchanged++;
                    case DATE_HAS_WORKDAY -> skippedEntries.add(new SkippedScheduleEntry(candidate.jurisoneEmployeeId, entry.getKey().toString(),
                            SCHEDULE_DATE_HAS_WORKDAY));
                    case DATE_TOO_FAR -> skippedEntries.add(new SkippedScheduleEntry(candidate.jurisoneEmployeeId, entry.getKey().toString(),
                            SCHEDULE_DATE_TOO_FAR));
                }
            }
            if (candidate.isNew) created++;
            else if (wasDeactivated) deactivated++;
            else if (wasReactivated) reactivated++;
            else if (isChanged) updated++;
            else unchanged++;
        }

        int notCreated = (int) candidates.stream().filter(candidate -> INACTIVE_NOT_CREATED.equals(candidate.skipReason)).count();
        unchanged += notCreated;
        List<SkippedEmployee> skipped = candidates.stream().filter(candidate -> !candidate.isLive() && !INACTIVE_NOT_CREATED.equals(candidate.skipReason))
                .map(candidate -> new SkippedEmployee(candidate.jurisoneEmployeeId, candidate.skipReason)).toList();
        RosterSyncResult result = new RosterSyncResult(new ShiftCounts(shiftCounts[0], shiftCounts[1], shiftCounts[2]),
                new EmployeeCounts(created, updated, deactivated, reactivated, unchanged),
                new ScheduleEntryCounts(entriesSet, entriesUnchanged, skippedEntries.size()), skipped, skippedShifts, skippedEntries);
        recordRosterSynced(businessId, result, receivedEmployees, receivedShifts);
        return result;
    }

    private Business findOrCreateBusiness(IntegrationServiceClaims caller) {
        Optional<Business> found = businessRepository.findByJurisoneBusinessId(caller.jurisoneBusinessId());
        if (found.isEmpty()) return provisioningService.createBusiness(caller.jurisoneBusinessId(), caller.businessName());
        Business business = businessRepository.findByIdForUpdate(found.get().getId()).orElseThrow();
        if (business.isSuspended()) throw new BusinessSuspendedException();
        return provisioningService.renameFromToken(business, caller.businessName());
    }

    // ── shifts ──

    /** Creates or updates each shift by its code; returns every coded shift of the business (those not in the call as they are). */
    private Map<String, Shift> upsertShifts(UUID businessId, List<ParsedShift> parsedShifts, int[] counts, List<SkippedShift> skipped) {
        List<Shift> existingShifts = shiftRepository.findByBusinessId(businessId);
        Map<String, Shift> byCode = new HashMap<>();
        Map<String, Shift> byName = new HashMap<>();
        for (Shift shift : existingShifts) {
            if (shift.getCode() != null) byCode.put(shift.getCode(), shift);
            byName.put(shift.getName().toLowerCase(Locale.ROOT), shift);
        }
        for (ParsedShift parsed : parsedShifts) {
            Shift shift = byCode.get(parsed.code());
            String lowerName = parsed.name().toLowerCase(Locale.ROOT);
            Shift sameName = byName.get(lowerName);
            if (shift == null) {
                if (sameName != null && (sameName.isDefault() || sameName.getCode() != null)) {
                    skipped.add(new SkippedShift(parsed.code(), SHIFT_NAME_IN_USE));
                    continue;
                }
                if (sameName != null) { // the owner made this shift by hand: JurisOne's code is put on it and JurisOne's times win
                    applyShift(businessId, sameName, parsed, counts);
                    byCode.put(parsed.code(), sameName);
                    continue;
                }
                Shift created = new Shift();
                created.setBusinessId(businessId);
                created.setCode(parsed.code());
                created.setName(parsed.name());
                created.setStartTime(parsed.startTime());
                created.setEndTime(parsed.endTime());
                created.setEarlyStartMinutes(parsed.earlyStartMinutes());
                created.setLateEndMinutes(parsed.lateEndMinutes());
                shiftRepository.saveAndFlush(created);
                auditService.record(new AuditEntry(businessId, null, null, AuditAction.SHIFT_CREATED, SHIFT_ENTITY, created.getId().toString(), null,
                        shiftDetails(created), null));
                counts[0]++;
                byCode.put(parsed.code(), created);
                byName.put(lowerName, created);
                continue;
            }
            if (sameName != null && !sameName.getId().equals(shift.getId())) {
                skipped.add(new SkippedShift(parsed.code(), SHIFT_NAME_IN_USE));
                continue;
            }
            applyShift(businessId, shift, parsed, counts);
            byName.put(lowerName, shift);
        }
        return byCode;
    }

    /** Puts JurisOne's code, name, times and grace on the shift; audited only when something changed. */
    private void applyShift(UUID businessId, Shift shift, ParsedShift parsed, int[] counts) {
        Map<String, Object> before = shiftDetails(shift);
        shift.setCode(parsed.code());
        shift.setName(parsed.name());
        shift.setStartTime(parsed.startTime());
        shift.setEndTime(parsed.endTime());
        shift.setEarlyStartMinutes(parsed.earlyStartMinutes());
        shift.setLateEndMinutes(parsed.lateEndMinutes());
        Map<String, Object> after = shiftDetails(shift);
        if (after.equals(before)) {
            counts[2]++;
            return;
        }
        shiftRepository.saveAndFlush(shift);
        auditService.record(new AuditEntry(businessId, null, null, AuditAction.SHIFT_UPDATED, SHIFT_ENTITY, shift.getId().toString(), before, after, null));
        counts[1]++;
    }

    private static Map<String, Object> shiftDetails(Shift shift) {
        Map<String, Object> details = new LinkedHashMap<>(ShiftService.details(shift));
        details.put("code", shift.getCode());
        return details;
    }

    // ── employees: who is matched to whom, who is skipped ──

    /** Decides for every candidate: matched user (or new), skipped with a reason, and the shifts it names. Writes nothing. */
    private void planEmployees(UUID businessId, List<User> businessUsers, List<Candidate> candidates, Map<String, Shift> shiftsByCode) {
        List<Candidate> parsed = candidates.stream().filter(Candidate::isLive).toList();
        Set<String> emails = new HashSet<>();
        Set<UUID> employeeIds = new HashSet<>();
        for (Candidate candidate : parsed) {
            emails.add(candidate.email);
            employeeIds.add(candidate.jurisoneEmployeeId);
        }
        Map<String, User> userByEmail = new HashMap<>();
        if (!emails.isEmpty()) for (User user : userRepository.findByEmailIn(emails)) userByEmail.put(user.getEmail(), user);
        Map<UUID, User> userByJurisoneId = new HashMap<>();
        if (!employeeIds.isEmpty()) for (User user : userRepository.findByJurisoneEmployeeIdIn(employeeIds)) userByJurisoneId.put(user.getJurisoneEmployeeId(), user);
        Map<String, User> holderOfEmployeeCode = new HashMap<>();
        for (User user : businessUsers) if (user.getEmployeeCode() != null) holderOfEmployeeCode.put(user.getEmployeeCode(), user);

        Set<UUID> claimedUserIds = new HashSet<>();
        Set<String> claimedEmails = new HashSet<>();
        Set<String> claimedEmployeeCodes = new HashSet<>();
        for (Candidate candidate : parsed) {
            matchCandidate(businessId, candidate, userByJurisoneId, userByEmail);
            if (!candidate.isLive()) continue;
            if (candidate.isNew && !candidate.isActive) {
                candidate.skip(INACTIVE_NOT_CREATED);
                continue;
            }
            if (candidate.user != null && !claimedUserIds.add(candidate.user.getId())) {
                candidate.skip(DUPLICATE_EMPLOYEE);
                continue;
            }
            if (!claimedEmails.add(candidate.email)) {
                candidate.skip(DUPLICATE_EMAIL);
                continue;
            }
            if (candidate.employeeCode != null) {
                User holder = holderOfEmployeeCode.get(candidate.employeeCode);
                if (holder != null && (candidate.user == null || !holder.getId().equals(candidate.user.getId()))) {
                    candidate.skip(EMPLOYEE_CODE_IN_USE);
                    continue;
                }
                if (!claimedEmployeeCodes.add(candidate.employeeCode)) {
                    candidate.skip(DUPLICATE_EMPLOYEE_CODE);
                    continue;
                }
            }
            if (candidate.user != null && candidate.user.getRole() == Role.ADMIN && !candidate.isActive && candidate.user.isActive()) {
                candidate.skip(OWNER_CANNOT_BE_DEACTIVATED);
                continue;
            }
            resolveShifts(candidate, shiftsByCode);
        }
        resolveReportingLine(businessUsers, candidates);
    }

    private static void matchCandidate(UUID businessId, Candidate candidate, Map<UUID, User> userByJurisoneId, Map<String, User> userByEmail) {
        User matched = userByJurisoneId.get(candidate.jurisoneEmployeeId);
        User withSameEmail = userByEmail.get(candidate.email);
        if (matched != null) {
            if (!businessId.equals(matched.getBusinessId())) {
                candidate.skip(EMPLOYEE_OF_ANOTHER_BUSINESS);
                return;
            }
            if (withSameEmail != null && !withSameEmail.getId().equals(matched.getId())) {
                candidate.skip(withSameEmail.getRole() == Role.SUPER_ADMIN ? EMAIL_OF_SUPER_ADMIN : EMAIL_IN_USE);
                return;
            }
        } else if (withSameEmail != null) {
            if (withSameEmail.getRole() == Role.SUPER_ADMIN) {
                candidate.skip(EMAIL_OF_SUPER_ADMIN);
                return;
            }
            if (!businessId.equals(withSameEmail.getBusinessId())) {
                candidate.skip(EMAIL_OF_ANOTHER_BUSINESS);
                return;
            }
            if (withSameEmail.getJurisoneEmployeeId() != null) {
                candidate.skip(EMAIL_LINKED_TO_ANOTHER_EMPLOYEE);
                return;
            }
            matched = withSameEmail;
            candidate.isLink = true;
        }
        candidate.user = matched;
        candidate.isNew = matched == null;
    }

    private static void resolveShifts(Candidate candidate, Map<String, Shift> shiftsByCode) {
        if (candidate.shiftCode != null) {
            Shift shift = shiftsByCode.get(candidate.shiftCode);
            if (shift == null) {
                candidate.skip(UNKNOWN_SHIFT_CODE);
                return;
            }
            if (!shift.isActive()) {
                candidate.skip(SHIFT_INACTIVE);
                return;
            }
            candidate.shift = shift;
        }
        for (ParsedScheduleEntry entry : candidate.schedule) {
            if (entry.shiftCode() == null) {
                candidate.shiftByDate.put(entry.date(), null); // a week off
                continue;
            }
            Shift shift = shiftsByCode.get(entry.shiftCode());
            if (shift == null) {
                candidate.skip(UNKNOWN_SHIFT_CODE);
                return;
            }
            if (!shift.isActive()) {
                candidate.skip(SHIFT_INACTIVE);
                return;
            }
            candidate.shiftByDate.put(entry.date(), shift);
        }
    }

    /**
     * A manager is named by JurisOne id: known when a person of the business already has it or a person of this call gets it. The reporting line
     * is checked on a copy first, in the order of the call, so a person whose manager is unknown, or whose manager would close a loop, is skipped
     * here and nobody is half applied. Skipping a new person can make others' managers unknown, so the check repeats until nothing more is skipped.
     */
    private static void resolveReportingLine(List<User> businessUsers, List<Candidate> candidates) {
        boolean isAnythingSkipped;
        do {
            isAnythingSkipped = false;
            // a person is a node of the line under their JurisOne id when they have one (or get one now), else under their own id
            Map<UUID, UUID> nodeOfUserId = new HashMap<>();
            for (User user : businessUsers) nodeOfUserId.put(user.getId(), user.getJurisoneEmployeeId() != null ? user.getJurisoneEmployeeId() : user.getId());
            for (Candidate candidate : candidates) {
                if (candidate.isLive() && candidate.isLink) nodeOfUserId.put(candidate.user.getId(), candidate.jurisoneEmployeeId);
            }
            Map<UUID, UUID> managerOfNode = new HashMap<>();
            for (User user : businessUsers) {
                if (user.getManagerId() != null && nodeOfUserId.containsKey(user.getManagerId())) {
                    managerOfNode.put(nodeOfUserId.get(user.getId()), nodeOfUserId.get(user.getManagerId()));
                }
            }
            Set<UUID> knownNodes = new HashSet<>(nodeOfUserId.values());
            for (Candidate candidate : candidates) if (candidate.isLive()) knownNodes.add(candidate.jurisoneEmployeeId);
            for (Candidate candidate : candidates) {
                if (!candidate.isLive()) continue;
                UUID managerNode = candidate.managerJurisoneId == null || candidate.managerJurisoneId.equals(candidate.jurisoneEmployeeId) ? null
                        : candidate.managerJurisoneId;
                if (managerNode != null && !knownNodes.contains(managerNode)) {
                    candidate.skip(UNKNOWN_MANAGER);
                    isAnythingSkipped = true;
                    break;
                }
                if (managerNode != null && isOnTheWayUp(managerOfNode, managerNode, candidate.jurisoneEmployeeId)) {
                    candidate.skip(MANAGER_CYCLE);
                    isAnythingSkipped = true;
                    break;
                }
                if (managerNode == null) managerOfNode.remove(candidate.jurisoneEmployeeId);
                else managerOfNode.put(candidate.jurisoneEmployeeId, managerNode);
            }
        } while (isAnythingSkipped);
    }

    /** True when {@code searchedNode} is {@code startNode} or one of the managers above it. */
    private static boolean isOnTheWayUp(Map<UUID, UUID> managerOfNode, UUID startNode, UUID searchedNode) {
        UUID node = startNode;
        for (int steps = 0; node != null && steps <= managerOfNode.size() + 1; steps++) {
            if (node.equals(searchedNode)) return true;
            node = managerOfNode.get(node);
        }
        return false;
    }

    // ── employees: writing ──

    /** Creates the person or puts JurisOne's name, email, code and role on them (the manager follows in the next pass). */
    private void applyProfile(UUID businessId, Candidate candidate) {
        if (candidate.isNew) {
            User user = new User();
            user.setBusinessId(businessId);
            user.setJurisoneEmployeeId(candidate.jurisoneEmployeeId);
            user.setEmail(candidate.email);
            user.setFirstName(candidate.firstName);
            user.setLastName(candidate.lastName);
            user.setEmployeeCode(candidate.employeeCode);
            user.setRole(candidate.role == null ? Role.EMPLOYEE : candidate.role);
            if (!candidate.isActive) {
                user.setActive(false);
                user.setDeactivatedByRosterSync(true);
            }
            userRepository.saveAndFlush(user);
            candidate.user = user;
            return;
        }
        User user = candidate.user;
        employeeLockRepository.lockEmployee(user.getId()); // waits for a time event or a status change of the same person
        if (candidate.isLink) {
            user.setJurisoneEmployeeId(candidate.jurisoneEmployeeId);
            auditService.record(new AuditEntry(businessId, null, user.getId(), AuditAction.USER_LINKED, USER_ENTITY, user.getId().toString(), null,
                    Map.of("jurisoneEmployeeId", candidate.jurisoneEmployeeId.toString()), null));
        }
        user.setEmail(candidate.email);
        user.setFirstName(candidate.firstName);
        user.setLastName(candidate.lastName);
        if (candidate.employeeCode != null) user.setEmployeeCode(candidate.employeeCode);
        // JurisOne moves people between EMPLOYEE and MANAGER; an owner (ADMIN) stays the owner
        if (candidate.role != null && user.getRole() != Role.ADMIN) user.setRole(candidate.role);
        userRepository.saveAndFlush(user);
    }

    private void deactivate(UUID businessId, User user) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("isActive", false);
        // no record is left running for a deactivated account: the open workday ends now (audited as workday.closed)
        timeTrackingService.closeOpenWorkday(businessId, null, user.getId(), DEACTIVATION_REASON)
                .ifPresent(workdayId -> after.put("closedWorkdayId", workdayId.toString()));
        user.setActive(false);
        user.setDeactivatedByRosterSync(true);
        userRepository.saveAndFlush(user);
        after.put("endedSessions", sessionService.revokeAllOfUser(user.getId()));
        auditService.record(new AuditEntry(businessId, null, user.getId(), AuditAction.EMPLOYEE_DEACTIVATED, USER_ENTITY, user.getId().toString(),
                Map.of("isActive", true), after, DEACTIVATION_REASON));
    }

    private void reactivate(UUID businessId, User user) {
        user.setActive(true);
        user.setDeactivatedByRosterSync(false);
        userRepository.saveAndFlush(user);
        auditService.record(new AuditEntry(businessId, null, user.getId(), AuditAction.EMPLOYEE_REACTIVATED, USER_ENTITY, user.getId().toString(),
                Map.of("isActive", false), Map.of("isActive", true), DEACTIVATION_REASON));
    }

    /** The audited fields of a person that the sync changes (the active state has its own entries). */
    private static Map<String, Object> profileOf(User user) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeCode", user.getEmployeeCode());
        details.put("firstName", user.getFirstName());
        details.put("lastName", user.getLastName());
        details.put("email", user.getEmail());
        details.put("role", user.getRole().name());
        details.put("managerId", user.getManagerId() == null ? null : user.getManagerId().toString());
        return details;
    }

    private static Map<String, Object> provisionedDetails(User user, Candidate candidate) {
        Map<String, Object> details = profileOf(user);
        details.put("jurisoneEmployeeId", candidate.jurisoneEmployeeId.toString());
        details.put("isActive", user.isActive());
        return details;
    }

    // ── audit ──

    private void recordRosterSynced(UUID businessId, RosterSyncResult result, int receivedEmployees, int receivedShifts) {
        Map<String, Integer> skippedByReason = new TreeMap<>();
        for (SkippedEmployee skippedEmployee : result.skipped()) skippedByReason.merge(skippedEmployee.reason(), 1, Integer::sum);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("receivedShifts", receivedShifts);
        details.put("receivedEmployees", receivedEmployees);
        details.put("shifts", result.shifts());
        details.put("employees", result.employees());
        details.put("scheduleEntries", result.scheduleEntries());
        details.put("skippedEmployees", result.skipped().size());
        details.put("skippedEmployeesByReason", skippedByReason);
        details.put("skippedShifts", result.skippedShifts().size());
        auditService.record(new AuditEntry(businessId, null, null, AuditAction.ROSTER_SYNCED, BUSINESS_ENTITY, businessId.toString(), null, details, null));
    }
}

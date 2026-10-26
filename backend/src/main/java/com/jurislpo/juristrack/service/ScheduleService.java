package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.EmployeeSchedule;
import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.engine.ShiftSummary;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.ScheduleEntry;
import com.jurislpo.juristrack.entity.Shift;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.repository.ScheduleEntryRepository;
import com.jurislpo.juristrack.repository.TeamQueryRepository;
import com.jurislpo.juristrack.repository.TeamQueryRepository.TeamMemberEmployee;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The roster: for every employee and shift date either a shift or a week off (the weekly off is rotational, so it is stored per date).
 * The schedule of a date is the roster entry if there is one, else the employee's assigned shift, else the default shift
 * ({@link EmployeeSchedule}). The owner writes; a manager reads the schedule of their reports (direct and indirect); an employee reads
 * their own. A date that already has a workday cannot change (422 {@code SCHEDULE_DATE_HAS_WORKDAY}); a date more than 365 days ahead
 * is refused (422 {@code SCHEDULE_DATE_TOO_FAR}). Every changed date writes its own audit entry; a write that changes nothing writes none.
 */
@Service
public class ScheduleService {

    public static final int MAXIMUM_DAYS = 62;
    public static final int MAXIMUM_DAYS_AHEAD = 365;
    private static final int DEFAULT_DAYS = 7;

    /** One date of someone's schedule; {@code source} is ENTRY, ASSIGNMENT or DEFAULT. */
    public record ScheduleDayView(LocalDate date, UUID shiftId, String shiftName, @JsonProperty("isOff") boolean isOff, String source,
            String reason) {}

    /** One person's schedule for the range; {@code shift} is the shift they are assigned now. */
    public record ScheduleRow(UUID employeeId, String name, String employeeCode, ShiftSummary shift, List<ScheduleDayView> days) {}

    /** {@code GET /api/schedule-entries}: one page of people with their schedule for {@code from} to {@code to}. */
    public record ScheduleBoard(List<ScheduleRow> rows, long total, int page, int pageSize, LocalDate from, LocalDate to) {}

    /** One entry of {@code PUT /api/employees/{employeeId}/schedule-entries}; {@code shiftId} null = a week off. */
    public record ScheduleEntryInput(String date, UUID shiftId, String reason) {}

    /** {@code PUT /api/employees/{employeeId}/schedule-entries/{date}} body. */
    public record ScheduleEntryBody(UUID shiftId, String reason) {}

    /** {@code PUT /api/employees/{employeeId}/schedule-entries} body. */
    public record ScheduleEntriesBody(List<ScheduleEntryInput> entries) {}

    public static final List<String> SORT_KEYS = List.of("name");
    private static final String ENTRY_ENTITY = "schedule_entry";
    private static final String USER_ENTITY = "user";
    private static final String NOT_FOUND = "Employee not found.";

    private final Clock clock;
    private final BusinessRepository businessRepository;
    private final EmployeeLockRepository employeeLockRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;
    private final WorkdayRepository workdayRepository;
    private final ShiftResolver shiftResolver;
    private final ShiftService shiftService;
    private final TeamQueryRepository teamQueryRepository;
    private final ReportingScopeService reportingScopeService;
    private final AuditService auditService;

    public ScheduleService(Clock clock, BusinessRepository businessRepository, EmployeeLockRepository employeeLockRepository,
            ScheduleEntryRepository scheduleEntryRepository, WorkdayRepository workdayRepository, ShiftResolver shiftResolver,
            ShiftService shiftService, TeamQueryRepository teamQueryRepository, ReportingScopeService reportingScopeService,
            AuditService auditService) {
        this.clock = clock;
        this.businessRepository = businessRepository;
        this.employeeLockRepository = employeeLockRepository;
        this.scheduleEntryRepository = scheduleEntryRepository;
        this.workdayRepository = workdayRepository;
        this.shiftResolver = shiftResolver;
        this.shiftService = shiftService;
        this.teamQueryRepository = teamQueryRepository;
        this.reportingScopeService = reportingScopeService;
        this.auditService = auditService;
    }

    /**
     * The schedule of the people the caller may see: the owner everybody of the business, a manager their direct and indirect reports.
     * Query: {@code from}, {@code to} (dates, at most 62 days; default today and the 6 days after), {@code employeeId}, {@code managerId}
     * (their direct reports), {@code search}, {@code page}, {@code pageSize}.
     */
    @Transactional(readOnly = true)
    public ScheduleBoard listSchedules(CurrentUser viewer, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(queryParameters, SORT_KEYS, "name", validator);
        Business business = loadBusiness(viewer.businessId());
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        LocalDate[] range = readRange(queryParameters, clock.instant(), zone, validator);
        UUID employeeId = optionalId(queryParameters, "employeeId", validator);
        UUID managerId = optionalId(queryParameters, "managerId", validator);
        validator.throwIfInvalid();

        List<TeamMemberEmployee> members = viewer.role() == Role.ADMIN
                ? teamQueryRepository.findBoardMembersOfBusiness(viewer.businessId(), viewer.userId())
                : teamQueryRepository.findBoardMembers(viewer.businessId(),
                        reportingScopeService.visibleEmployeeIds(viewer.businessId(), viewer.userId(), ReportingLine.WITH_INDIRECT_REPORTS));
        String searchText = listQuery.search().toLowerCase(Locale.ROOT);
        List<TeamMemberEmployee> matching = members.stream().filter(member -> member.isActive())
                .filter(member -> employeeId == null || member.id().equals(employeeId))
                .filter(member -> managerId == null || managerId.equals(member.managerId()))
                .filter(member -> searchText.isEmpty() || (member.firstName() + " " + member.lastName() + " " + member.email() + " "
                        + (member.employeeCode() == null ? "" : member.employeeCode())).toLowerCase(Locale.ROOT).contains(searchText))
                .sorted(Comparator.comparing((TeamMemberEmployee member) -> member.firstName().toLowerCase(Locale.ROOT))
                        .thenComparing(member -> member.lastName().toLowerCase(Locale.ROOT)).thenComparing(TeamMemberEmployee::id))
                .toList();
        int fromIndex = (int) Math.min(listQuery.offset(), matching.size());
        int toIndex = Math.min(fromIndex + listQuery.pageSize(), matching.size());
        List<TeamMemberEmployee> page = matching.subList(fromIndex, toIndex);
        Map<UUID, EmployeeSchedule> schedules = shiftResolver.schedulesOf(viewer.businessId(), page.stream().map(TeamMemberEmployee::id).toList(),
                range[0], range[1]);
        LocalDate today = BusinessCalendar.toDateKey(clock.instant(), zone);
        List<ScheduleRow> rows = page.stream().map(member -> rowOf(member.id(), member.firstName() + " " + member.lastName(), member.employeeCode(),
                schedules.get(member.id()), range[0], range[1], today, Map.of())).toList();
        return new ScheduleBoard(rows, matching.size(), listQuery.page(), listQuery.pageSize(), range[0], range[1]);
    }

    /** The caller's own schedule ({@code from}, {@code to} as above). */
    @Transactional(readOnly = true)
    public ScheduleRow findOwnSchedule(CurrentUser user, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        Business business = loadBusiness(user.businessId());
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        LocalDate[] range = readRange(queryParameters, clock.instant(), zone, validator);
        validator.throwIfInvalid();
        Map<UUID, EmployeeSchedule> schedules = shiftResolver.schedulesOf(user.businessId(), List.of(user.userId()), range[0], range[1]);
        LocalDate today = BusinessCalendar.toDateKey(clock.instant(), zone);
        Map<LocalDate, String> reasons = reasonsOf(List.of(user.userId()), range[0], range[1]);
        return rowOf(user.userId(), user.firstName() + " " + user.lastName(), null, schedules.get(user.userId()), range[0], range[1], today, reasons);
    }

    /** Sets one date of the roster ({@code body.shiftId} null = a week off); idempotent. Answers the date as now scheduled. */
    @Transactional
    public ScheduleDayView setEntry(CurrentUser owner, UUID employeeId, String dateText, ScheduleEntryBody body) {
        InputValidator validator = new InputValidator();
        LocalDate date = readDate(validator, "date", dateText);
        String reason = validator.optionalText("reason", "Reason", body == null ? null : body.reason(), InputValidator.MAXIMUM_REASON_LENGTH);
        validator.throwIfInvalid();
        Business business = lockAndLoad(owner, employeeId);
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        applyEntry(owner, employeeId, date, body == null ? null : body.shiftId(), reason, zone);
        return dayOf(owner.businessId(), employeeId, date);
    }

    /** Removes one date's roster entry: the date follows the assigned shift again. Answers the date as now scheduled. */
    @Transactional
    public ScheduleDayView removeEntry(CurrentUser owner, UUID employeeId, String dateText) {
        InputValidator validator = new InputValidator();
        LocalDate date = readDate(validator, "date", dateText);
        validator.throwIfInvalid();
        Business business = lockAndLoad(owner, employeeId);
        Optional<ScheduleEntry> entry = scheduleEntryRepository.findByEmployeeIdAndEntryDate(employeeId, date);
        if (entry.isPresent()) {
            requireNoWorkday(employeeId, date);
            Map<String, Object> before = entryDetails(entry.get());
            scheduleEntryRepository.delete(entry.get());
            scheduleEntryRepository.flush();
            auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId, AuditAction.SCHEDULE_ENTRY_REMOVED, ENTRY_ENTITY,
                    employeeId + "/" + date, before, null, null));
        }
        return dayOf(owner.businessId(), employeeId, date);
    }

    /** Sets up to 62 dates at once (all or none: one bad entry refuses the call); one audit entry per date that changed. */
    @Transactional
    public List<ScheduleDayView> setEntries(CurrentUser owner, UUID employeeId, ScheduleEntriesBody body) {
        InputValidator validator = new InputValidator();
        List<ScheduleEntryInput> entries = body == null || body.entries() == null ? List.of() : body.entries();
        if (entries.isEmpty()) validator.reject("entries", "Send at least one entry.");
        if (entries.size() > MAXIMUM_DAYS) validator.reject("entries", "Send at most " + MAXIMUM_DAYS + " entries at a time.");
        List<LocalDate> dates = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        List<String> reasons = new ArrayList<>();
        for (ScheduleEntryInput entry : entries) {
            LocalDate date = entry == null ? null : readDate(validator, "entries.date", entry.date());
            if (date != null && !seen.add(date)) validator.reject("entries.date", "A date can only be listed once.");
            dates.add(date);
            reasons.add(validator.optionalText("entries.reason", "Reason", entry == null ? null : entry.reason(), InputValidator.MAXIMUM_REASON_LENGTH));
        }
        validator.throwIfInvalid();
        Business business = lockAndLoad(owner, employeeId);
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        List<ScheduleDayView> days = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            applyEntry(owner, employeeId, dates.get(i), entries.get(i).shiftId(), reasons.get(i), zone);
            days.add(dayOf(owner.businessId(), employeeId, dates.get(i)));
        }
        return days;
    }

    private void applyEntry(CurrentUser owner, UUID employeeId, LocalDate date, UUID shiftId, String reason, ZoneId zone) {
        LocalDate today = BusinessCalendar.toDateKey(clock.instant(), zone);
        if (date.isAfter(today.plusDays(MAXIMUM_DAYS_AHEAD))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SCHEDULE_DATE_TOO_FAR,
                    "A schedule can be set at most " + MAXIMUM_DAYS_AHEAD + " days ahead.");
        }
        Shift shift = shiftId == null ? null : shiftService.requireActiveShift(owner.businessId(), shiftId);
        Optional<ScheduleEntry> existing = scheduleEntryRepository.findByEmployeeIdAndEntryDate(employeeId, date);
        Map<String, Object> before = existing.map(ScheduleService::entryDetails).orElse(null);
        boolean isUnchanged = existing.isPresent() && java.util.Objects.equals(existing.get().getShiftId(), shiftId)
                && java.util.Objects.equals(existing.get().getReason(), reason);
        if (isUnchanged) return;
        requireNoWorkday(employeeId, date);
        ScheduleEntry entry = existing.orElseGet(ScheduleEntry::new);
        entry.setBusinessId(owner.businessId());
        entry.setEmployeeId(employeeId);
        entry.setEntryDate(date);
        entry.setShiftId(shiftId);
        entry.setReason(reason);
        entry.setCreatedBy(owner.userId());
        scheduleEntryRepository.saveAndFlush(entry);
        Map<String, Object> after = entryDetails(entry);
        after.put("shiftName", shift == null ? null : shift.getName());
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId, AuditAction.SCHEDULE_ENTRY_SET, ENTRY_ENTITY,
                employeeId + "/" + date, before, after, reason));
    }

    /** What JurisOne's roster sync got for one date of one employee's roster. */
    public enum RosterEntryOutcome { SET, UNCHANGED, DATE_HAS_WORKDAY, DATE_TOO_FAR }

    /**
     * Sets one date of the roster for JurisOne's roster sync ({@code shift} null = a week off; the caller has checked that the shift is an
     * active one of the business). Like {@link #setEntry} but it never throws a business-rule refusal (it answers
     * {@link RosterEntryOutcome#DATE_HAS_WORKDAY} or {@link RosterEntryOutcome#DATE_TOO_FAR}, so one date cannot fail the batch), has no
     * signed-in user (the audit entry {@code schedule.entry_set} has no actor) and keeps the reason an owner wrote on the entry.
     * The same shift on a date again changes nothing and writes nothing.
     */
    RosterEntryOutcome applyRosterEntry(UUID businessId, UUID employeeId, LocalDate date, Shift shift, LocalDate today) {
        if (date.isAfter(today.plusDays(MAXIMUM_DAYS_AHEAD))) return RosterEntryOutcome.DATE_TOO_FAR;
        UUID shiftId = shift == null ? null : shift.getId();
        Optional<ScheduleEntry> existing = scheduleEntryRepository.findByEmployeeIdAndEntryDate(employeeId, date);
        if (existing.isPresent() && java.util.Objects.equals(existing.get().getShiftId(), shiftId)) return RosterEntryOutcome.UNCHANGED;
        if (workdayRepository.findByEmployeeIdAndWorkDate(employeeId, date).isPresent()) return RosterEntryOutcome.DATE_HAS_WORKDAY;
        Map<String, Object> before = existing.map(ScheduleService::entryDetails).orElse(null);
        ScheduleEntry entry = existing.orElseGet(ScheduleEntry::new);
        entry.setBusinessId(businessId);
        entry.setEmployeeId(employeeId);
        entry.setEntryDate(date);
        entry.setShiftId(shiftId);
        scheduleEntryRepository.saveAndFlush(entry);
        Map<String, Object> after = entryDetails(entry);
        after.put("shiftName", shift == null ? null : shift.getName());
        auditService.record(new AuditEntry(businessId, null, employeeId, AuditAction.SCHEDULE_ENTRY_SET, ENTRY_ENTITY, employeeId + "/" + date, before,
                after, entry.getReason()));
        return RosterEntryOutcome.SET;
    }

    private void requireNoWorkday(UUID employeeId, LocalDate date) {
        if (workdayRepository.findByEmployeeIdAndWorkDate(employeeId, date).isPresent()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SCHEDULE_DATE_HAS_WORKDAY,
                    "This person already has a workday on " + date + ", so its schedule can no longer change.");
        }
    }

    /** Locks the employee's row (a roster change waits for a concurrent time event) and checks they belong to the owner's business. */
    private Business lockAndLoad(CurrentUser owner, UUID employeeId) {
        TeamMemberEmployee employee = teamQueryRepository.findMember(owner.businessId(), employeeId)
                .orElseThrow(() -> new OutOfScopeException(USER_ENTITY, NOT_FOUND));
        employeeLockRepository.lockEmployee(employee.id()).orElseThrow(() -> new OutOfScopeException(USER_ENTITY, NOT_FOUND));
        return loadBusiness(owner.businessId());
    }

    private ScheduleDayView dayOf(UUID businessId, UUID employeeId, LocalDate date) {
        EmployeeSchedule schedule = shiftResolver.schedulesOf(businessId, List.of(employeeId), date, date).get(employeeId);
        return dayView(schedule.dayOn(date), reasonsOf(List.of(employeeId), date, date).get(date));
    }

    private Map<LocalDate, String> reasonsOf(List<UUID> employeeIds, LocalDate from, LocalDate to) {
        Map<LocalDate, String> reasons = new LinkedHashMap<>();
        for (ScheduleEntry entry : scheduleEntryRepository.findByEmployeeIdInAndEntryDateBetween(employeeIds, from, to)) {
            if (entry.getReason() != null) reasons.put(entry.getEntryDate(), entry.getReason());
        }
        return reasons;
    }

    private static ScheduleRow rowOf(UUID employeeId, String name, String employeeCode, EmployeeSchedule schedule, LocalDate from, LocalDate to,
            LocalDate today, Map<LocalDate, String> reasons) {
        List<ScheduleDayView> days = new ArrayList<>();
        for (LocalDate date : BusinessCalendar.eachDay(from, to)) days.add(dayView(schedule.dayOn(date), reasons.get(date)));
        return new ScheduleRow(employeeId, name.trim(), employeeCode, ShiftSummary.of(schedule.assignedOn(today)), days);
    }

    private static ScheduleDayView dayView(EmployeeSchedule.Day day, String reason) {
        return new ScheduleDayView(day.date(), day.shift() == null ? null : day.shift().id(), day.shift() == null ? null : day.shift().name(),
                day.isOff(), day.source().name(), reason);
    }

    private static Map<String, Object> entryDetails(ScheduleEntry entry) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", entry.getEntryDate().toString());
        details.put("shiftId", entry.getShiftId() == null ? null : entry.getShiftId().toString());
        details.put("isOff", entry.getShiftId() == null);
        details.put("reason", entry.getReason());
        return details;
    }

    /** {@code from} / {@code to} (default today and the 6 days after); at most 62 days. */
    private static LocalDate[] readRange(Map<String, String> queryParameters, java.time.Instant now, ZoneId zone, InputValidator validator) {
        LocalDate today = BusinessCalendar.toDateKey(now, zone);
        LocalDate from = readOptionalDate(queryParameters, "from", validator);
        LocalDate to = readOptionalDate(queryParameters, "to", validator);
        if (from == null) from = to != null ? to.minusDays(DEFAULT_DAYS - 1) : today;
        if (to == null) to = from.plusDays(DEFAULT_DAYS - 1);
        if (to.isBefore(from)) validator.reject("to", "The end date cannot be before the start date.");
        else if (BusinessCalendar.daysBetween(from, to) + 1 > MAXIMUM_DAYS) validator.reject("to", "At most " + MAXIMUM_DAYS + " days at a time.");
        return new LocalDate[] {from, to};
    }

    private static LocalDate readOptionalDate(Map<String, String> queryParameters, String field, InputValidator validator) {
        String text = queryParameters.getOrDefault(field, "").trim();
        return text.isEmpty() ? null : readDate(validator, field, text);
    }

    private static LocalDate readDate(InputValidator validator, String field, String text) {
        Optional<LocalDate> date = text == null ? Optional.empty() : BusinessCalendar.parseDateKey(text.trim());
        if (date.isEmpty()) validator.reject(field, "The date is a date (YYYY-MM-DD).");
        return date.orElse(null);
    }

    private static UUID optionalId(Map<String, String> queryParameters, String field, InputValidator validator) {
        String text = queryParameters.getOrDefault(field, "").trim();
        if (text.isEmpty()) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            validator.reject(field, "The " + field + " is not valid.");
            return null;
        }
    }

    private Business loadBusiness(UUID businessId) {
        return businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
    }
}

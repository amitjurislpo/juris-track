package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.LastActivity;
import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.engine.ShiftAttribution;
import com.jurislpo.juristrack.engine.ShiftOccurrence;
import com.jurislpo.juristrack.engine.TrackingStatus;
import com.jurislpo.juristrack.engine.WorkdayShift;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.Workday;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.TeamQueryRepository;
import com.jurislpo.juristrack.repository.TeamQueryRepository.TeamMemberEmployee;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.WorkdayHistoryService.WorkdayHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's Team tab (INTEGRATION.md section 3b): the live board of the people in the caller's scope, one person's live state
 * and one person's history. Ported from the old app's {@code board.service.ts} with the reporting line in place of teams:
 * <ul>
 *   <li>Scope: a MANAGER sees their direct reports ({@code scope=direct}, the default) or also the next level
 *       ({@code scope=indirect}); the owner (ADMIN) sees everybody of the business ({@code scope=all}, the default) or their own
 *       reporting line. The caller is never in their own list.</li>
 *   <li>Each person's state is today's in the business time zone: the open workday whatever its date (a night shift from yesterday
 *       is WORKING), else the workday of the shift occurrence the person is in now (a night shift that ended after midnight is
 *       DAY_ENDED with its own date until its attribution window is over; its hours count in the tiles), else NOT_STARTED (with the
 *       shift of that occurrence). Deactivated people are left out unless a workday is still open.</li>
 *   <li>Everything is read in batches (people, workdays, work sessions, break sessions: one query each), never one query per
 *       person.</li>
 * </ul>
 * Opening one person (detail or history) is limited by {@link TeamAccessPolicy} and audited as access ({@link TeamAccessAudit}).
 */
@Service
public class TeamService {

    /**
     * One person on the live board with today's state; the time fields are those of the personal snapshot
     * ({@code GET /api/me/time}), so the client runs the same live timer. Without a workday every number is 0 and every time null.
     *
     * @param employee  who it is
     * @param status    NOT_STARTED, WORKING, ON_BREAK or DAY_ENDED
     * @param workdayId the workday shown (open, dated today, or ended today), or null
     * @param date      that workday's date (a night shift keeps the date it started on), or null
     * @param isStale   open longer than the business's stale workday hours ("not ended")
     * @param shift     the workday's shift; without a workday the shift occurrence the person is in now
     * @param varianceSeconds productive minus scheduled time once the workday has ended, else null
     * @param isOffDay  the shift date is a week off in the roster (with a workday: it was worked on the week off); a person with a week
     *                  off and no workday is NOT_STARTED here but counted in {@code metrics.weekOff}, not in {@code metrics.notStarted}
     * @param extraSeconds productive time outside the scheduled window (included in productiveSeconds)
     */
    public record TeamMemberRow(TeamMemberEmployee employee, TrackingStatus status, UUID workdayId, LocalDate date, Instant startedAt,
            Instant endedAt, @JsonProperty("isStale") boolean isStale, long closedProductiveSeconds, long closedBreakSeconds,
            Instant activeWorkStartedAt, Instant activeBreakStartedAt, long productiveSeconds, long breakSeconds, long spanSeconds,
            int breakCount, LastActivity lastActivity, WorkdayShift shift, @JsonProperty("isOutsideShift") boolean isOutsideShift,
            Long varianceSeconds, @JsonProperty("isOffDay") boolean isOffDay, long extraSeconds) {}

    /**
     * Today's figures over everybody in the chosen scope (independent of {@code search}, {@code status} and paging).
     *
     * @param weekOff                  people whose roster has a week off for their current shift date and who have no workday (not in
     *                                 {@code notStarted})
     * @param totalExtraSeconds        the extra time (outside the scheduled windows) inside {@code totalProductiveSeconds}
     * @param averageProductiveSeconds {@code totalProductiveSeconds} divided by the people who started today (working, on a break or
     *                                 day ended), rounded down; 0 when nobody started
     */
    public record TeamMetrics(int total, int working, int onBreak, int notStarted, int weekOff, int dayEnded, int stale,
            long totalProductiveSeconds, long totalBreakSeconds, long totalExtraSeconds, long averageProductiveSeconds) {}

    /** {@code GET /api/team/members}: one page of the board plus what the client needs to run the live timers. */
    public record TeamBoard(List<TeamMemberRow> rows, long total, int page, int pageSize, String scope, Instant serverTime, LocalDate today,
            String businessTimeZone, int staleWorkdayHours, TeamMetrics metrics) {}

    /** {@code GET /api/team/members/{employeeId}}: the person and their time state as {@code GET /api/me/time} shows one's own. */
    public record TeamMemberDetail(TeamMemberEmployee employee, Instant serverTime, LocalDate today, String businessTimeZone,
            int staleWorkdayHours, TrackingStatus status, LocalDate shiftDate, @JsonProperty("isOffDay") boolean isOffDay, WorkdayShift shift,
            WorkdaySnapshot workday) {}

    /** The sort keys of {@code GET /api/team/members}. */
    public static final List<String> SORT_KEYS = List.of("name", "status", "productiveSeconds", "lastActivity");
    private static final String SCOPE_DIRECT = "direct";
    private static final String SCOPE_INDIRECT = "indirect";
    private static final String SCOPE_ALL = "all";
    private static final String STATUS_ALL = "all";
    /** The order of {@code sort=status} ascending: who is at work first. */
    private static final List<TrackingStatus> STATUS_ORDER = List.of(TrackingStatus.WORKING, TrackingStatus.ON_BREAK,
            TrackingStatus.NOT_STARTED, TrackingStatus.DAY_ENDED);
    private static final Comparator<TeamMemberRow> BY_NAME = Comparator
            .comparing((TeamMemberRow row) -> row.employee().firstName().toLowerCase(Locale.ROOT))
            .thenComparing(row -> row.employee().lastName().toLowerCase(Locale.ROOT));
    private static final Map<String, Comparator<TeamMemberRow>> SORT_ORDERS = Map.of(
            "name", BY_NAME,
            "status", Comparator.comparingInt(row -> STATUS_ORDER.indexOf(row.status())),
            "productiveSeconds", Comparator.comparingLong(TeamMemberRow::productiveSeconds),
            "lastActivity", Comparator.comparing(row -> row.lastActivity() == null ? null : row.lastActivity().at(),
                    Comparator.nullsFirst(Comparator.naturalOrder())));
    private static final String EMPLOYEE_NOT_FOUND = "Employee not found.";

    private final Clock clock;
    private final TeamQueryRepository teamQueryRepository;
    private final WorkdayRepository workdayRepository;
    private final ShiftResolver shiftResolver;
    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;
    private final BusinessRepository businessRepository;
    private final ReportingScopeService reportingScopeService;
    private final TeamAccessPolicy teamAccessPolicy;
    private final TeamAccessAudit teamAccessAudit;
    private final WorkdaySnapshots workdaySnapshots;
    private final WorkdayHistoryService workdayHistoryService;

    public TeamService(Clock clock, TeamQueryRepository teamQueryRepository, WorkdayRepository workdayRepository,
            ShiftResolver shiftResolver, WorkSessionRepository workSessionRepository, BreakSessionRepository breakSessionRepository, BusinessRepository businessRepository,
            ReportingScopeService reportingScopeService, TeamAccessPolicy teamAccessPolicy, TeamAccessAudit teamAccessAudit,
            WorkdaySnapshots workdaySnapshots, WorkdayHistoryService workdayHistoryService) {
        this.clock = clock;
        this.teamQueryRepository = teamQueryRepository;
        this.workdayRepository = workdayRepository;
        this.shiftResolver = shiftResolver;
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
        this.businessRepository = businessRepository;
        this.reportingScopeService = reportingScopeService;
        this.teamAccessPolicy = teamAccessPolicy;
        this.teamAccessAudit = teamAccessAudit;
        this.workdaySnapshots = workdaySnapshots;
        this.workdayHistoryService = workdayHistoryService;
    }

    /**
     * The live board of the caller (ADMIN or MANAGER): {@code scope} ({@code direct} | {@code indirect}, and {@code all} for the
     * owner; default {@code direct} for a manager, {@code all} for the owner), {@code status} (WORKING, ON_BREAK, NOT_STARTED,
     * DAY_ENDED or {@code all}, any case), {@code search} (name, email, employee code), {@code sort} ({@link #SORT_KEYS}, default
     * {@code name}), {@code sortDirection}, {@code page}, {@code pageSize}. 400 for an unknown value.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TeamBoard listMembers(CurrentUser viewer, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(queryParameters, SORT_KEYS, "name", validator);
        String scope = readScope(viewer, queryParameters.get("scope"), validator);
        Optional<TrackingStatus> statusFilter = readStatusFilter(queryParameters.get("status"), validator);
        validator.throwIfInvalid();

        Business business = loadBusiness(viewer.businessId());
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.toDateKey(now, BusinessCalendar.zoneOf(business.getTimeZone()));
        List<TeamMemberEmployee> members = loadBoardMembers(viewer, scope);
        List<TeamMemberRow> rows = rowsOf(viewer.businessId(), members, BusinessCalendar.zoneOf(business.getTimeZone()), now, business.getStaleWorkdayHours());
        TeamMetrics metrics = metricsOf(rows);

        String searchText = listQuery.search().toLowerCase(Locale.ROOT);
        Comparator<TeamMemberRow> order = SORT_ORDERS.get(listQuery.sort());
        if (!listQuery.isAscending()) order = order.reversed();
        order = order.thenComparing(BY_NAME).thenComparing(row -> row.employee().id());
        List<TeamMemberRow> matchingRows = rows.stream()
                .filter(row -> statusFilter.isEmpty() || row.status() == statusFilter.get())
                .filter(row -> searchText.isEmpty() || matchesSearch(row.employee(), searchText))
                .sorted(order).toList();
        int fromIndex = (int) Math.min(listQuery.offset(), matchingRows.size());
        int toIndex = Math.min(fromIndex + listQuery.pageSize(), matchingRows.size());
        return new TeamBoard(matchingRows.subList(fromIndex, toIndex), matchingRows.size(), listQuery.page(), listQuery.pageSize(), scope, now,
                today, business.getTimeZone(), business.getStaleWorkdayHours(), metrics);
    }

    /** One person of the caller's scope with their current time state; 404 outside the scope. Audited as access. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TeamMemberDetail findMember(CurrentUser viewer, UUID employeeId) {
        TeamMemberEmployee employee = requireVisibleMember(viewer, employeeId);
        Business business = loadBusiness(viewer.businessId());
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.toDateKey(now, BusinessCalendar.zoneOf(business.getTimeZone()));
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        ShiftAttribution attribution = shiftResolver.attributionOf(viewer.businessId(), employeeId, now, zone);
        ShiftOccurrence occurrence = attribution.occurrence();
        Optional<Workday> workday = workdayRepository.findByActiveEmployeeId(employeeId)
                .or(() -> workdayRepository.findByEmployeeIdAndWorkDate(employeeId, occurrence.date()));
        WorkdaySnapshot snapshot = workday.map(day -> workdaySnapshots.snapshotOf(day, now, business.getStaleWorkdayHours())).orElse(null);
        teamAccessAudit.recordMemberViewed(viewer, employeeId);
        return new TeamMemberDetail(employee, now, today, business.getTimeZone(), business.getStaleWorkdayHours(),
                snapshot == null ? TrackingStatus.NOT_STARTED : snapshot.status(), occurrence.date(), attribution.isOffDay(), WorkdayShift.of(occurrence, zone), snapshot);
    }

    /** One person's history, as {@code GET /api/workdays/mine} answers one's own; 404 outside the scope. Audited as access. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WorkdayHistory listMemberWorkdays(CurrentUser viewer, UUID employeeId, Map<String, String> queryParameters) {
        requireVisibleMember(viewer, employeeId);
        WorkdayHistory history = workdayHistoryService.listWorkdays(employeeId, viewer.businessId(), queryParameters);
        teamAccessAudit.recordMemberWorkdaysViewed(viewer, employeeId, history);
        return history;
    }

    private TeamMemberEmployee requireVisibleMember(CurrentUser viewer, UUID employeeId) {
        return teamQueryRepository.findMember(viewer.businessId(), employeeId)
                .filter(employee -> teamAccessPolicy.canView(viewer, employee.id()))
                .orElseThrow(() -> new OutOfScopeException("user", EMPLOYEE_NOT_FOUND));
    }

    private static String readScope(CurrentUser viewer, String rawScope, InputValidator validator) {
        boolean isOwner = viewer.role() == Role.ADMIN;
        String scope = rawScope == null || rawScope.isBlank() ? (isOwner ? SCOPE_ALL : SCOPE_DIRECT) : rawScope.trim().toLowerCase(Locale.ROOT);
        boolean isAllowed = scope.equals(SCOPE_DIRECT) || scope.equals(SCOPE_INDIRECT) || (isOwner && scope.equals(SCOPE_ALL));
        if (!isAllowed) validator.reject("scope", isOwner ? "The scope is all, direct or indirect." : "The scope is direct or indirect.");
        return scope;
    }

    private static Optional<TrackingStatus> readStatusFilter(String rawStatus, InputValidator validator) {
        if (rawStatus == null || rawStatus.isBlank() || rawStatus.trim().equalsIgnoreCase(STATUS_ALL)) return Optional.empty();
        String status = rawStatus.trim().toUpperCase(Locale.ROOT);
        for (TrackingStatus trackingStatus : TrackingStatus.values()) {
            if (trackingStatus.name().equals(status)) return Optional.of(trackingStatus);
        }
        validator.reject("status", "The status is WORKING, ON_BREAK, NOT_STARTED, DAY_ENDED or all.");
        return Optional.empty();
    }

    private List<TeamMemberEmployee> loadBoardMembers(CurrentUser viewer, String scope) {
        if (scope.equals(SCOPE_ALL)) return teamQueryRepository.findBoardMembersOfBusiness(viewer.businessId(), viewer.userId());
        int depth = scope.equals(SCOPE_INDIRECT) ? ReportingLine.WITH_INDIRECT_REPORTS : ReportingLine.DIRECT_REPORTS;
        Set<UUID> employeeIds = reportingScopeService.visibleEmployeeIds(viewer.businessId(), viewer.userId(), depth);
        return teamQueryRepository.findBoardMembers(viewer.businessId(), employeeIds);
    }

    /**
     * The current state of every member: each person's current workday (the open one, else the workday of the shift occurrence they
     * are in now; see {@link ShiftResolver}) and those workdays' intervals are read in one query per table.
     */
    private List<TeamMemberRow> rowsOf(UUID businessId, List<TeamMemberEmployee> members, ZoneId zone, Instant now, int staleWorkdayHours) {
        if (members.isEmpty()) return List.of();
        List<UUID> employeeIds = members.stream().map(TeamMemberEmployee::id).toList();
        Map<UUID, ShiftAttribution> attributionByEmployeeId = shiftResolver.attributionsOf(businessId, employeeIds, now, zone);
        LocalDate today = BusinessCalendar.toDateKey(now, zone);
        Map<UUID, Workday> workdayByEmployeeId = new HashMap<>();
        for (Workday workday : workdayRepository.findOpenOrDatedBetweenByEmployeeIds(employeeIds, today.minusDays(3), today.plusDays(2))) {
            UUID employeeId = workday.getEmployeeId();
            boolean isOpen = workday.getActiveEmployeeId() != null;
            boolean isOfCurrentOccurrence = workday.getWorkDate().equals(attributionByEmployeeId.get(employeeId).occurrence().date());
            Workday shown = workdayByEmployeeId.get(employeeId);
            if (isOpen || (isOfCurrentOccurrence && shown == null)) workdayByEmployeeId.put(employeeId, workday);
        }
        List<UUID> workdayIds = workdayByEmployeeId.values().stream().map(Workday::getId).toList();
        Map<UUID, List<WorkSession>> workSessionsByWorkdayId = workdayIds.isEmpty() ? Map.of()
                : workSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(workdayIds).stream()
                        .collect(Collectors.groupingBy(WorkSession::getWorkdayId));
        Map<UUID, List<BreakSession>> breakSessionsByWorkdayId = workdayIds.isEmpty() ? Map.of()
                : breakSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(workdayIds).stream()
                        .collect(Collectors.groupingBy(BreakSession::getWorkdayId));
        return members.stream().map(employee -> {
            Workday workday = workdayByEmployeeId.get(employee.id());
            if (workday == null) {
                return new TeamMemberRow(employee, TrackingStatus.NOT_STARTED, null, null, null, null, false, 0, 0, null, null, 0, 0, 0, 0, null,
                        WorkdayShift.of(attributionByEmployeeId.get(employee.id()).occurrence(), zone), false, null,
                        attributionByEmployeeId.get(employee.id()).isOffDay(), 0);
            }
            WorkdaySnapshot snapshot = WorkdaySnapshots.snapshotOf(workday, workSessionsByWorkdayId.getOrDefault(workday.getId(), List.of()),
                    breakSessionsByWorkdayId.getOrDefault(workday.getId(), List.of()), now, staleWorkdayHours);
            return new TeamMemberRow(employee, snapshot.status(), snapshot.id(), snapshot.date(), snapshot.startedAt(), snapshot.endedAt(),
                    snapshot.isStale(), snapshot.closedProductiveSeconds(), snapshot.closedBreakSeconds(), snapshot.activeWorkStartedAt(),
                    snapshot.activeBreakStartedAt(), snapshot.productiveSeconds(), snapshot.breakSeconds(), snapshot.spanSeconds(),
                    snapshot.breakCount(), snapshot.lastActivity(), snapshot.shift(), snapshot.isOutsideShift(), snapshot.varianceSeconds(),
                    snapshot.isOffDay(), snapshot.extraSeconds());
        }).toList();
    }

    private static TeamMetrics metricsOf(List<TeamMemberRow> rows) {
        Map<TrackingStatus, Long> countByStatus = rows.stream().collect(Collectors.groupingBy(TeamMemberRow::status, Collectors.counting()));
        Function<TrackingStatus, Integer> countOf = status -> countByStatus.getOrDefault(status, 0L).intValue();
        long totalProductiveSeconds = rows.stream().mapToLong(TeamMemberRow::productiveSeconds).sum();
        long totalBreakSeconds = rows.stream().mapToLong(TeamMemberRow::breakSeconds).sum();
        int weekOff = (int) rows.stream().filter(row -> row.status() == TrackingStatus.NOT_STARTED && row.isOffDay()).count();
        int notStarted = countOf.apply(TrackingStatus.NOT_STARTED) - weekOff;
        int startedCount = rows.size() - countOf.apply(TrackingStatus.NOT_STARTED);
        return new TeamMetrics(rows.size(), countOf.apply(TrackingStatus.WORKING), countOf.apply(TrackingStatus.ON_BREAK),
                notStarted, weekOff, countOf.apply(TrackingStatus.DAY_ENDED),
                (int) rows.stream().filter(TeamMemberRow::isStale).count(),
                totalProductiveSeconds, totalBreakSeconds, rows.stream().mapToLong(TeamMemberRow::extraSeconds).sum(),
                startedCount == 0 ? 0 : totalProductiveSeconds / startedCount);
    }

    /** First name, last name, "first last", email or employee code contains the (lower-case) search text. */
    private static boolean matchesSearch(TeamMemberEmployee employee, String searchText) {
        String fullName = employee.firstName() + " " + employee.lastName();
        return fullName.toLowerCase(Locale.ROOT).contains(searchText)
                || employee.email().toLowerCase(Locale.ROOT).contains(searchText)
                || (employee.employeeCode() != null && employee.employeeCode().toLowerCase(Locale.ROOT).contains(searchText));
    }

    private Business loadBusiness(UUID businessId) {
        return businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
    }
}

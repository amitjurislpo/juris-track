package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.DateRange;
import com.jurislpo.juristrack.engine.DateRanges;
import com.jurislpo.juristrack.engine.IntervalMath;
import com.jurislpo.juristrack.engine.LastBreak;
import com.jurislpo.juristrack.engine.RangePreset;
import com.jurislpo.juristrack.engine.TimeInterval;
import com.jurislpo.juristrack.engine.WorkdayShift;
import com.jurislpo.juristrack.engine.TrackingStatus;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.engine.WorkdayTimeline;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.Workday;
import com.jurislpo.juristrack.entity.WorkdayStatus;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.ReportQueryRepository;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workdays: the history of a date range (rows, totals and a daily series for the chart) and one workday with its work and break
 * intervals, for the signed-in user's own workdays and (Team tab) for the people a manager or the owner may see
 * ({@link TeamAccessPolicy}). Ported from the old app's {@code getWorkdayRecords}, {@code totalsOf} and {@code dailySeries} as used
 * by the "My history" and dashboard pages. A completed workday shows its cached totals; an open one is computed live from its
 * intervals.
 */
@Service
public class WorkdayHistoryService {

    /**
     * One workday of the history.
     *
     * @param id                the workday
     * @param date              its date (business time zone, when it started)
     * @param status            WORKING, ON_BREAK or DAY_ENDED
     * @param isStale           open and longer than the business's stale workday hours ("not ended")
     * @param startedAt         start of the day
     * @param endedAt           end of the day, null while open
     * @param productiveSeconds work time (live while open)
     * @param breakSeconds      break time (live while open)
     * @param spanSeconds       start to end (or now)
     * @param breakCount        breaks taken
     * @param lastBreak         the break that started last (its end is null while it runs), or null without a break
     * @param shift             the shift the day was attributed to (null for a record without one)
     * @param varianceSeconds   productive minus scheduled time once the day has ended, else null
     * @param extraSeconds      productive time outside the scheduled window (included in productiveSeconds)
     */
    public record WorkdayRow(UUID id, LocalDate date, TrackingStatus status, @JsonProperty("isStale") boolean isStale, Instant startedAt,
            Instant endedAt, long productiveSeconds, long breakSeconds, long spanSeconds, int breakCount, LastBreak lastBreak, WorkdayShift shift,
            @JsonProperty("isOutsideShift") boolean isOutsideShift, @JsonProperty("isOffDay") boolean isOffDay, Long varianceSeconds,
            long extraSeconds) {}

    /**
     * The sums over every workday of the range (all pages). {@code averageProductiveSeconds}: productive seconds per workday of the range,
     * rounded half up, 0 without workdays (the one definition of the reports too).
     */
    public record WorkdayTotalsSummary(int workdays, long productiveSeconds, long breakSeconds, long spanSeconds, int breakCount,
            long averageProductiveSeconds, long extraSeconds) {}

    /** One day of the chart; every date of the range is present (zero-filled). */
    public record DailyPoint(LocalDate date, long productiveSeconds, long breakSeconds, int workdays) {}

    /**
     * {@code GET /api/workdays/mine}: one page of rows plus what the whole range adds up to.
     *
     * @param rows        this page's workdays
     * @param total       workdays in the range (all pages)
     * @param page        this page's number, from 1
     * @param pageSize    rows per page
     * @param range       the preset used ({@code today}, {@code yesterday}, {@code week}, {@code month}, {@code custom})
     * @param from        first date of the range (included)
     * @param to          last date of the range (included)
     * @param totals      sums over the whole range
     * @param dailySeries one point per date from {@code from} to {@code to}
     */
    public record WorkdayHistory(List<WorkdayRow> rows, long total, int page, int pageSize, String range, LocalDate from, LocalDate to,
            WorkdayTotalsSummary totals, List<DailyPoint> dailySeries) {}

    /** The sort keys of {@code GET /api/workdays/mine}, in the order the API documents them. */
    public static final List<String> SORT_KEYS = List.of("date", "startedAt", "endedAt", "productiveSeconds", "breakSeconds", "spanSeconds",
            "breakCount");
    private static final Map<String, Comparator<WorkdayRow>> SORT_ORDERS = Map.of(
            "date", Comparator.comparing(WorkdayRow::date),
            "startedAt", Comparator.comparing(WorkdayRow::startedAt),
            "endedAt", Comparator.comparing(WorkdayRow::endedAt, Comparator.nullsLast(Comparator.naturalOrder())),
            "productiveSeconds", Comparator.comparingLong(WorkdayRow::productiveSeconds),
            "breakSeconds", Comparator.comparingLong(WorkdayRow::breakSeconds),
            "spanSeconds", Comparator.comparingLong(WorkdayRow::spanSeconds),
            "breakCount", Comparator.comparingInt(WorkdayRow::breakCount));
    private static final String NOT_FOUND = "Workday not found.";

    private final Clock clock;
    private final WorkdayRepository workdayRepository;
    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;
    private final BusinessRepository businessRepository;
    private final WorkdaySnapshots workdaySnapshots;
    private final ShiftResolver shiftResolver;
    private final TeamAccessPolicy teamAccessPolicy;
    private final TeamAccessAudit teamAccessAudit;
    private final ReportQueryRepository reportQueryRepository;

    public WorkdayHistoryService(Clock clock, WorkdayRepository workdayRepository, WorkSessionRepository workSessionRepository,
            BreakSessionRepository breakSessionRepository, BusinessRepository businessRepository, WorkdaySnapshots workdaySnapshots,
            ShiftResolver shiftResolver, TeamAccessPolicy teamAccessPolicy, TeamAccessAudit teamAccessAudit,
            ReportQueryRepository reportQueryRepository) {
        this.clock = clock;
        this.workdayRepository = workdayRepository;
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
        this.businessRepository = businessRepository;
        this.workdaySnapshots = workdaySnapshots;
        this.shiftResolver = shiftResolver;
        this.teamAccessPolicy = teamAccessPolicy;
        this.teamAccessAudit = teamAccessAudit;
        this.reportQueryRepository = reportQueryRepository;
    }

    /**
     * The signed-in user's history: {@code range} ({@code today}, {@code yesterday}, {@code week}, {@code month} (default),
     * {@code custom} with {@code from} / {@code to} as {@code YYYY-MM-DD}, see {@link DateRanges}), {@code sort} ({@link #SORT_KEYS},
     * default {@code date}), {@code sortDirection} (default {@code desc} here: newest first), {@code page}, {@code pageSize}.
     * 400 for an unknown range, a malformed date or a bad list parameter.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkdayHistory listOwnWorkdays(CurrentUser currentUser, Map<String, String> queryParameters) {
        return listWorkdays(currentUser.userId(), currentUser.businessId(), queryParameters);
    }

    /**
     * The history of {@code employeeId}, a user of {@code businessId}, with the parameters and answer of
     * {@link #listOwnWorkdays}. The caller checks that it may be seen (inside its own transaction).
     */
    WorkdayHistory listWorkdays(UUID employeeId, UUID businessId, Map<String, String> queryParameters) {
        Map<String, String> parameters = new HashMap<>(queryParameters);
        parameters.putIfAbsent("sortDirection", "desc");
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(parameters, SORT_KEYS, "date", validator);
        String rangeKey = parameters.getOrDefault("range", "").trim();
        if (!rangeKey.isEmpty() && RangePreset.fromKey(rangeKey).isEmpty()) {
            validator.reject("range", "The range is today, yesterday, week, month or custom.");
        }
        String fromText = parameters.getOrDefault("from", "").trim();
        String toText = parameters.getOrDefault("to", "").trim();
        if (!fromText.isEmpty() && !BusinessCalendar.isDateKey(fromText)) validator.reject("from", "The start date is a date (YYYY-MM-DD).");
        if (!toText.isEmpty() && !BusinessCalendar.isDateKey(toText)) validator.reject("to", "The end date is a date (YYYY-MM-DD).");
        validator.throwIfInvalid();

        Business business = loadBusiness(businessId);
        Instant now = now();
        // "today" is the person's current shift date, so a night shift that runs past midnight (or just ended) is in "Today"
        ZoneId zone = BusinessCalendar.zoneOf(business.getTimeZone());
        LocalDate today = shiftResolver.attributionOf(businessId, employeeId, now, zone).occurrence().date();
        DateRange range = DateRanges.resolveRange(rangeKey, fromText, toText, today, RangePreset.MONTH);

        List<Workday> workdays = workdayRepository.findByEmployeeIdAndWorkDateBetween(employeeId, range.from(), range.to());
        List<WorkdayRow> rows = rowsOf(workdays, now, business.getStaleWorkdayHours());
        Comparator<WorkdayRow> order = SORT_ORDERS.get(listQuery.sort());
        if (!listQuery.isAscending()) order = order.reversed();
        order = order.thenComparing(WorkdayRow::date, Comparator.reverseOrder()).thenComparing(WorkdayRow::startedAt);
        List<WorkdayRow> sortedRows = rows.stream().sorted(order).toList();
        int fromIndex = (int) Math.min(listQuery.offset(), sortedRows.size());
        int toIndex = Math.min(fromIndex + listQuery.pageSize(), sortedRows.size());
        return new WorkdayHistory(sortedRows.subList(fromIndex, toIndex), sortedRows.size(), listQuery.page(), listQuery.pageSize(),
                range.preset().key(), range.from(), range.to(), totalsOf(rows), dailySeries(rows, range.from(), range.to()));
    }

    /**
     * One workday with every work and break interval: one of the signed-in user's own, or one of somebody a manager or the owner may
     * see ({@link TeamAccessPolicy}; audited as access). 404 for anybody else's and for another business's, so existence is not
     * revealed. Not read-only: reading somebody else's workday writes its access audit entry.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public WorkdaySnapshot findWorkday(CurrentUser currentUser, UUID workdayId) {
        Workday workday = workdayRepository.findById(workdayId).filter(day -> day.getBusinessId().equals(currentUser.businessId()))
                .orElseThrow(() -> new OutOfScopeException("workday", NOT_FOUND));
        if (!workday.getEmployeeId().equals(currentUser.userId())) {
            if (!teamAccessPolicy.canView(currentUser, workday.getEmployeeId())) throw new OutOfScopeException("workday", NOT_FOUND);
            teamAccessAudit.recordWorkdayViewed(currentUser, workday.getEmployeeId(), workday.getId(), workday.getWorkDate());
        }
        return workdaySnapshots.snapshotOf(workday, now(), loadBusiness(currentUser.businessId()).getStaleWorkdayHours());
    }

    /** Completed workdays with cached totals use them; the others are computed from their intervals as of {@code now}. */
    private List<WorkdayRow> rowsOf(List<Workday> workdays, Instant now, int staleWorkdayHours) {
        List<UUID> liveWorkdayIds = workdays.stream().filter(workday -> !hasCachedTotals(workday)).map(Workday::getId).toList();
        Map<UUID, List<WorkSession>> workSessionsByWorkdayId = liveWorkdayIds.isEmpty() ? Map.of()
                : workSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(liveWorkdayIds).stream()
                        .collect(Collectors.groupingBy(WorkSession::getWorkdayId));
        Map<UUID, List<BreakSession>> breakSessionsByWorkdayId = liveWorkdayIds.isEmpty() ? Map.of()
                : breakSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(liveWorkdayIds).stream()
                        .collect(Collectors.groupingBy(BreakSession::getWorkdayId));
        Map<UUID, LastBreak> lastBreakByWorkdayId = reportQueryRepository.findLastBreaksByWorkdayId(
                workdays.stream().filter(WorkdayHistoryService::hasCachedTotals).map(Workday::getId).toList());
        List<WorkdayRow> rows = new ArrayList<>();
        for (Workday workday : workdays) {
            if (hasCachedTotals(workday)) {
                long spanSeconds = IntervalMath.intervalSeconds(new TimeInterval(workday.getStartedAt(), workday.getEndedAt()), now);
                rows.add(new WorkdayRow(workday.getId(), workday.getWorkDate(), TrackingStatus.DAY_ENDED, false, workday.getStartedAt(),
                        workday.getEndedAt(), workday.getProductiveSeconds(), workday.getBreakSeconds() == null ? 0 : workday.getBreakSeconds(),
                        spanSeconds, workday.getBreakCount() == null ? 0 : workday.getBreakCount(), lastBreakByWorkdayId.get(workday.getId()),
                        WorkdaySnapshots.shiftOf(workday), workday.isOutsideShift(), workday.isOffDay(), varianceOf(workday),
                        workday.getExtraSeconds() == null ? 0 : workday.getExtraSeconds()));
            } else {
                WorkdaySnapshot snapshot = WorkdaySnapshots.snapshotOf(workday, workSessionsByWorkdayId.getOrDefault(workday.getId(), List.of()),
                        breakSessionsByWorkdayId.getOrDefault(workday.getId(), List.of()), now, staleWorkdayHours);
                rows.add(new WorkdayRow(snapshot.id(), snapshot.date(), snapshot.status(), snapshot.isStale(), snapshot.startedAt(),
                        snapshot.endedAt(), snapshot.productiveSeconds(), snapshot.breakSeconds(), snapshot.spanSeconds(), snapshot.breakCount(),
                        snapshot.lastBreak(), snapshot.shift(), snapshot.isOutsideShift(), snapshot.isOffDay(), snapshot.varianceSeconds(), snapshot.extraSeconds()));
            }
        }
        return rows;
    }

    private static Long varianceOf(Workday workday) {
        WorkdayShift shift = WorkdaySnapshots.shiftOf(workday);
        if (workday.getProductiveSeconds() == null || shift == null) return null;
        return workday.isOffDay() ? Long.valueOf(workday.getProductiveSeconds()) : Long.valueOf(shift.varianceOf(workday.getProductiveSeconds()));
    }

    private static boolean hasCachedTotals(Workday workday) {
        return workday.getStatus() == WorkdayStatus.COMPLETED && workday.getProductiveSeconds() != null;
    }

    static WorkdayTotalsSummary totalsOf(List<WorkdayRow> rows) {
        return new WorkdayTotalsSummary(rows.size(), rows.stream().mapToLong(WorkdayRow::productiveSeconds).sum(),
                rows.stream().mapToLong(WorkdayRow::breakSeconds).sum(), rows.stream().mapToLong(WorkdayRow::spanSeconds).sum(),
                rows.stream().mapToInt(WorkdayRow::breakCount).sum(), averageOf(rows.stream().mapToLong(WorkdayRow::productiveSeconds).sum(), rows.size()),
                rows.stream().mapToLong(WorkdayRow::extraSeconds).sum());
    }

    /** Seconds per workday rounded half up (like JavaScript's {@code Math.round}); 0 without workdays. */
    static long averageOf(long seconds, int workdays) {
        return workdays == 0 ? 0 : Math.round((double) seconds / workdays);
    }

    /** One point per calendar day from {@code from} to {@code to}, zero-filled. */
    static List<DailyPoint> dailySeries(List<WorkdayRow> rows, LocalDate from, LocalDate to) {
        Map<LocalDate, List<WorkdayRow>> rowsByDate = rows.stream().collect(Collectors.groupingBy(WorkdayRow::date));
        Map<LocalDate, DailyPoint> points = new LinkedHashMap<>();
        for (LocalDate date : BusinessCalendar.eachDay(from, to)) {
            List<WorkdayRow> rowsOfDate = rowsByDate.getOrDefault(date, List.of());
            points.put(date, new DailyPoint(date, rowsOfDate.stream().mapToLong(WorkdayRow::productiveSeconds).sum(),
                    rowsOfDate.stream().mapToLong(WorkdayRow::breakSeconds).sum(), rowsOfDate.size()));
        }
        return List.copyOf(points.values());
    }

    private Business loadBusiness(UUID businessId) {
        return businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
    }

    private Instant now() {
        return clock.instant();
    }
}

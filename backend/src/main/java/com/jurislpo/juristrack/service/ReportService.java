package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.CsvFormat;
import com.jurislpo.juristrack.engine.DateRange;
import com.jurislpo.juristrack.engine.DateRanges;
import com.jurislpo.juristrack.engine.IntervalMath;
import com.jurislpo.juristrack.engine.RangePreset;
import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.engine.TimeInterval;
import com.jurislpo.juristrack.engine.TrackingStatus;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.engine.WorkdayTimeline;
import com.jurislpo.juristrack.engine.ZoneNames;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.WorkdayStatus;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.exception.Problems.FieldError;
import com.jurislpo.juristrack.exception.ValidationException;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository;
import com.jurislpo.juristrack.repository.ReportQueryRepository;
import com.jurislpo.juristrack.repository.ReportQueryRepository.StoredWorkday;
import com.jurislpo.juristrack.repository.ReportQueryRepository.WorkdayFilter;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.WorkdayHistoryService.DailyPoint;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports of managers and owners (contract: references/API.md): the workdays of the people the caller may see for a date
 * range ({@code GET /api/reports/workdays}, also as CSV), and the same workdays added up per employee, per manager or per date
 * ({@code GET /api/reports/summaries}). Ported from the old app's {@code reports.service.ts} ({@code getWorkdayRecords},
 * {@code totalsOf}, {@code aggregate}, {@code dailySeries}) and {@code api/reports/export/route.ts}; teams became the reporting line.
 *
 * <p><b>Who sees whom</b> (INTEGRATION.md 3b): a MANAGER sees their direct reports ({@code scope=direct}, the default) or also the
 * reports of those ({@code scope=indirect}), never themselves; the owner (ADMIN) sees everybody in the business ({@code scope=all},
 * the default) or, with {@code direct} / {@code indirect}, their own reporting line. An employee or manager filter outside the scope
 * answers 404 like an unknown id. Every request is audited once ({@link ReportAccessAudit}).
 *
 * <p><b>Figures</b>: an ended workday shows the totals stored when it ended; an open one is computed from its intervals up to now
 * (its intervals are loaded in one query for all open days). The whole range is read in one query, so the totals and the daily series
 * cover every page.
 */
@Service
public class ReportService {

    /** The employee of a report row. */
    public record EmployeeReference(UUID id, String employeeCode, String name) {}

    /** The employee's manager on the day of the workday (the workday's snapshot), with today's name. */
    public record ManagerReference(UUID id, String name) {}

    /**
     * One workday of the report.
     *
     * @param status  WORKING, ON_BREAK or DAY_ENDED
     * @param isStale open and longer than the business's stale workday hours ("Not ended")
     * @param manager null when the employee had no manager that day
     */
    public record ReportRow(UUID id, LocalDate date, EmployeeReference employee, ManagerReference manager, TrackingStatus status,
            @JsonProperty("isStale") boolean isStale, Instant startedAt, Instant endedAt, long productiveSeconds, long breakSeconds,
            long spanSeconds, int breakCount) {}

    /**
     * What every workday of the range adds up to (all pages).
     *
     * @param averageProductiveSeconds productive seconds per workday, rounded (0 without workdays)
     */
    public record ReportTotals(int workdays, int employees, long productiveSeconds, long breakSeconds, long spanSeconds, int breakCount,
            long averageProductiveSeconds) {}

    /** {@code GET /api/reports/workdays}: one page of workdays plus the totals and the daily series of the whole range. */
    public record WorkdayReport(List<ReportRow> rows, long total, int page, int pageSize, String range, LocalDate from, LocalDate to,
            String scope, ReportTotals totals, List<DailyPoint> dailySeries) {}

    /**
     * One group of {@code GET /api/reports/summaries}.
     *
     * @param key            the employee's id, the manager's id ({@value #NO_MANAGER_KEY} for workdays without a manager) or the date
     * @param label          the employee's or manager's name, or the date; null for the "no manager" group
     * @param employeeCode   the employee's code when grouped by employee, else null
     * @param employees      how many different people the group's workdays belong to
     * @param firstStartedAt the earliest start of the group's workdays
     * @param lastEndedAt    the latest end of the group's ended workdays, or null
     */
    public record SummaryRow(String key, String label, String employeeCode, int workdays, int employees, long productiveSeconds,
            long breakSeconds, long spanSeconds, int breakCount, long averageProductiveSeconds, Instant firstStartedAt, Instant lastEndedAt) {}

    /** {@code GET /api/reports/summaries}: one page of groups plus the totals and the daily series of the whole range. */
    public record SummaryReport(List<SummaryRow> rows, long total, int page, int pageSize, String groupBy, String range, LocalDate from,
            LocalDate to, String scope, ReportTotals totals, List<DailyPoint> dailySeries) {}

    /** The CSV download of the workday report. */
    public record WorkdayExport(String fileName, byte[] content, int rowCount) {}

    public static final List<String> WORKDAY_SORT_KEYS = List.of("date", "employee", "startedAt", "productiveSeconds", "breakSeconds",
            "spanSeconds", "breakCount");
    public static final List<String> SUMMARY_SORT_KEYS = List.of("label", "workdays", "employees", "productiveSeconds", "breakSeconds",
            "spanSeconds", "breakCount", "averageProductiveSeconds");
    public static final List<String> GROUP_BY_KEYS = List.of("employee", "manager", "date");
    /** The {@code status} filter values; NOT_ENDED = an open day longer than the stale workday hours. */
    public static final List<String> STATUS_FILTERS = List.of("WORKING", "ON_BREAK", "DAY_ENDED", "NOT_ENDED");
    private static final String DIRECT_SCOPE = "direct";
    private static final String INDIRECT_SCOPE = "indirect";
    private static final String ALL_SCOPE = "all";
    /** The {@code scope} values: direct reports, also indirect reports, everybody (owner only). */
    public static final List<String> SCOPES = List.of(DIRECT_SCOPE, INDIRECT_SCOPE, ALL_SCOPE);
    public static final String NO_MANAGER_KEY = "none";
    static final String WORKDAYS_REPORT = "workdays";
    static final String SUMMARIES_REPORT = "summaries";
    private static final String NOT_ENDED = "NOT_ENDED";
    /** The CSV status of a stale open day (the old app's "Not ended" badge); the others are the old LIVE_STATUS_LABELS. */
    private static final String NOT_ENDED_LABEL = "Not ended";
    private static final Map<TrackingStatus, String> STATUS_LABELS = Map.of(TrackingStatus.WORKING, "Working", TrackingStatus.ON_BREAK,
            "On Break", TrackingStatus.DAY_ENDED, "Day Ended");
    private static final Map<String, Comparator<ReportRow>> WORKDAY_ORDERS = Map.of(
            "date", Comparator.comparing(ReportRow::date),
            "employee", Comparator.comparing((ReportRow row) -> row.employee().name(), String.CASE_INSENSITIVE_ORDER),
            "startedAt", Comparator.comparing(ReportRow::startedAt),
            "productiveSeconds", Comparator.comparingLong(ReportRow::productiveSeconds),
            "breakSeconds", Comparator.comparingLong(ReportRow::breakSeconds),
            "spanSeconds", Comparator.comparingLong(ReportRow::spanSeconds),
            "breakCount", Comparator.comparingInt(ReportRow::breakCount));
    private static final Comparator<ReportRow> WORKDAY_TIE_BREAK = Comparator.comparing(ReportRow::date, Comparator.reverseOrder())
            .thenComparing(row -> row.employee().name(), String.CASE_INSENSITIVE_ORDER).thenComparing(ReportRow::startedAt)
            .thenComparing(ReportRow::id);
    private static final Map<String, Comparator<SummaryRow>> SUMMARY_ORDERS = Map.of(
            "workdays", Comparator.comparingInt(SummaryRow::workdays),
            "employees", Comparator.comparingInt(SummaryRow::employees),
            "productiveSeconds", Comparator.comparingLong(SummaryRow::productiveSeconds),
            "breakSeconds", Comparator.comparingLong(SummaryRow::breakSeconds),
            "spanSeconds", Comparator.comparingLong(SummaryRow::spanSeconds),
            "breakCount", Comparator.comparingInt(SummaryRow::breakCount),
            "averageProductiveSeconds", Comparator.comparingLong(SummaryRow::averageProductiveSeconds));
    private static final Comparator<SummaryRow> SUMMARY_TIE_BREAK = Comparator.comparing(SummaryRow::label,
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(SummaryRow::key);

    /**
     * A report request after its query parameters were checked.
     *
     * @param visibleEmployeeIds the people in scope; null = everybody in the business
     * @param statuses           the status filter (empty = every status)
     * @param auditFilters       the filters as the audit entry records them
     */
    private record ReportRequest(Business business, Instant now, DateRange range, String scope, Set<UUID> visibleEmployeeIds,
            UUID employeeId, UUID managerId, Set<String> statuses, ListQuery listQuery, Map<String, Object> auditFilters) {}

    private final Clock clock;
    private final BusinessRepository businessRepository;
    private final ReportQueryRepository reportQueryRepository;
    private final EmployeeQueryRepository employeeQueryRepository;
    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;
    private final ReportingScopeService reportingScopeService;
    private final ReportAccessAudit reportAccessAudit;
    private final int maximumExportRows;

    public ReportService(Clock clock, BusinessRepository businessRepository, ReportQueryRepository reportQueryRepository,
            EmployeeQueryRepository employeeQueryRepository, WorkSessionRepository workSessionRepository,
            BreakSessionRepository breakSessionRepository, ReportingScopeService reportingScopeService, ReportAccessAudit reportAccessAudit,
            @Value("${juristrack.reports.maximum-export-rows:50000}") int maximumExportRows) {
        this.clock = clock;
        this.businessRepository = businessRepository;
        this.reportQueryRepository = reportQueryRepository;
        this.employeeQueryRepository = employeeQueryRepository;
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
        this.reportingScopeService = reportingScopeService;
        this.reportAccessAudit = reportAccessAudit;
        this.maximumExportRows = maximumExportRows;
    }

    /** {@code GET /api/reports/workdays}: default {@code sort=date}, {@code sortDirection=desc} (newest first), {@code range=week}. */
    @Transactional
    public WorkdayReport listWorkdays(CurrentUser currentUser, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ReportRequest request = readRequest(currentUser, withDefaultDirection(queryParameters, "desc"), WORKDAY_SORT_KEYS, "date", validator);
        List<ReportRow> rows = sortedRows(request);
        reportAccessAudit.recordView(currentUser, WORKDAYS_REPORT, request.auditFilters(), request.employeeId(), rows.size());
        return new WorkdayReport(pageOf(rows, request.listQuery()), rows.size(), request.listQuery().page(), request.listQuery().pageSize(),
                request.range().preset().key(), request.range().from(), request.range().to(), request.scope(), totalsOf(rows),
                dailySeries(rows, request.range().from(), request.range().to()));
    }

    /**
     * {@code GET /api/reports/summaries}: {@code groupBy} = employee (default) | manager | date; default {@code sort=label}, ascending
     * (descending, newest first, for {@code groupBy=date}).
     */
    @Transactional
    public SummaryReport listSummaries(CurrentUser currentUser, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        String groupBy = queryParameters.getOrDefault("groupBy", "").trim().toLowerCase(Locale.ROOT);
        if (groupBy.isEmpty()) groupBy = "employee";
        if (!GROUP_BY_KEYS.contains(groupBy)) validator.reject("groupBy", "Group by employee, manager or date.");
        Map<String, String> parameters = withDefaultDirection(queryParameters, groupBy.equals("date") ? "desc" : "asc");
        ReportRequest request = readRequest(currentUser, parameters, SUMMARY_SORT_KEYS, "label", validator);
        request.auditFilters().put("groupBy", groupBy);
        List<ReportRow> rows = loadRows(request);
        List<SummaryRow> groups = summarize(rows, groupBy);
        Comparator<SummaryRow> order = summaryOrder(request.listQuery());
        List<SummaryRow> sortedGroups = groups.stream().sorted(order).toList();
        reportAccessAudit.recordView(currentUser, SUMMARIES_REPORT, request.auditFilters(), request.employeeId(), sortedGroups.size());
        return new SummaryReport(pageOf(sortedGroups, request.listQuery()), sortedGroups.size(), request.listQuery().page(),
                request.listQuery().pageSize(), groupBy, request.range().preset().key(), request.range().from(), request.range().to(),
                request.scope(), totalsOf(rows), dailySeries(rows, request.range().from(), request.range().to()));
    }

    /**
     * The workday report as CSV (every row of the range in the requested order, no paging): the times in the display time zone
     * {@code timeZone} (the business time zone by default; it must be the business time zone or one of its display time zones),
     * headers naming that zone's abbreviation at the moment of the export. 422 {@code EXPORT_TOO_LARGE} above the row cap.
     */
    @Transactional
    public WorkdayExport exportWorkdays(CurrentUser currentUser, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ReportRequest request = readRequest(currentUser, withDefaultDirection(queryParameters, "desc"), WORKDAY_SORT_KEYS, "date", validator);
        ZoneId displayZone = displayZoneOf(request.business(), queryParameters.getOrDefault("timeZone", ""));
        List<ReportRow> rows = sortedRows(request);
        if (rows.size() > maximumExportRows) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.EXPORT_TOO_LARGE, "This export has " + rows.size()
                    + " rows; at most " + maximumExportRows + " fit in one file. Choose a shorter date range or fewer people.");
        }
        String abbreviation = ZoneNames.zoneAbbreviation(displayZone, request.now());
        List<String> lines = new ArrayList<>();
        lines.add(CsvFormat.line(List.of("Date", "Employee", "Employee ID", "Manager", "Status", "Start (" + abbreviation + ")",
                "End (" + abbreviation + ")", "Productive hours", "Break hours", "Span hours", "Breaks")));
        for (ReportRow row : rows) {
            lines.add(CsvFormat.line(List.of(row.date().toString(), row.employee().name(),
                    row.employee().employeeCode() == null ? "" : row.employee().employeeCode(),
                    row.manager() == null || row.manager().name() == null ? "" : row.manager().name(),
                    row.isStale() ? NOT_ENDED_LABEL : STATUS_LABELS.get(row.status()), CsvFormat.wallClockTime(row.startedAt(), displayZone),
                    row.endedAt() == null ? "" : CsvFormat.wallClockTime(row.endedAt(), displayZone), CsvFormat.decimalHours(row.productiveSeconds()),
                    CsvFormat.decimalHours(row.breakSeconds()), CsvFormat.decimalHours(row.spanSeconds()), row.breakCount())));
        }
        String fileName = "juristrack-" + request.range().from() + "-to-" + request.range().to() + ".csv";
        reportAccessAudit.recordExport(currentUser, WORKDAYS_REPORT, request.auditFilters(), request.employeeId(), rows.size(),
                displayZone.getId(), fileName);
        return new WorkdayExport(fileName, CsvFormat.document(lines), rows.size());
    }

    // ── request ─────────────────────────────────────────────────────────────

    private static Map<String, String> withDefaultDirection(Map<String, String> queryParameters, String direction) {
        Map<String, String> parameters = new LinkedHashMap<>(queryParameters);
        parameters.putIfAbsent("sortDirection", direction);
        return parameters;
    }

    /** Checks every shared parameter (400 for all problems at once), then the scope (403) and the employee / manager filter (404). */
    private ReportRequest readRequest(CurrentUser currentUser, Map<String, String> parameters, List<String> sortKeys, String defaultSort,
            InputValidator validator) {
        ListQuery listQuery = ListQuery.of(parameters, sortKeys, defaultSort, validator);
        String rangeKey = parameters.getOrDefault("range", "").trim();
        if (!rangeKey.isEmpty() && RangePreset.fromKey(rangeKey).isEmpty()) {
            validator.reject("range", "The range is today, yesterday, week, month or custom.");
        }
        String fromText = parameters.getOrDefault("from", "").trim();
        String toText = parameters.getOrDefault("to", "").trim();
        if (!fromText.isEmpty() && !BusinessCalendar.isDateKey(fromText)) validator.reject("from", "The start date is a date (YYYY-MM-DD).");
        if (!toText.isEmpty() && !BusinessCalendar.isDateKey(toText)) validator.reject("to", "The end date is a date (YYYY-MM-DD).");
        String scope = parameters.getOrDefault("scope", "").trim().toLowerCase(Locale.ROOT);
        if (scope.isEmpty()) scope = currentUser.hasRole(Role.ADMIN) ? ALL_SCOPE : DIRECT_SCOPE;
        if (!SCOPES.contains(scope)) validator.reject("scope", "The scope is direct, indirect or all.");
        UUID employeeId = optionalId(parameters, "employeeId", "The employee is an id.", validator);
        UUID managerId = optionalId(parameters, "managerId", "The manager is an id.", validator);
        Set<String> statuses = new LinkedHashSet<>();
        for (String status : parameters.getOrDefault("status", "").split(",")) {
            String statusKey = status.trim().toUpperCase(Locale.ROOT);
            if (statusKey.isEmpty()) continue;
            if (STATUS_FILTERS.contains(statusKey)) statuses.add(statusKey);
            else validator.reject("status", "The status is one or more of " + String.join(", ", STATUS_FILTERS) + ".");
        }
        validator.throwIfInvalid();

        if (scope.equals(ALL_SCOPE) && !currentUser.hasRole(Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can see only the people who report to you.");
        }
        Business business = businessRepository.findById(currentUser.businessId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.toDateKey(now, BusinessCalendar.zoneOf(business.getTimeZone()));
        DateRange range = DateRanges.resolveRange(rangeKey, fromText, toText, today, RangePreset.WEEK);
        Set<UUID> visibleEmployeeIds = scope.equals(ALL_SCOPE) ? null : reportingScopeService.visibleEmployeeIds(business.getId(),
                currentUser.userId(), scope.equals(DIRECT_SCOPE) ? ReportingLine.DIRECT_REPORTS : ReportingLine.WITH_INDIRECT_REPORTS);
        if (employeeId != null && !isVisible(business.getId(), visibleEmployeeIds, employeeId)) {
            throw new OutOfScopeException("user", "Employee not found.");
        }
        if (managerId != null && !managerId.equals(currentUser.userId()) && !isVisible(business.getId(), visibleEmployeeIds, managerId)) {
            throw new OutOfScopeException("user", "Manager not found.");
        }

        Map<String, Object> auditFilters = new LinkedHashMap<>();
        auditFilters.put("range", range.preset().key());
        auditFilters.put("from", range.from().toString());
        auditFilters.put("to", range.to().toString());
        auditFilters.put("scope", scope);
        auditFilters.put("employeeId", employeeId);
        auditFilters.put("managerId", managerId);
        auditFilters.put("status", List.copyOf(statuses));
        auditFilters.put("search", listQuery.search().isEmpty() ? null : listQuery.search());
        auditFilters.put("sort", listQuery.sort());
        auditFilters.put("sortDirection", listQuery.isAscending() ? "asc" : "desc");
        return new ReportRequest(business, now, range, scope, visibleEmployeeIds, employeeId, managerId, statuses, listQuery, auditFilters);
    }

    /** In scope: one of the visible people, or (scope all) any user of the business. */
    private boolean isVisible(UUID businessId, Set<UUID> visibleEmployeeIds, UUID userId) {
        return visibleEmployeeIds == null ? employeeQueryRepository.findRow(businessId, userId).isPresent() : visibleEmployeeIds.contains(userId);
    }

    private static UUID optionalId(Map<String, String> parameters, String field, String message, InputValidator validator) {
        String text = parameters.getOrDefault(field, "").trim();
        if (text.isEmpty()) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            validator.reject(field, message);
            return null;
        }
    }

    /** The requested display zone: empty = the business time zone; otherwise the business zone or one of its display zones (else 400). */
    private static ZoneId displayZoneOf(Business business, String requestedTimeZone) {
        ZoneId businessZone = BusinessCalendar.zoneOf(business.getTimeZone());
        String trimmedTimeZone = requestedTimeZone.trim();
        if (trimmedTimeZone.isEmpty()) return businessZone;
        Set<String> allowedZoneIds = new HashSet<>();
        allowedZoneIds.add(businessZone.getId().toLowerCase(Locale.ROOT));
        business.getDisplayTimeZones().forEach(displayTimeZone -> BusinessCalendar.findZone(displayTimeZone)
                .ifPresent(zone -> allowedZoneIds.add(zone.getId().toLowerCase(Locale.ROOT))));
        ZoneId requestedZone = BusinessCalendar.findZone(trimmedTimeZone).orElse(null);
        if (requestedZone == null || !allowedZoneIds.contains(requestedZone.getId().toLowerCase(Locale.ROOT))) {
            throw new ValidationException(List.of(new FieldError("timeZone", "Choose the business time zone or one of its display time zones.")));
        }
        return requestedZone;
    }

    // ── rows ────────────────────────────────────────────────────────────────

    /** Every workday of the request (all pages), filtered by status and sorted by the requested key. */
    private List<ReportRow> sortedRows(ReportRequest request) {
        Comparator<ReportRow> order = WORKDAY_ORDERS.get(request.listQuery().sort());
        if (!request.listQuery().isAscending()) order = order.reversed();
        return loadRows(request).stream().sorted(order.thenComparing(WORKDAY_TIE_BREAK)).toList();
    }

    /** Every workday of the request (all pages), filtered by status, newest date first. */
    private List<ReportRow> loadRows(ReportRequest request) {
        Boolean isOpen = null;
        if (!request.statuses().isEmpty()) {
            boolean wantsEndedDays = request.statuses().contains(TrackingStatus.DAY_ENDED.name());
            boolean wantsOpenDays = request.statuses().size() > (wantsEndedDays ? 1 : 0);
            if (wantsEndedDays != wantsOpenDays) isOpen = wantsOpenDays;
        }
        List<StoredWorkday> storedWorkdays = reportQueryRepository.findWorkdays(new WorkdayFilter(request.business().getId(),
                request.range().from(), request.range().to(), request.visibleEmployeeIds(), request.employeeId(), request.managerId(), isOpen,
                request.listQuery().searchPattern()));
        return rowsOf(storedWorkdays, request.now(), request.business().getStaleWorkdayHours()).stream()
                .filter(row -> matchesStatus(row, request.statuses())).sorted(WORKDAY_TIE_BREAK).toList();
    }

    private static boolean matchesStatus(ReportRow row, Set<String> statuses) {
        return statuses.isEmpty() || statuses.contains(row.status().name()) || (row.isStale() && statuses.contains(NOT_ENDED));
    }

    /** Ended workdays with stored totals use them; the others are computed from their intervals as of {@code now}. */
    private List<ReportRow> rowsOf(List<StoredWorkday> storedWorkdays, Instant now, int staleWorkdayHours) {
        List<UUID> liveWorkdayIds = storedWorkdays.stream().filter(workday -> !hasStoredTotals(workday)).map(StoredWorkday::id).toList();
        Map<UUID, List<WorkSession>> workSessionsByWorkdayId = liveWorkdayIds.isEmpty() ? Map.of()
                : workSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(liveWorkdayIds).stream()
                        .collect(Collectors.groupingBy(WorkSession::getWorkdayId));
        Map<UUID, List<BreakSession>> breakSessionsByWorkdayId = liveWorkdayIds.isEmpty() ? Map.of()
                : breakSessionRepository.findByWorkdayIdInOrderByStartedAtAsc(liveWorkdayIds).stream()
                        .collect(Collectors.groupingBy(BreakSession::getWorkdayId));
        List<ReportRow> rows = new ArrayList<>(storedWorkdays.size());
        for (StoredWorkday workday : storedWorkdays) {
            EmployeeReference employee = new EmployeeReference(workday.employeeId(), workday.employeeCode(),
                    (workday.employeeFirstName() + " " + workday.employeeLastName()).trim());
            ManagerReference manager = workday.managerId() == null ? null : new ManagerReference(workday.managerId(), workday.managerName());
            if (hasStoredTotals(workday)) {
                long spanSeconds = IntervalMath.intervalSeconds(new TimeInterval(workday.startedAt(), workday.endedAt()), now);
                rows.add(new ReportRow(workday.id(), workday.date(), employee, manager, TrackingStatus.DAY_ENDED, false, workday.startedAt(),
                        workday.endedAt(), workday.productiveSeconds(), workday.breakSeconds() == null ? 0 : workday.breakSeconds(), spanSeconds,
                        workday.breakCount() == null ? 0 : workday.breakCount()));
            } else {
                WorkdaySnapshot snapshot = WorkdayTimeline.snapshot(workday.id(), workday.date(), WorkdaySnapshots.statusOf(workday.status()),
                        workday.startedAt(), workday.endedAt(),
                        WorkdaySnapshots.workSegments(workSessionsByWorkdayId.getOrDefault(workday.id(), List.of())),
                        WorkdaySnapshots.breakSegments(breakSessionsByWorkdayId.getOrDefault(workday.id(), List.of())), now, staleWorkdayHours);
                rows.add(new ReportRow(workday.id(), workday.date(), employee, manager, snapshot.status(), snapshot.isStale(), snapshot.startedAt(),
                        snapshot.endedAt(), snapshot.productiveSeconds(), snapshot.breakSeconds(), snapshot.spanSeconds(), snapshot.breakCount()));
            }
        }
        return rows;
    }

    private static boolean hasStoredTotals(StoredWorkday workday) {
        return workday.status() == WorkdayStatus.COMPLETED && workday.productiveSeconds() != null;
    }

    private static <T> List<T> pageOf(List<T> rows, ListQuery listQuery) {
        int fromIndex = (int) Math.min(listQuery.offset(), rows.size());
        int toIndex = Math.min(fromIndex + listQuery.pageSize(), rows.size());
        return rows.subList(fromIndex, toIndex);
    }

    // ── totals, series, groups ──────────────────────────────────────────────

    /** The old {@code totalsOf}, plus the average productive time per workday. */
    static ReportTotals totalsOf(List<ReportRow> rows) {
        long productiveSeconds = rows.stream().mapToLong(ReportRow::productiveSeconds).sum();
        return new ReportTotals(rows.size(), (int) rows.stream().map(row -> row.employee().id()).distinct().count(), productiveSeconds,
                rows.stream().mapToLong(ReportRow::breakSeconds).sum(), rows.stream().mapToLong(ReportRow::spanSeconds).sum(),
                rows.stream().mapToInt(ReportRow::breakCount).sum(), WorkdayHistoryService.averageOf(productiveSeconds, rows.size()));
    }

    /** One point per calendar day from {@code from} to {@code to}, zero-filled (the old {@code dailySeries}). */
    static List<DailyPoint> dailySeries(List<ReportRow> rows, LocalDate from, LocalDate to) {
        Map<LocalDate, List<ReportRow>> rowsByDate = rows.stream().collect(Collectors.groupingBy(ReportRow::date));
        List<DailyPoint> points = new ArrayList<>();
        for (LocalDate date : BusinessCalendar.eachDay(from, to)) {
            List<ReportRow> rowsOfDate = rowsByDate.getOrDefault(date, List.of());
            points.add(new DailyPoint(date, rowsOfDate.stream().mapToLong(ReportRow::productiveSeconds).sum(),
                    rowsOfDate.stream().mapToLong(ReportRow::breakSeconds).sum(), rowsOfDate.size()));
        }
        return points;
    }

    /** The old {@code aggregate}: the workdays added up per employee, per manager of the day, or per date (unsorted). */
    static List<SummaryRow> summarize(List<ReportRow> rows, String groupBy) {
        Map<String, List<ReportRow>> rowsByKey = new LinkedHashMap<>();
        for (ReportRow row : rows) rowsByKey.computeIfAbsent(groupKeyOf(row, groupBy), key -> new ArrayList<>()).add(row);
        List<SummaryRow> groups = new ArrayList<>();
        rowsByKey.forEach((key, groupRows) -> {
            ReportRow first = groupRows.get(0);
            String label = switch (groupBy) {
                case "employee" -> first.employee().name();
                case "manager" -> first.manager() == null ? null : first.manager().name();
                default -> first.date().toString();
            };
            long productiveSeconds = groupRows.stream().mapToLong(ReportRow::productiveSeconds).sum();
            groups.add(new SummaryRow(key, label, groupBy.equals("employee") ? first.employee().employeeCode() : null, groupRows.size(),
                    (int) groupRows.stream().map(row -> row.employee().id()).distinct().count(), productiveSeconds,
                    groupRows.stream().mapToLong(ReportRow::breakSeconds).sum(), groupRows.stream().mapToLong(ReportRow::spanSeconds).sum(),
                    groupRows.stream().mapToInt(ReportRow::breakCount).sum(), WorkdayHistoryService.averageOf(productiveSeconds, groupRows.size()),
                    groupRows.stream().map(ReportRow::startedAt).min(Comparator.naturalOrder()).orElse(null),
                    groupRows.stream().map(ReportRow::endedAt).filter(endedAt -> endedAt != null).max(Comparator.naturalOrder()).orElse(null)));
        });
        return groups;
    }

    private static String groupKeyOf(ReportRow row, String groupBy) {
        return switch (groupBy) {
            case "employee" -> row.employee().id().toString();
            case "manager" -> row.manager() == null ? NO_MANAGER_KEY : row.manager().id().toString();
            default -> row.date().toString();
        };
    }

    /** {@code label} sorts by name (case-insensitive; the "no manager" group last either way), the others by their number. */
    private static Comparator<SummaryRow> summaryOrder(ListQuery listQuery) {
        if (listQuery.sort().equals("label")) {
            Comparator<String> textOrder = listQuery.isAscending() ? String.CASE_INSENSITIVE_ORDER : String.CASE_INSENSITIVE_ORDER.reversed();
            return Comparator.comparing(SummaryRow::label, Comparator.nullsLast(textOrder)).thenComparing(SummaryRow::key);
        }
        Comparator<SummaryRow> order = SUMMARY_ORDERS.get(listQuery.sort());
        return (listQuery.isAscending() ? order : order.reversed()).thenComparing(SUMMARY_TIE_BREAK);
    }
}

package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.engine.IntervalMath;
import com.jurislpo.juristrack.engine.SegmentType;
import com.jurislpo.juristrack.engine.TimeInterval;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.engine.WorkdayTimeline;
import com.jurislpo.juristrack.engine.WorkdayTotals;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.Workday;
import com.jurislpo.juristrack.entity.WorkdayStatus;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository.LockedEmployee;
import com.jurislpo.juristrack.repository.TeamQueryRepository;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.TimeCorrectionAudit.IntervalRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corrections of somebody else's time by their manager or the owner (decision D16; ported from the old app's
 * {@code time-corrections.service.ts}, which allowed only the owner): move a work or break interval, or end a forgotten (open)
 * workday. Rules:
 * <ul>
 *   <li>Who: {@link TeamAccessPolicy} (MANAGER: direct and indirect reports; ADMIN: anybody of the business). Never one's own time
 *       (403 {@code CANNOT_CORRECT_OWN_TIME}); out of scope or another business answers 404.</li>
 *   <li>A reason of 5 to 500 characters is mandatory; times are ISO-8601 instants (with {@code Z} or an offset), stored in UTC.</li>
 *   <li>An interval ends after it starts; nothing lies in the future; the intervals of a workday never overlap (work with work,
 *       break with break, and work with break); the first interval is a work interval (so every break lies inside the day); a
 *       running interval stays running (only its start moves; the workday is closed to end it) and a closed one stays closed.</li>
 *   <li>The workday's start and end follow its first and last interval; its date stays. Its cached totals are recomputed from the
 *       intervals (set when COMPLETED, empty while open), so they always equal the sum of the intervals. A COMPLETED workday stays
 *       COMPLETED.</li>
 *   <li>Each correction runs in one transaction that first locks the employee's row (as the time events do), so it never races
 *       with the employee's own start, break, resume or end; the state is read after the lock.</li>
 *   <li>Every applied correction writes its audit entry in the same transaction ({@link TimeCorrectionAudit}); a correction that a
 *       business rule refuses (422) writes {@code time.correction_refused} on its own after the rollback.</li>
 * </ul>
 */
@Service
public class TimeCorrectionService {

    /** {@code PUT /api/workdays/{workdayId}/status} body: {@code status} = DAY_ENDED, optional {@code endedAt}, the reason. */
    public record WorkdayStatusInput(String status, String endedAt, String reason) {}

    /**
     * What a correction answers.
     *
     * @param isChanged false when nothing had to change (same times, or the workday had already ended): nothing was written
     * @param workday   the workday afterwards with every interval
     */
    public record CorrectionResult(boolean isChanged, WorkdaySnapshot workday) {}

    /** A work or break interval, whichever table it is stored in. */
    private record EditableInterval(UUID id, SegmentType type, Instant startedAt, Instant endedAt) {}

    /** Oldest first; of two intervals starting at the same instant the one that ended first (a running one last). */
    private static final Comparator<EditableInterval> TIMELINE_ORDER = Comparator.comparing(EditableInterval::startedAt)
            .thenComparing(EditableInterval::endedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    public static final int MINIMUM_REASON_LENGTH = 5;
    private static final String WORKDAY_NOT_FOUND = "Workday not found.";
    private static final String INTERVAL_NOT_FOUND = "Time record not found.";
    private static final String STATUS_DAY_ENDED = "DAY_ENDED";
    private static final java.time.Duration MAXIMUM_GRACE = java.time.Duration.ofHours(12);
    private static final String FUTURE_TIME = "Times cannot be in the future.";

    private final Clock clock;
    private final TeamQueryRepository teamQueryRepository;
    private final EmployeeLockRepository employeeLockRepository;
    private final WorkdayRepository workdayRepository;
    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;
    private final TeamAccessPolicy teamAccessPolicy;
    private final TimeCorrectionAudit timeCorrectionAudit;
    private final TransactionTemplate writeTransaction;

    public TimeCorrectionService(Clock clock, TeamQueryRepository teamQueryRepository, EmployeeLockRepository employeeLockRepository,
            WorkdayRepository workdayRepository, WorkSessionRepository workSessionRepository, BreakSessionRepository breakSessionRepository,
            TeamAccessPolicy teamAccessPolicy, TimeCorrectionAudit timeCorrectionAudit, PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.teamQueryRepository = teamQueryRepository;
        this.employeeLockRepository = employeeLockRepository;
        this.workdayRepository = workdayRepository;
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
        this.teamAccessPolicy = teamAccessPolicy;
        this.timeCorrectionAudit = timeCorrectionAudit;
    }

    /**
     * {@code PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}} (type WORK) and {@code .../break-sessions/{sessionId}}
     * (BREAK): body {@code startedAt?}, {@code endedAt?} (left out: unchanged; {@code null} only for the running interval) and
     * {@code reason}.
     */
    public CorrectionResult adjustInterval(CurrentUser actor, UUID workdayId, SegmentType type, UUID intervalId, PatchBody body) {
        String operation = type == SegmentType.WORK ? "adjust_work_session" : "adjust_break_session";
        return auditRefusals(actor, workdayId, operation, employeeIdHolder -> adjustIntervalInTransaction(actor, workdayId, type, intervalId, body,
                employeeIdHolder));
    }

    private CorrectionResult adjustIntervalInTransaction(CurrentUser actor, UUID workdayId, SegmentType type, UUID intervalId, PatchBody body,
            AtomicReference<UUID> employeeIdHolder) {
        InputValidator validator = new InputValidator();
        Optional<String> startedAtText = body.field("startedAt", String.class);
        Optional<String> endedAtText = body.field("endedAt", String.class);
        Optional<String> reasonText = body.field("reason", String.class);
        String reason = readReason(reasonText == null ? null : reasonText.orElse(null), validator);
        body.throwIfInvalid();
        Instant requestedStart = null;
        if (startedAtText != null) {
            if (startedAtText.isEmpty()) validator.reject("startedAt", "The start time is required.");
            else requestedStart = readInstant("startedAt", startedAtText.get(), validator);
        }
        Instant requestedEnd = endedAtText == null || endedAtText.isEmpty() ? null : readInstant("endedAt", endedAtText.get(), validator);
        validator.throwIfInvalid();

        LockedEmployee employee = lockWorkdayOwner(actor, workdayId, employeeIdHolder);
        Instant now = now();
        Workday workday = workdayRepository.findById(workdayId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, WORKDAY_NOT_FOUND));
        List<WorkSession> workSessions = workSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId);
        List<BreakSession> breakSessions = breakSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId);
        List<EditableInterval> intervals = intervalsOf(workSessions, breakSessions);
        EditableInterval target = intervals.stream().filter(interval -> interval.id().equals(intervalId) && interval.type() == type).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, INTERVAL_NOT_FOUND));

        // times are compared to the millisecond a browser keeps: a time sent back as it was read (milliseconds) is no change, and the
        // stored value (which may have microseconds from older records) is kept
        Instant newStart = requestedStart == null || requestedStart.equals(inMilliseconds(target.startedAt())) ? target.startedAt() : requestedStart;
        Instant newEnd = endedAtText == null || (requestedEnd != null && requestedEnd.equals(inMilliseconds(target.endedAt()))) ? target.endedAt()
                : requestedEnd;
        boolean isRunning = target.endedAt() == null;
        if (isRunning && newEnd != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.INTERVAL_STILL_RUNNING,
                    "This interval is still running. Close the workday instead of setting an end time.");
        }
        if (!isRunning && newEnd == null) rejectField("endedAt", "An end time is required.");
        if (newStart.isAfter(now)) rejectField("startedAt", FUTURE_TIME);
        if (newEnd != null && newEnd.isAfter(now)) rejectField("endedAt", FUTURE_TIME);
        if (newEnd != null && !newEnd.isAfter(newStart)) rejectField("endedAt", "The end time must be after the start time.");
        if (newStart.equals(target.startedAt()) && Objects.equals(newEnd, target.endedAt())) {
            return new CorrectionResult(false, snapshotOf(workday, workSessions, breakSessions, now, employee));
        }

        EditableInterval corrected = new EditableInterval(target.id(), type, newStart, newEnd);
        List<EditableInterval> correctedIntervals = intervals.stream().map(interval -> interval == target ? corrected : interval)
                .sorted(TIMELINE_ORDER).toList();
        requireInsideWindow(workday, corrected, correctedIntervals.get(0) == corrected);
        requireValidTimeline(correctedIntervals, now);
        requireNoOtherWorkdayOverlap(workday, correctedIntervals, now);

        WorkdaySnapshot before = snapshotOf(workday, workSessions, breakSessions, now, employee);
        if (type == SegmentType.WORK) {
            WorkSession session = workSessions.stream().filter(workSession -> workSession.getId().equals(intervalId)).findFirst().orElseThrow();
            session.setStartedAt(newStart);
            session.setEndedAt(newEnd);
            workSessionRepository.saveAndFlush(session);
        } else {
            BreakSession session = breakSessions.stream().filter(breakSession -> breakSession.getId().equals(intervalId)).findFirst().orElseThrow();
            session.setStartedAt(newStart);
            session.setEndedAt(newEnd);
            breakSessionRepository.saveAndFlush(session);
        }
        workday.setStartedAt(correctedIntervals.get(0).startedAt());
        if (workday.getStatus() == WorkdayStatus.COMPLETED) {
            workday.setEndedAt(correctedIntervals.stream().map(EditableInterval::endedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                    .orElse(workday.getEndedAt()));
        }
        recomputeTotals(workday, workSessions, breakSessions);
        WorkdaySnapshot after = snapshotOf(workday, workSessions, breakSessions, now, employee);
        timeCorrectionAudit.recordSessionAdjusted(actor, workday.getEmployeeId(), recordOf(target), recordOf(corrected), before, after, reason);
        return new CorrectionResult(true, after);
    }

    /**
     * {@code PUT /api/workdays/{workdayId}/status} with {@code status = DAY_ENDED}: ends an open (typically forgotten) workday at
     * {@code endedAt}, by default now when an interval is still running (otherwise the end of its last interval). The running work
     * or break interval ends then. A workday that has already ended is left as it is (idempotent, nothing written).
     */
    public CorrectionResult closeWorkday(CurrentUser actor, UUID workdayId, WorkdayStatusInput input) {
        return auditRefusals(actor, workdayId, "close_workday", employeeIdHolder -> closeWorkdayInTransaction(actor, workdayId, input,
                employeeIdHolder));
    }

    private CorrectionResult closeWorkdayInTransaction(CurrentUser actor, UUID workdayId, WorkdayStatusInput input,
            AtomicReference<UUID> employeeIdHolder) {
        InputValidator validator = new InputValidator();
        WorkdayStatusInput body = input == null ? new WorkdayStatusInput(null, null, null) : input;
        if (body.status() == null || !body.status().trim().toUpperCase(Locale.ROOT).equals(STATUS_DAY_ENDED)) {
            validator.reject("status", "The status can only be set to DAY_ENDED.");
        }
        String reason = readReason(body.reason(), validator);
        Instant requestedEnd = body.endedAt() == null || body.endedAt().isBlank() ? null : readInstant("endedAt", body.endedAt(), validator);
        validator.throwIfInvalid();

        LockedEmployee employee = lockWorkdayOwner(actor, workdayId, employeeIdHolder);
        Instant now = now();
        Workday workday = workdayRepository.findById(workdayId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, WORKDAY_NOT_FOUND));
        List<WorkSession> workSessions = workSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId);
        List<BreakSession> breakSessions = breakSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId);
        if (workday.getStatus() == WorkdayStatus.COMPLETED) {
            return new CorrectionResult(false, snapshotOf(workday, workSessions, breakSessions, now, employee));
        }

        List<EditableInterval> intervals = intervalsOf(workSessions, breakSessions);
        boolean hasRunningInterval = intervals.stream().anyMatch(interval -> interval.endedAt() == null);
        Instant lastClosedEnd = intervals.stream().map(EditableInterval::endedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .orElse(null);
        Instant end = requestedEnd != null ? requestedEnd : hasRunningInterval || lastClosedEnd == null ? now : lastClosedEnd;
        if (end.isAfter(now)) rejectField("endedAt", FUTURE_TIME);
        Instant earliestEnd = intervals.stream().map(interval -> interval.endedAt() == null ? interval.startedAt() : interval.endedAt())
                .max(Comparator.naturalOrder()).orElse(workday.getStartedAt());
        if (inMilliseconds(end).isBefore(inMilliseconds(earliestEnd)) || end.isBefore(inMilliseconds(workday.getStartedAt()))) {
            rejectField("endedAt", "The end time cannot be before the last recorded activity.");
        }
        // the end as the client read the last activity (milliseconds) may be a hair before the stored microseconds: the activity wins
        if (end.isBefore(earliestEnd)) end = earliestEnd;
        if (end.isBefore(workday.getStartedAt())) end = workday.getStartedAt();
        requireInsideWindow(workday, end);
        requireNoOtherWorkdayOverlap(workday, workday.getStartedAt(), end, now);

        WorkdaySnapshot before = snapshotOf(workday, workSessions, breakSessions, now, employee);
        List<IntervalRecord> closedIntervals = new ArrayList<>();
        for (WorkSession session : workSessions) {
            if (session.getEndedAt() != null) continue;
            session.setEndedAt(end);
            session.setOpenWorkdayId(null);
            workSessionRepository.saveAndFlush(session);
            closedIntervals.add(new IntervalRecord(session.getId(), SegmentType.WORK, session.getStartedAt(), end));
        }
        for (BreakSession session : breakSessions) {
            if (session.getEndedAt() != null) continue;
            session.setEndedAt(end);
            session.setOpenWorkdayId(null);
            breakSessionRepository.saveAndFlush(session);
            closedIntervals.add(new IntervalRecord(session.getId(), SegmentType.BREAK, session.getStartedAt(), end));
        }
        workday.setStatus(WorkdayStatus.COMPLETED);
        workday.setEndedAt(end);
        workday.setActiveEmployeeId(null);
        recomputeTotals(workday, workSessions, breakSessions);
        WorkdaySnapshot after = snapshotOf(workday, workSessions, breakSessions, now, employee);
        timeCorrectionAudit.recordWorkdayClosed(actor, workday.getEmployeeId(), before, after, closedIntervals, reason);
        return new CorrectionResult(true, after);
    }

    /**
     * Runs one correction in its own transaction; a refusal by a business rule (422) is then audited on its own (the correction's
     * transaction has rolled back and its connection is back in the pool) and answered as before.
     */
    private CorrectionResult auditRefusals(CurrentUser actor, UUID workdayId, String operation,
            Function<AtomicReference<UUID>, CorrectionResult> correction) {
        AtomicReference<UUID> employeeIdHolder = new AtomicReference<>();
        try {
            return writeTransaction.execute(transactionStatus -> correction.apply(employeeIdHolder));
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNPROCESSABLE_ENTITY && employeeIdHolder.get() != null) {
                timeCorrectionAudit.recordRefused(actor, employeeIdHolder.get(), workdayId, operation, e.code());
            }
            throw e;
        }
    }

    /**
     * Finds whose workday it is, checks that the actor may correct it (403 own time, 404 unknown or out of scope) and locks that
     * employee's row until the transaction ends. Nothing of the workday is loaded before the lock.
     */
    private LockedEmployee lockWorkdayOwner(CurrentUser actor, UUID workdayId, AtomicReference<UUID> employeeIdHolder) {
        UUID employeeId = teamQueryRepository.findWorkdayEmployeeId(actor.businessId(), workdayId)
                .orElseThrow(() -> new OutOfScopeException("workday", WORKDAY_NOT_FOUND));
        teamAccessPolicy.requireCanCorrect(actor, employeeId, WORKDAY_NOT_FOUND);
        employeeIdHolder.set(employeeId);
        return employeeLockRepository.lockEmployee(employeeId).orElseThrow(() -> new OutOfScopeException("workday", WORKDAY_NOT_FOUND));
    }

    /**
     * A corrected interval must lie in the workday's window: from the start of its date to the end of the next date, both in the zone
     * the workday was started in (a night shift may run into the next morning), and the first interval of the day must start on the
     * date itself. 422 {@code CORRECTION_OUT_OF_RANGE} otherwise: a year typed wrong must not add years of work, nor move hours into
     * another day.
     */
    private void requireInsideWindow(Workday workday, EditableInterval corrected, boolean isFirstInterval) {
        ZoneId zone = ZoneId.of(workday.getTimeZone());
        Instant windowStart = windowStartOf(workday, zone);
        Instant windowEnd = windowEndOf(workday, zone);
        boolean isStartInside = !corrected.startedAt().isBefore(windowStart) && corrected.startedAt().isBefore(windowEnd);
        boolean isEndInside = corrected.endedAt() == null || (corrected.endedAt().isAfter(windowStart) && !corrected.endedAt().isAfter(windowEnd));
        boolean isFirstOnTheDate = !isFirstInterval || corrected.startedAt().isBefore(workday.getWorkDate().plusDays(1).atStartOfDay(zone).toInstant());
        if (!isStartInside || !isEndInside || !isFirstOnTheDate) throw outOfRange(workday);
    }

    /** The end of a closed workday must lie in the same window. */
    private void requireInsideWindow(Workday workday, Instant end) {
        ZoneId zone = ZoneId.of(workday.getTimeZone());
        Instant windowStart = windowStartOf(workday, zone);
        Instant windowEnd = windowEndOf(workday, zone);
        if (!end.isAfter(windowStart) || end.isAfter(windowEnd)) throw outOfRange(workday);
    }

    /** The start of the shift date, or 12 hours before the scheduled start (the largest grace) when that is earlier. */
    private static Instant windowStartOf(Workday workday, ZoneId zone) {
        Instant startOfDate = workday.getWorkDate().atStartOfDay(zone).toInstant();
        Instant scheduledStart = workday.getScheduledStartAt();
        return scheduledStart != null && !isFullDay(workday) && scheduledStart.minus(MAXIMUM_GRACE).isBefore(startOfDate) ? scheduledStart.minus(MAXIMUM_GRACE) : startOfDate;
    }

    /** The end of the day after the shift date, or the scheduled end plus 12 hours when that is later. */
    private static Instant windowEndOf(Workday workday, ZoneId zone) {
        Instant endOfNextDate = workday.getWorkDate().plusDays(2).atStartOfDay(zone).toInstant();
        Instant scheduledEnd = workday.getScheduledEndAt();
        return scheduledEnd != null && !isFullDay(workday) && scheduledEnd.plus(MAXIMUM_GRACE).isAfter(endOfNextDate) ? scheduledEnd.plus(MAXIMUM_GRACE) : endOfNextDate;
    }

    /** The default calendar-day shift (a 23 to 25 hour occurrence) keeps the plain date window. */
    private static boolean isFullDay(Workday workday) {
        return workday.getScheduledStartAt() != null && workday.getScheduledEndAt() != null
                && java.time.Duration.between(workday.getScheduledStartAt(), workday.getScheduledEndAt()).toHours() >= 23;
    }

    private static ApiException outOfRange(Workday workday) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.CORRECTION_OUT_OF_RANGE,
                "Choose a time on " + workday.getWorkDate() + " or " + workday.getWorkDate().plusDays(1)
                        + ", in the business time zone. To close a forgotten shift, give the time it really ended.");
    }

    /** The corrected workday (first start to last end, or until now while it runs) must not overlap another workday of the person. */
    private void requireNoOtherWorkdayOverlap(Workday workday, List<EditableInterval> correctedIntervals, Instant now) {
        Instant from = correctedIntervals.get(0).startedAt();
        boolean isOpen = workday.getStatus() != WorkdayStatus.COMPLETED || correctedIntervals.stream().anyMatch(interval -> interval.endedAt() == null);
        Instant until = isOpen ? now : correctedIntervals.stream().map(EditableInterval::endedAt).max(Comparator.naturalOrder()).orElse(from);
        requireNoOtherWorkdayOverlap(workday, from, until, now);
    }

    private void requireNoOtherWorkdayOverlap(Workday workday, Instant from, Instant until, Instant now) {
        if (teamQueryRepository.existsOverlappingWorkday(workday.getEmployeeId(), workday.getId(), from, until, now)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.WORKDAY_OVERLAP,
                    "The corrected time overlaps another workday of this person. Their hours would count twice.");
        }
    }

    private static Instant inMilliseconds(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * The corrected intervals (oldest first) must not overlap (a running one lasts until now, so only the latest may run) and the
     * first must be work.
     */
    private static void requireValidTimeline(List<EditableInterval> sortedIntervals, Instant now) {
        for (int i = 0; i + 1 < sortedIntervals.size(); i++) {
            EditableInterval current = sortedIntervals.get(i);
            EditableInterval next = sortedIntervals.get(i + 1);
            Instant currentEnd = current.endedAt() == null ? now : current.endedAt();
            if (current.endedAt() == null || next.startedAt().isBefore(currentEnd)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.TIME_INTERVAL_OVERLAP,
                        "The corrected interval overlaps another work or break interval.");
            }
        }
        if (!sortedIntervals.isEmpty() && sortedIntervals.get(0).type() != SegmentType.WORK) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.WORKDAY_MUST_START_WITH_WORK,
                    "A workday must begin with a work interval.");
        }
    }

    /** The cached totals from the intervals: set once the day is completed, empty while it is open (as the time events keep them). */
    private void recomputeTotals(Workday workday, List<WorkSession> workSessions, List<BreakSession> breakSessions) {
        if (workday.getStatus() != WorkdayStatus.COMPLETED || workday.getEndedAt() == null) {
            workday.setProductiveSeconds(null);
            workday.setBreakSeconds(null);
            workday.setBreakCount(null);
            workday.setExtraSeconds(null);
        } else {
            WorkdayTotals totals = IntervalMath.computeTotals(new TimeInterval(workday.getStartedAt(), workday.getEndedAt()),
                    workSessions.stream().map(session -> new TimeInterval(session.getStartedAt(), session.getEndedAt())).toList(),
                    breakSessions.stream().map(session -> new TimeInterval(session.getStartedAt(), session.getEndedAt())).toList(),
                    workday.getEndedAt());
            workday.setProductiveSeconds(Math.toIntExact(totals.productiveSeconds()));
            workday.setBreakSeconds(Math.toIntExact(totals.breakSeconds()));
            workday.setBreakCount(totals.breakCount());
            workday.setExtraSeconds(Math.toIntExact(WorkdayTimeline.extraSeconds(WorkdaySnapshots.shiftOf(workday), workday.isOffDay(),
                    workSessions.stream().map(session -> new TimeInterval(session.getStartedAt(), session.getEndedAt())).toList(), workday.getEndedAt())));
        }
        workdayRepository.saveAndFlush(workday);
    }

    private static WorkdaySnapshot snapshotOf(Workday workday, List<WorkSession> workSessions, List<BreakSession> breakSessions, Instant now,
            LockedEmployee employee) {
        List<WorkSession> orderedWorkSessions = workSessions.stream().sorted(Comparator.comparing(WorkSession::getStartedAt)).toList();
        List<BreakSession> orderedBreakSessions = breakSessions.stream().sorted(Comparator.comparing(BreakSession::getStartedAt)).toList();
        return WorkdaySnapshots.snapshotOf(workday, orderedWorkSessions, orderedBreakSessions, now, employee.staleWorkdayHours());
    }

    private static List<EditableInterval> intervalsOf(List<WorkSession> workSessions, List<BreakSession> breakSessions) {
        List<EditableInterval> intervals = new ArrayList<>();
        workSessions.forEach(session -> intervals.add(new EditableInterval(session.getId(), SegmentType.WORK, session.getStartedAt(),
                session.getEndedAt())));
        breakSessions.forEach(session -> intervals.add(new EditableInterval(session.getId(), SegmentType.BREAK, session.getStartedAt(),
                session.getEndedAt())));
        return intervals;
    }

    private static IntervalRecord recordOf(EditableInterval interval) {
        return new IntervalRecord(interval.id(), interval.type(), interval.startedAt(), interval.endedAt());
    }

    /** The mandatory reason, trimmed, 5 to 500 characters. */
    private static String readReason(String rawReason, InputValidator validator) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MINIMUM_REASON_LENGTH) {
            validator.reject("reason", "Give a reason of at least " + MINIMUM_REASON_LENGTH + " characters.");
            return null;
        }
        if (reason.length() > InputValidator.MAXIMUM_REASON_LENGTH) {
            validator.reject("reason", "The reason must be at most " + InputValidator.MAXIMUM_REASON_LENGTH + " characters.");
            return null;
        }
        return reason;
    }

    /** An ISO-8601 instant with {@code Z} or an offset ({@code 2026-10-22T09:30:00Z}), to the millisecond (what the browser keeps). */
    private static Instant readInstant(String field, String text, InputValidator validator) {
        String trimmedText = text.trim();
        try {
            return Instant.parse(trimmedText).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(trimmedText).toInstant().truncatedTo(ChronoUnit.MILLIS);
            } catch (DateTimeParseException offsetException) {
                validator.reject(field, "Send the time as an ISO-8601 instant, for example 2026-10-22T09:30:00Z.");
                return null;
            }
        }
    }

    private static void rejectField(String field, String message) {
        InputValidator validator = new InputValidator();
        validator.reject(field, message);
        validator.throwIfInvalid();
    }

    /** Now, to the millisecond. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}

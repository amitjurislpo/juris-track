package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.engine.BusinessCalendar;
import com.jurislpo.juristrack.engine.IntervalMath;
import com.jurislpo.juristrack.engine.TimeEventType;
import com.jurislpo.juristrack.engine.TimeInterval;
import com.jurislpo.juristrack.engine.TimeStateMachine;
import com.jurislpo.juristrack.engine.TimeTransition;
import com.jurislpo.juristrack.engine.TrackingStatus;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.engine.WorkdayTotals;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.BreakSession;
import com.jurislpo.juristrack.entity.WorkSession;
import com.jurislpo.juristrack.entity.Workday;
import com.jurislpo.juristrack.entity.WorkdayStatus;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BreakSessionRepository;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository;
import com.jurislpo.juristrack.repository.EmployeeLockRepository.LockedEmployee;
import com.jurislpo.juristrack.repository.WorkSessionRepository;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The signed-in user's own time tracking: the current state and the events start, break, resume and end (state machine:
 * {@link TimeStateMachine}). Ported from the old app's {@code time-tracking.service.ts}.
 * <ul>
 *   <li>The employee is always the signed-in user, never an id from the request.</li>
 *   <li>Every event runs in one transaction that first locks the employee's row ({@link EmployeeLockRepository}), so requests of
 *       the same employee are serialized while others are never blocked; the state is read and the transition decided inside the
 *       lock, and "now" is read from the injected {@link Clock} only after the lock was taken.</li>
 *   <li>A repeated event (a double-click) answers the current state with {@code alreadyApplied = true} and creates nothing. The
 *       unique "open key" columns make a second open workday or interval impossible even if this code were bypassed; a unique-key
 *       collision from a concurrent request is reported the same way.</li>
 *   <li>A workday belongs to the calendar date in the business time zone when it started and may run past midnight. Starting again
 *       after the day ended on the same date reopens that workday with a new work session; the gap counts as neither productive nor
 *       break time (owner decision D5).</li>
 *   <li>The cached totals on the workday are recomputed from the intervals after every change: values once the day is completed,
 *       empty while it is open. Never incremented.</li>
 *   <li>The manager at the start of the day is kept on the workday ({@code manager_id}, the reporting snapshot).</li>
 *   <li>Every applied event is audited in its transaction ({@code time.*}); an event refused because the open workday's intervals are
 *       inconsistent is audited as {@code time.event_failed} (outcome FAILED). Reading the own state is not audited (it is polled).</li>
 * </ul>
 */
@Service
public class TimeTrackingService {

    /** {@code POST /api/me/time-events} body: {@code type} = START, BREAK, RESUME or END (any case). */
    public record TimeEventInput(String type) {}

    /**
     * The signed-in user's time state.
     *
     * @param employeeId        the signed-in user
     * @param serverTime        the server's "now" for this answer (clients measure their clock offset from it)
     * @param today             today's date in the business time zone
     * @param businessTimeZone  the business time zone (IANA id)
     * @param staleWorkdayHours an open workday longer than this is flagged "not ended" ({@code workday.isStale})
     * @param displayTimeZones  the business's configured display time zones (IANA ids) for the time-zone switcher; the business
     *                          time zone itself is always offered by the client
     * @param status            NOT_STARTED, WORKING, ON_BREAK or DAY_ENDED
     * @param workday           the open workday (from any date), else today's ended workday, else null
     */
    public record TimeState(UUID employeeId, Instant serverTime, LocalDate today, String businessTimeZone, int staleWorkdayHours,
            List<String> displayTimeZones, TrackingStatus status, WorkdaySnapshot workday) {}

    /**
     * What a time event answers.
     *
     * @param type           the event
     * @param alreadyApplied true when it was a repeat and nothing was created or changed
     * @param workdayId      the workday the event applied to (for a repeat: the current one, null when there is none). It differs from
     *                       {@code state.workday} when a night shift ended after midnight: the state then shows today (not started)
     *                       and the ended day is read with {@code GET /api/workdays/{workdayId}}
     * @param state          the state afterwards
     */
    public record TimeEventResult(TimeEventType type, boolean alreadyApplied, UUID workdayId, TimeState state) {}

    /** What one event did inside its transaction. */
    private record AppliedEvent(boolean isAlreadyApplied, UUID workdayId) {}

    private static final String WORKDAY_ENTITY = "workday";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String DEACTIVATION_REASON = "Employee deactivated.";

    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final EmployeeLockRepository employeeLockRepository;
    private final WorkdayRepository workdayRepository;
    private final WorkSessionRepository workSessionRepository;
    private final BreakSessionRepository breakSessionRepository;
    private final BusinessRepository businessRepository;
    private final WorkdaySnapshots workdaySnapshots;
    private final AuditService auditService;

    public TimeTrackingService(Clock clock, PlatformTransactionManager transactionManager, EmployeeLockRepository employeeLockRepository,
            WorkdayRepository workdayRepository, WorkSessionRepository workSessionRepository, BreakSessionRepository breakSessionRepository,
            BusinessRepository businessRepository, WorkdaySnapshots workdaySnapshots, AuditService auditService) {
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.employeeLockRepository = employeeLockRepository;
        this.workdayRepository = workdayRepository;
        this.workSessionRepository = workSessionRepository;
        this.breakSessionRepository = breakSessionRepository;
        this.businessRepository = businessRepository;
        this.workdaySnapshots = workdaySnapshots;
        this.auditService = auditService;
    }

    /**
     * The current state: the open workday if there is one (even one started on an earlier date), otherwise today's ended workday,
     * otherwise NOT_STARTED without a workday.
     */
    public TimeState currentState(CurrentUser currentUser) {
        return readTransaction.execute(transactionStatus -> loadState(currentUser.userId(), currentUser.businessId()));
    }

    /**
     * Applies one event for the signed-in user and answers the state afterwards. 400 for an unknown type; 403 when the account was
     * deactivated meanwhile; 409 {@code WORKDAY_NOT_STARTED}, {@code NOT_ON_BREAK} or {@code BREAK_IN_PROGRESS} for an event the
     * current state does not allow.
     */
    public TimeEventResult recordEvent(CurrentUser currentUser, TimeEventInput input) {
        TimeEventType type = TimeEventType.fromName(input == null ? null : input.type()).orElse(null);
        if (type == null) {
            InputValidator validator = new InputValidator();
            validator.reject("type", "The type is START, BREAK, RESUME or END.");
            validator.throwIfInvalid();
        }
        AppliedEvent appliedEvent;
        try {
            appliedEvent = writeTransaction.execute(transactionStatus -> applyEvent(currentUser.userId(), type));
        } catch (DataIntegrityViolationException e) {
            // a concurrent request opened the record first: report the resulting state instead of failing
            if (!isUniqueViolation(e)) throw e;
            appliedEvent = new AppliedEvent(true, null);
        } catch (ApiException e) {
            if (Problems.TIME_RECORD_INCONSISTENT.equals(e.code())) {
                // the event's transaction rolled back: the failure is recorded on its own
                auditService.recordIndependently(new AuditEntry(currentUser.businessId(), currentUser.userId(), currentUser.userId(),
                        AuditAction.TIME_EVENT_FAILED, WORKDAY_ENTITY, null, null, Map.of("type", type.name()), e.code()));
            }
            throw e;
        }
        TimeState state = currentState(currentUser);
        UUID workdayId = appliedEvent.workdayId() != null ? appliedEvent.workdayId() : state.workday() == null ? null : state.workday().id();
        return new TimeEventResult(type, appliedEvent.isAlreadyApplied(), workdayId, state);
    }

    /**
     * Closes the employee's open workday at the current time because the employee is being deactivated: the open work or break
     * session ends now, the day is COMPLETED and its totals are recomputed, with a {@code workday.closed} audit entry (the reason
     * given for the deactivation, else "Employee deactivated."). Runs inside the deactivation's transaction and takes the employee's
     * lock first. Returns the closed workday's id, or empty when no workday was open.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> closeOpenWorkdayOfDeactivatedEmployee(UUID businessId, UUID actorId, UUID employeeId, String reason) {
        employeeLockRepository.lockEmployee(employeeId);
        Optional<Workday> openWorkday = workdayRepository.findByActiveEmployeeId(employeeId);
        if (openWorkday.isEmpty()) return Optional.empty();
        Workday workday = openWorkday.get();
        Instant now = now();
        WorkdayStatus statusBefore = workday.getStatus();
        findOpenWorkSession(workday.getId()).ifPresent(session -> closeWorkSession(session, now));
        findOpenBreakSession(workday.getId()).ifPresent(session -> closeBreakSession(session, now));
        completeWorkday(workday, now);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", statusBefore.name());
        before.put("endedAt", null);
        auditService.record(new AuditEntry(businessId, actorId, employeeId, AuditAction.WORKDAY_CLOSED, WORKDAY_ENTITY, workday.getId().toString(),
                before, Map.of("status", WorkdayStatus.COMPLETED.name(), "endedAt", now.toString()), reason == null ? DEACTIVATION_REASON : reason));
        return Optional.of(workday.getId());
    }

    private TimeState loadState(UUID employeeId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Business not found."));
        Instant now = now();
        LocalDate today = BusinessCalendar.toDateKey(now, BusinessCalendar.zoneOf(business.getTimeZone()));
        Optional<Workday> workday = workdayRepository.findByActiveEmployeeId(employeeId)
                .or(() -> workdayRepository.findByEmployeeIdAndWorkDate(employeeId, today));
        WorkdaySnapshot snapshot = workday.map(day -> workdaySnapshots.snapshotOf(day, now, business.getStaleWorkdayHours())).orElse(null);
        return new TimeState(employeeId, now, today, business.getTimeZone(), business.getStaleWorkdayHours(),
                List.copyOf(business.getDisplayTimeZones()), snapshot == null ? TrackingStatus.NOT_STARTED : snapshot.status(), snapshot);
    }

    /** One event inside the write transaction. */
    private AppliedEvent applyEvent(UUID employeeId, TimeEventType type) {
        LockedEmployee employee = employeeLockRepository.lockEmployee(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Employee not found."));
        if (!employee.isActive()) throw new ApiException(HttpStatus.FORBIDDEN, "This account is deactivated.");
        Instant now = now();
        ZoneId zone = BusinessCalendar.zoneOf(employee.businessTimeZone());
        LocalDate today = BusinessCalendar.toDateKey(now, zone);
        Optional<Workday> openWorkday = workdayRepository.findByActiveEmployeeId(employeeId);
        Optional<Workday> endedWorkdayToday = openWorkday.isPresent() ? Optional.empty()
                : workdayRepository.findByEmployeeIdAndWorkDate(employeeId, today);
        TrackingStatus status = openWorkday.map(workday -> WorkdaySnapshots.statusOf(workday.getStatus()))
                .orElse(endedWorkdayToday.isPresent() ? TrackingStatus.DAY_ENDED : TrackingStatus.NOT_STARTED);

        TimeTransition transition = TimeStateMachine.decide(status, type);
        Workday workday;
        AuditAction auditAction;
        switch (transition) {
            case ALREADY_APPLIED -> {
                return new AppliedEvent(true, openWorkday.or(() -> endedWorkdayToday).map(Workday::getId).orElse(null));
            }
            case REJECTED_WORKDAY_NOT_STARTED -> throw new ApiException(HttpStatus.CONFLICT, Problems.WORKDAY_NOT_STARTED,
                    type == TimeEventType.BREAK ? "Start your workday before taking a break." : "You have not started a workday.");
            case REJECTED_NOT_ON_BREAK -> throw new ApiException(HttpStatus.CONFLICT, Problems.NOT_ON_BREAK, "You are not on a break.");
            case REJECTED_BREAK_IN_PROGRESS -> throw new ApiException(HttpStatus.CONFLICT, Problems.BREAK_IN_PROGRESS,
                    "You are on a break. Select Back to Work before ending your workday.");
            case START_WORKDAY -> {
                workday = startWorkday(employee, zone, today, now);
                auditAction = AuditAction.WORKDAY_STARTED;
            }
            case REOPEN_WORKDAY -> {
                workday = reopenWorkday(endedWorkdayToday.orElseThrow(), now);
                auditAction = AuditAction.WORKDAY_REOPENED;
            }
            case START_BREAK -> {
                workday = startBreak(openWorkday.orElseThrow(), now);
                auditAction = AuditAction.BREAK_STARTED;
            }
            case RESUME_WORK -> {
                workday = resumeWork(openWorkday.orElseThrow(), now);
                auditAction = AuditAction.WORK_RESUMED;
            }
            case END_WORKDAY -> {
                workday = endWorkday(openWorkday.orElseThrow(), now);
                auditAction = AuditAction.WORKDAY_ENDED;
            }
            default -> throw new IllegalStateException("Unhandled transition " + transition);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", WorkdaySnapshots.statusOf(workday.getStatus()).name());
        after.put("at", now.toString());
        auditService.record(new AuditEntry(employee.businessId(), employeeId, employeeId, auditAction, WORKDAY_ENTITY, workday.getId().toString(),
                Map.of("status", status.name()), after, null));
        return new AppliedEvent(false, workday.getId());
    }

    private Workday startWorkday(LockedEmployee employee, ZoneId zone, LocalDate today, Instant now) {
        Workday workday = new Workday();
        workday.setBusinessId(employee.businessId());
        workday.setEmployeeId(employee.employeeId());
        workday.setManagerId(employee.managerId());
        workday.setWorkDate(today);
        workday.setTimeZone(zone.getId());
        workday.setStartedAt(now);
        workday.setStatus(WorkdayStatus.WORKING);
        workday.setActiveEmployeeId(employee.employeeId());
        workdayRepository.saveAndFlush(workday);
        openWorkSession(workday, now);
        return workday;
    }

    /** Today's ended workday runs again with a new work session; the gap since it ended is neither productive nor break time. */
    private Workday reopenWorkday(Workday workday, Instant now) {
        workday.setStatus(WorkdayStatus.WORKING);
        workday.setEndedAt(null);
        workday.setActiveEmployeeId(workday.getEmployeeId());
        workdayRepository.saveAndFlush(workday);
        openWorkSession(workday, now);
        recomputeTotals(workday);
        return workday;
    }

    private Workday startBreak(Workday workday, Instant now) {
        WorkSession openWorkSession = requireConsistentOpenIntervals(workday).workSession();
        closeWorkSession(openWorkSession, now);
        BreakSession breakSession = new BreakSession();
        breakSession.setBusinessId(workday.getBusinessId());
        breakSession.setEmployeeId(workday.getEmployeeId());
        breakSession.setWorkdayId(workday.getId());
        breakSession.setStartedAt(now);
        breakSession.setOpenWorkdayId(workday.getId());
        breakSessionRepository.saveAndFlush(breakSession);
        workday.setStatus(WorkdayStatus.ON_BREAK);
        workdayRepository.saveAndFlush(workday);
        recomputeTotals(workday);
        return workday;
    }

    private Workday resumeWork(Workday workday, Instant now) {
        BreakSession openBreakSession = requireConsistentOpenIntervals(workday).breakSession();
        closeBreakSession(openBreakSession, now);
        openWorkSession(workday, now);
        workday.setStatus(WorkdayStatus.WORKING);
        workdayRepository.saveAndFlush(workday);
        recomputeTotals(workday);
        return workday;
    }

    private Workday endWorkday(Workday workday, Instant now) {
        closeWorkSession(requireConsistentOpenIntervals(workday).workSession(), now);
        completeWorkday(workday, now);
        return workday;
    }

    private void completeWorkday(Workday workday, Instant now) {
        workday.setStatus(WorkdayStatus.COMPLETED);
        workday.setEndedAt(now);
        workday.setActiveEmployeeId(null);
        workdayRepository.saveAndFlush(workday);
        recomputeTotals(workday);
    }

    private void openWorkSession(Workday workday, Instant now) {
        WorkSession workSession = new WorkSession();
        workSession.setBusinessId(workday.getBusinessId());
        workSession.setEmployeeId(workday.getEmployeeId());
        workSession.setWorkdayId(workday.getId());
        workSession.setStartedAt(now);
        workSession.setOpenWorkdayId(workday.getId());
        workSessionRepository.saveAndFlush(workSession);
    }

    private void closeWorkSession(WorkSession workSession, Instant now) {
        workSession.setEndedAt(now);
        workSession.setOpenWorkdayId(null);
        workSessionRepository.saveAndFlush(workSession);
    }

    private void closeBreakSession(BreakSession breakSession, Instant now) {
        breakSession.setEndedAt(now);
        breakSession.setOpenWorkdayId(null);
        breakSessionRepository.saveAndFlush(breakSession);
    }

    private Optional<WorkSession> findOpenWorkSession(UUID workdayId) {
        return workSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId).stream().filter(session -> session.getEndedAt() == null).findFirst();
    }

    private Optional<BreakSession> findOpenBreakSession(UUID workdayId) {
        return breakSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workdayId).stream().filter(session -> session.getEndedAt() == null).findFirst();
    }

    /** The open intervals of an open workday. */
    private record OpenIntervals(WorkSession workSession, BreakSession breakSession) {}

    /**
     * The invariant before changing an open workday: WORKING has exactly an open work session, ON_BREAK exactly an open break.
     * Anything else needs an administrator's correction (500 {@code TIME_RECORD_INCONSISTENT}).
     */
    private OpenIntervals requireConsistentOpenIntervals(Workday workday) {
        WorkSession openWorkSession = findOpenWorkSession(workday.getId()).orElse(null);
        BreakSession openBreakSession = findOpenBreakSession(workday.getId()).orElse(null);
        boolean isConsistent = switch (workday.getStatus()) {
            case WORKING -> openWorkSession != null && openBreakSession == null;
            case ON_BREAK -> openBreakSession != null && openWorkSession == null;
            case COMPLETED -> false;
        };
        if (!isConsistent) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, Problems.TIME_RECORD_INCONSISTENT,
                    "Your time record is in an unexpected state. Please contact an administrator to review it.");
        }
        return new OpenIntervals(openWorkSession, openBreakSession);
    }

    /** The cached totals from the intervals: set once the day is completed, empty while it is open (old recomputeWorkdayTotals). */
    private void recomputeTotals(Workday workday) {
        if (workday.getStatus() != WorkdayStatus.COMPLETED || workday.getEndedAt() == null) {
            workday.setProductiveSeconds(null);
            workday.setBreakSeconds(null);
            workday.setBreakCount(null);
        } else {
            List<TimeInterval> workIntervals = workSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workday.getId()).stream()
                    .map(session -> new TimeInterval(session.getStartedAt(), session.getEndedAt())).toList();
            List<TimeInterval> breakIntervals = breakSessionRepository.findByWorkdayIdOrderByStartedAtAsc(workday.getId()).stream()
                    .map(session -> new TimeInterval(session.getStartedAt(), session.getEndedAt())).toList();
            WorkdayTotals totals = IntervalMath.computeTotals(new TimeInterval(workday.getStartedAt(), workday.getEndedAt()), workIntervals,
                    breakIntervals, workday.getEndedAt());
            workday.setProductiveSeconds(Math.toIntExact(totals.productiveSeconds()));
            workday.setBreakSeconds(Math.toIntExact(totals.breakSeconds()));
            workday.setBreakCount(totals.breakCount());
        }
        workdayRepository.saveAndFlush(workday);
    }

    /** Now, to the microsecond the database stores. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && UNIQUE_VIOLATION.equals(sqlException.getSQLState())) return true;
        }
        return false;
    }
}

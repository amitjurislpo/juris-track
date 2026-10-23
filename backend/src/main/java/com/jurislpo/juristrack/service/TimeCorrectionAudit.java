package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.engine.SegmentType;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries of time corrections (actor = the manager or owner, subject = the employee, the interval and the workday's totals
 * before and after, the reason). Applied corrections are written in the correction's transaction (category {@code time}, outcome
 * SUCCESS); a refusal by a business rule is written on its own after that transaction rolled back (outcome DENIED). The request
 * context (source, IP, request id, path) is filled in by {@link AuditService}.
 */
@Component
class TimeCorrectionAudit {

    /** One interval as the audit entry records it. */
    record IntervalRecord(UUID id, SegmentType type, Instant startedAt, Instant endedAt) {}

    private static final String WORKDAY_ENTITY = "workday";

    private final AuditService auditService;

    TimeCorrectionAudit(AuditService auditService) {
        this.auditService = auditService;
    }

    /** A work or break interval was moved: {@code time.session_adjusted}, entity {@code work_session} or {@code break_session}. */
    void recordSessionAdjusted(CurrentUser actor, UUID employeeId, IntervalRecord intervalBefore, IntervalRecord intervalAfter,
            WorkdaySnapshot workdayBefore, WorkdaySnapshot workdayAfter, String reason) {
        String entityType = intervalBefore.type() == SegmentType.WORK ? "work_session" : "break_session";
        auditService.record(new AuditEntry(actor.businessId(), actor.userId(), employeeId, AuditAction.TIME_SESSION_ADJUSTED, entityType,
                intervalBefore.id().toString(), changeOf(intervalBefore, workdayBefore), changeOf(intervalAfter, workdayAfter), reason));
    }

    /** An open workday was ended: {@code time.workday_closed}, entity {@code workday}, with the intervals that were closed. */
    void recordWorkdayClosed(CurrentUser actor, UUID employeeId, WorkdaySnapshot workdayBefore, WorkdaySnapshot workdayAfter,
            List<IntervalRecord> closedIntervals, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("workday", workdayOf(workdayAfter));
        after.put("closedIntervals", closedIntervals);
        auditService.record(new AuditEntry(actor.businessId(), actor.userId(), employeeId, AuditAction.TIME_WORKDAY_CLOSED, WORKDAY_ENTITY,
                workdayAfter.id().toString(), Map.of("workday", workdayOf(workdayBefore)), after, reason));
    }

    /**
     * A business rule refused a correction (422): {@code time.correction_refused} (outcome DENIED, the code as reason), written in a
     * transaction of its own after the correction's transaction rolled back, so it stays. Called outside any transaction.
     */
    void recordRefused(CurrentUser actor, UUID employeeId, UUID workdayId, String operation, String code) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operation", operation);
        details.put("code", code);
        auditService.recordIndependently(new AuditEntry(actor.businessId(), actor.userId(), employeeId, AuditAction.TIME_CORRECTION_REFUSED,
                WORKDAY_ENTITY, workdayId.toString(), null, details, code));
    }

    private static Map<String, Object> changeOf(IntervalRecord interval, WorkdaySnapshot workday) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("interval", interval);
        change.put("workday", workdayOf(workday));
        return change;
    }

    /** The workday's boundaries and totals (an open day's totals as of the correction). */
    private static Map<String, Object> workdayOf(WorkdaySnapshot workday) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", workday.id());
        summary.put("date", workday.date().toString());
        summary.put("status", workday.status().name());
        summary.put("startedAt", workday.startedAt());
        summary.put("endedAt", workday.endedAt());
        summary.put("productiveSeconds", workday.productiveSeconds());
        summary.put("breakSeconds", workday.breakSeconds());
        summary.put("spanSeconds", workday.spanSeconds());
        summary.put("breakCount", workday.breakCount());
        return summary;
    }
}

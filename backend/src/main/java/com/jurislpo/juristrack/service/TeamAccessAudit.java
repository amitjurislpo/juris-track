package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.WorkdayHistoryService.WorkdayHistory;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries for reading somebody else's time data (audit policy, "Access to other people's data": who looked at whom and
 * which range), one per request, through {@link AuditService#recordAccess} (category {@code access}, outcome SUCCESS, the request
 * context filled in by the audit service; nothing is written when the viewer reads their own data). The live board itself is not
 * recorded: it refreshes every few seconds and shows only today's state of the whole scope.
 */
@Component
class TeamAccessAudit {

    private static final String USER_ENTITY = "user";
    private static final String WORKDAY_ENTITY = "workday";

    private final AuditService auditService;

    TeamAccessAudit(AuditService auditService) {
        this.auditService = auditService;
    }

    /** {@code GET /api/v1/team/members/{employeeId}}: one person's live state. */
    void recordMemberViewed(CurrentUser viewer, UUID employeeId) {
        auditService.recordAccess(AuditAction.TEAM_MEMBER_VIEWED, viewer, employeeId, USER_ENTITY, employeeId.toString(), null);
    }

    /** {@code GET /api/v1/team/members/{employeeId}/workdays}: one person's history for a date range. */
    void recordMemberWorkdaysViewed(CurrentUser viewer, UUID employeeId, WorkdayHistory history) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("range", history.range());
        details.put("from", history.from().toString());
        details.put("to", history.to().toString());
        auditService.recordAccess(AuditAction.TEAM_MEMBER_WORKDAYS_VIEWED, viewer, employeeId, USER_ENTITY, employeeId.toString(), details);
    }

    /** {@code GET /api/v1/workdays/{workdayId}} of somebody else. */
    void recordWorkdayViewed(CurrentUser viewer, UUID employeeId, UUID workdayId, LocalDate date) {
        auditService.recordAccess(AuditAction.WORKDAY_VIEWED, viewer, employeeId, WORKDAY_ENTITY, workdayId.toString(),
                Map.of("date", date.toString()));
    }
}

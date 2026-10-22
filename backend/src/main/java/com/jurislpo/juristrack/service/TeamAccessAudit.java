package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import com.jurislpo.juristrack.service.WorkdayHistoryService.WorkdayHistory;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries for reading somebody else's time data (audit policy, "Access to other people's data": who looked at whom and
 * which range). Every such entry is written here, so the audit module can adapt them in one place. Called inside the read's
 * transaction (which must not be read-only). The live board itself is not recorded: it refreshes every few seconds and shows
 * only today's state of the whole scope.
 */
@Component
class TeamAccessAudit {

    private static final String USER_ENTITY = "user";
    private static final String WORKDAY_ENTITY = "workday";

    private final AuditService auditService;

    TeamAccessAudit(AuditService auditService) {
        this.auditService = auditService;
    }

    /** {@code GET /api/team/members/{employeeId}}: one person's live state. */
    void recordMemberViewed(CurrentUser viewer, UUID employeeId) {
        auditService.record(new AuditEntry(viewer.businessId(), viewer.userId(), employeeId, AuditAction.TEAM_MEMBER_VIEWED, USER_ENTITY,
                employeeId.toString(), null, null, null));
    }

    /** {@code GET /api/team/members/{employeeId}/workdays}: one person's history for a date range. */
    void recordMemberWorkdaysViewed(CurrentUser viewer, UUID employeeId, WorkdayHistory history) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("range", history.range());
        after.put("from", history.from().toString());
        after.put("to", history.to().toString());
        auditService.record(new AuditEntry(viewer.businessId(), viewer.userId(), employeeId, AuditAction.TEAM_MEMBER_WORKDAYS_VIEWED,
                USER_ENTITY, employeeId.toString(), null, after, null));
    }

    /** {@code GET /api/workdays/{workdayId}} of somebody else. */
    void recordWorkdayViewed(CurrentUser viewer, UUID employeeId, UUID workdayId, LocalDate date) {
        auditService.record(new AuditEntry(viewer.businessId(), viewer.userId(), employeeId, AuditAction.WORKDAY_VIEWED, WORKDAY_ENTITY,
                workdayId.toString(), null, Map.of("date", date.toString()), null));
    }
}

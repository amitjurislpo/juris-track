package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries of the reports (audit policy, "access to other people's data"): one entry per report request, never one per
 * row, through {@link AuditService#recordAccess} (category {@code access} from the action, outcome SUCCESS, the request context
 * filled in by the audit service). The only place the reports touch the audit log.
 *
 * <p>Entry: actor = the caller, subject = the employee filter (null when the report covers several people), entity {@code report}
 * with the report's name as its id, {@code after = { report, filters, rowCount, ... }}. A report filtered on the caller themselves
 * is reading one's own data and is not recorded.
 */
@Component
public class ReportAccessAudit {

    private static final String ENTITY_TYPE = "report";

    private final AuditService auditService;

    public ReportAccessAudit(AuditService auditService) {
        this.auditService = auditService;
    }

    /** A report was read ({@code reports.viewed}). */
    public void recordView(CurrentUser currentUser, String reportName, Map<String, Object> filters, UUID subjectId, int rowCount) {
        auditService.recordAccess(AuditAction.REPORT_VIEWED, currentUser, subjectId, ENTITY_TYPE, reportName, details(reportName, filters, rowCount));
    }

    /** The workday report was downloaded as CSV ({@code reports.exported}). */
    public void recordExport(CurrentUser currentUser, String reportName, Map<String, Object> filters, UUID subjectId, int rowCount,
            String timeZone, String fileName) {
        Map<String, Object> details = details(reportName, filters, rowCount);
        details.put("timeZone", timeZone);
        details.put("fileName", fileName);
        auditService.recordAccess(AuditAction.REPORT_EXPORTED, currentUser, subjectId, ENTITY_TYPE, reportName, details);
    }

    private static Map<String, Object> details(String reportName, Map<String, Object> filters, int rowCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("report", reportName);
        details.put("filters", filters);
        details.put("rowCount", rowCount);
        return details;
    }
}

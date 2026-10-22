package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit entries of the reports (audit policy, "access to other people's data"): one entry per report request, never one per
 * row, written in the request's transaction through {@link AuditService}. The only place the reports touch the audit log, so the
 * audit module can change how these entries are stored (for example a category column instead of {@code after.category}) here alone.
 *
 * <p>Entry: actor = the caller, subject = the employee filter (null when the report covers several people), entity {@code report}
 * with the report's name as its id, {@code after = { category, report, filters, rowCount, ... }}.
 */
@Component
public class ReportAccessAudit {

    /** Reading other people's data. */
    public static final String ACCESS_CATEGORY = "access";
    /** Taking other people's data out of the application. */
    public static final String EXPORT_CATEGORY = "export";
    private static final String ENTITY_TYPE = "report";

    private final AuditService auditService;

    public ReportAccessAudit(AuditService auditService) {
        this.auditService = auditService;
    }

    /** A report was read ({@code reports.viewed}). */
    public void recordView(CurrentUser currentUser, String reportName, Map<String, Object> filters, UUID subjectId, int rowCount) {
        Map<String, Object> after = details(ACCESS_CATEGORY, reportName, filters, rowCount);
        auditService.record(new AuditEntry(currentUser.businessId(), currentUser.userId(), subjectId, AuditAction.REPORT_VIEWED, ENTITY_TYPE,
                reportName, null, after, null));
    }

    /** The workday report was downloaded as CSV ({@code reports.exported}). */
    public void recordExport(CurrentUser currentUser, String reportName, Map<String, Object> filters, UUID subjectId, int rowCount,
            String timeZone, String fileName) {
        Map<String, Object> after = details(EXPORT_CATEGORY, reportName, filters, rowCount);
        after.put("timeZone", timeZone);
        after.put("fileName", fileName);
        auditService.record(new AuditEntry(currentUser.businessId(), currentUser.userId(), subjectId, AuditAction.REPORT_EXPORTED,
                ENTITY_TYPE, reportName, null, after, null));
    }

    private static Map<String, Object> details(String category, String reportName, Map<String, Object> filters, int rowCount) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("category", category);
        after.put("report", reportName);
        after.put("filters", filters);
        after.put("rowCount", rowCount);
        return after;
    }
}

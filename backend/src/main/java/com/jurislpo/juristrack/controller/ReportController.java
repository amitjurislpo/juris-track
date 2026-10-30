package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.ReportService;
import com.jurislpo.juristrack.service.ReportService.SummaryReport;
import com.jurislpo.juristrack.service.ReportService.WorkdayExport;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reports of managers (their reports) and owners (everybody in the business): {@code GET /api/v1/reports/workdays} (JSON, or the CSV
 * file when the request accepts {@code text/csv}) and {@code GET /api/v1/reports/summaries}. EMPLOYEE and the platform SUPER_ADMIN get
 * 403. Rules: {@link ReportService}; contract: references/API.md.
 */
@RestController
public class ReportController {

    private static final Role[] REPORT_ROLES = {Role.ADMIN, Role.MANAGER};
    private static final MediaType TEXT_CSV = new MediaType("text", "csv");
    private static final MediaType TEXT_CSV_UTF8 = MediaType.parseMediaType("text/csv; charset=utf-8");

    private final Auth auth;
    private final ReportService reportService;

    public ReportController(Auth auth, ReportService reportService) {
        this.auth = auth;
        this.reportService = reportService;
    }

    /** The workday report: JSON by default; the CSV download when {@code Accept} names {@code text/csv}. */
    @GetMapping("/api/v1/reports/workdays")
    public ResponseEntity<?> listWorkdays(@RequestParam Map<String, String> queryParameters,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        CurrentUser currentUser = auth.requireRole(REPORT_ROLES);
        if (!acceptsCsv(accept)) return ResponseEntity.ok(ApiResult.success(reportService.listWorkdays(currentUser, queryParameters)));
        WorkdayExport export = reportService.exportWorkdays(currentUser, queryParameters);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(export.content());
    }

    @GetMapping("/api/v1/reports/summaries")
    public ApiResult<SummaryReport> listSummaries(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(reportService.listSummaries(auth.requireRole(REPORT_ROLES), queryParameters));
    }

    /** True when the Accept header names {@code text/csv} itself (a wildcard such as {@code *}{@code /*} keeps JSON). */
    private static boolean acceptsCsv(String accept) {
        if (accept == null || accept.isBlank()) return false;
        try {
            List<MediaType> acceptedTypes = MediaType.parseMediaTypes(accept);
            return acceptedTypes.stream().anyMatch(acceptedType -> acceptedType.equalsTypeAndSubtype(TEXT_CSV));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}

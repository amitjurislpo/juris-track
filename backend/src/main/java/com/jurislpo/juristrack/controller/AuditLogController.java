package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditLogService;
import com.jurislpo.juristrack.service.AuditLogService.AuditLogDetail;
import com.jurislpo.juristrack.service.AuditLogService.CsvFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/audit-logs}: the audit log, read-only. The owner (ADMIN) reads their business's entries, the platform SUPER_ADMIN every
 * entry; everybody else gets 403. {@code format=csv} answers the same filtered list as a CSV file. Rules: {@link AuditLogService};
 * contract: references/API.md.
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final Auth auth;
    private final AuditLogService auditLogService;

    public AuditLogController(Auth auth, AuditLogService auditLogService) {
        this.auth = auth;
        this.auditLogService = auditLogService;
    }

    /** JSON {@code {ok, data: {rows, total, page, pageSize}}}, or with {@code format=csv} the CSV file of every row of the filter. */
    @GetMapping
    public ResponseEntity<?> listAuditLogs(@RequestParam Map<String, String> queryParameters) {
        CurrentUser viewer = auth.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        String format = queryParameters.getOrDefault("format", "json").trim();
        if (format.equalsIgnoreCase("csv")) {
            CsvFile file = auditLogService.export(viewer, queryParameters);
            return ResponseEntity.ok().contentType(CSV)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                    .body(file.content());
        }
        if (!format.equalsIgnoreCase("json")) {
            InputValidator validator = new InputValidator();
            validator.reject("format", "The format is json or csv.");
            validator.throwIfInvalid();
        }
        return ResponseEntity.ok(ApiResult.success(auditLogService.list(viewer, queryParameters)));
    }

    @GetMapping("/{auditLogId}")
    public ApiResult<AuditLogDetail> findAuditLog(@PathVariable UUID auditLogId) {
        return ApiResult.success(auditLogService.find(auth.requireRole(Role.ADMIN, Role.SUPER_ADMIN), auditLogId));
    }
}

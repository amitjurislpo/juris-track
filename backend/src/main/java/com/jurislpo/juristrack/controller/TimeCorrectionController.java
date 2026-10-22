package com.jurislpo.juristrack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.engine.SegmentType;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.service.TimeCorrectionService;
import com.jurislpo.juristrack.service.TimeCorrectionService.CorrectionResult;
import com.jurislpo.juristrack.service.TimeCorrectionService.WorkdayStatusInput;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corrections of somebody else's time by their manager or the owner: move a work or break interval
 * ({@code PATCH /api/workdays/{workdayId}/work-sessions/{sessionId}}, {@code .../break-sessions/{sessionId}}) and end an open workday
 * ({@code PUT /api/workdays/{workdayId}/status}). MANAGER and ADMIN; everybody else 403. Rules: {@link TimeCorrectionService};
 * contract: references/API-team-and-corrections.md.
 */
@RestController
public class TimeCorrectionController {

    private static final Role[] CORRECTING_ROLES = {Role.ADMIN, Role.MANAGER};

    private final Auth auth;
    private final TimeCorrectionService timeCorrectionService;
    private final ObjectMapper objectMapper;

    public TimeCorrectionController(Auth auth, TimeCorrectionService timeCorrectionService, ObjectMapper objectMapper) {
        this.auth = auth;
        this.timeCorrectionService = timeCorrectionService;
        this.objectMapper = objectMapper;
    }

    @PatchMapping("/api/workdays/{workdayId}/work-sessions/{sessionId}")
    public ApiResult<WorkdaySnapshot> adjustWorkSession(@PathVariable UUID workdayId, @PathVariable UUID sessionId,
            @RequestBody(required = false) JsonNode body) {
        return answer(timeCorrectionService.adjustInterval(auth.requireRole(CORRECTING_ROLES), workdayId, SegmentType.WORK, sessionId,
                PatchBody.of(body, objectMapper)), "Time corrected.");
    }

    @PatchMapping("/api/workdays/{workdayId}/break-sessions/{sessionId}")
    public ApiResult<WorkdaySnapshot> adjustBreakSession(@PathVariable UUID workdayId, @PathVariable UUID sessionId,
            @RequestBody(required = false) JsonNode body) {
        return answer(timeCorrectionService.adjustInterval(auth.requireRole(CORRECTING_ROLES), workdayId, SegmentType.BREAK, sessionId,
                PatchBody.of(body, objectMapper)), "Time corrected.");
    }

    @PutMapping("/api/workdays/{workdayId}/status")
    public ApiResult<WorkdaySnapshot> setWorkdayStatus(@PathVariable UUID workdayId, @RequestBody(required = false) WorkdayStatusInput body) {
        CorrectionResult result = timeCorrectionService.closeWorkday(auth.requireRole(CORRECTING_ROLES), workdayId, body);
        return result.isChanged() ? ApiResult.success("Workday ended.", result.workday())
                : ApiResult.success("This workday had already ended.", result.workday());
    }

    private static ApiResult<WorkdaySnapshot> answer(CorrectionResult result, String message) {
        return result.isChanged() ? ApiResult.success(message, result.workday()) : ApiResult.success("Nothing changed.", result.workday());
    }
}

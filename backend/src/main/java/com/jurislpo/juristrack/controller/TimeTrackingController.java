package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.engine.WorkdaySnapshot;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.TimeTrackingService;
import com.jurislpo.juristrack.service.TimeTrackingService.TimeEventInput;
import com.jurislpo.juristrack.service.TimeTrackingService.TimeEventResult;
import com.jurislpo.juristrack.service.TimeTrackingService.TimeState;
import com.jurislpo.juristrack.service.WorkdayHistoryService;
import com.jurislpo.juristrack.service.WorkdayHistoryService.WorkdayHistory;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own time tracking: {@code /api/me/time} (current state), {@code /api/me/time-events} (start, break, resume,
 * end), {@code /api/workdays/mine} (history) and {@code /api/workdays/{workdayId}} (one workday). Every business user (ADMIN,
 * MANAGER, EMPLOYEE) tracks their own time; the platform SUPER_ADMIN does not (403). Rules: {@link TimeTrackingService},
 * {@link WorkdayHistoryService}; contract: references/API.md.
 */
@RestController
public class TimeTrackingController {

    private static final Role[] TRACKING_ROLES = {Role.ADMIN, Role.MANAGER, Role.EMPLOYEE};

    private final Auth auth;
    private final TimeTrackingService timeTrackingService;
    private final WorkdayHistoryService workdayHistoryService;

    public TimeTrackingController(Auth auth, TimeTrackingService timeTrackingService, WorkdayHistoryService workdayHistoryService) {
        this.auth = auth;
        this.timeTrackingService = timeTrackingService;
        this.workdayHistoryService = workdayHistoryService;
    }

    @GetMapping("/api/me/time")
    public ApiResult<TimeState> findCurrentTimeState() {
        return ApiResult.success(timeTrackingService.currentState(auth.requireRole(TRACKING_ROLES)));
    }

    /** 201 when the event changed the state, 200 (no message) when it was a repeat ({@code alreadyApplied}). */
    @PostMapping("/api/me/time-events")
    public ResponseEntity<ApiResult<TimeEventResult>> recordTimeEvent(@RequestBody(required = false) TimeEventInput body) {
        CurrentUser currentUser = auth.requireRole(TRACKING_ROLES);
        TimeEventResult result = timeTrackingService.recordEvent(currentUser, body);
        if (result.alreadyApplied()) return ResponseEntity.ok(ApiResult.success(result));
        String message = switch (result.type()) {
            case START -> "Workday started. Have a productive day.";
            case BREAK -> "Break started. Your productive timer is paused.";
            case RESUME -> "Welcome back. Your productive timer is running.";
            case END -> "Workday ended. Your summary is ready.";
        };
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(message, result));
    }

    @GetMapping("/api/workdays/mine")
    public ApiResult<WorkdayHistory> listOwnWorkdays(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(workdayHistoryService.listOwnWorkdays(auth.requireRole(TRACKING_ROLES), queryParameters));
    }

    @GetMapping("/api/workdays/{workdayId}")
    public ApiResult<WorkdaySnapshot> findWorkday(@PathVariable UUID workdayId) {
        return ApiResult.success(workdayHistoryService.findOwnWorkday(auth.requireRole(TRACKING_ROLES), workdayId));
    }
}

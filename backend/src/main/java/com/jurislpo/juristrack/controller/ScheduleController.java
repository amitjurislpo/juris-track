package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.service.ScheduleService;
import com.jurislpo.juristrack.service.ScheduleService.ScheduleBoard;
import com.jurislpo.juristrack.service.ScheduleService.ScheduleDayView;
import com.jurislpo.juristrack.service.ScheduleService.ScheduleEntriesBody;
import com.jurislpo.juristrack.service.ScheduleService.ScheduleEntryBody;
import com.jurislpo.juristrack.service.ScheduleService.ScheduleRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The roster (who works which shift on which date, who has a week off): the owner writes, a manager reads their reports', an
 * employee reads their own. Rules: {@link ScheduleService}; contract: references/API.md.
 */
@RestController
public class ScheduleController {

    private final Auth auth;
    private final ScheduleService scheduleService;

    public ScheduleController(Auth auth, ScheduleService scheduleService) {
        this.auth = auth;
        this.scheduleService = scheduleService;
    }

    @GetMapping("/api/schedule-entries")
    public ApiResult<ScheduleBoard> listSchedules(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(scheduleService.listSchedules(auth.requireRole(Role.ADMIN, Role.MANAGER), queryParameters));
    }

    @GetMapping("/api/me/schedule")
    public ApiResult<ScheduleRow> findOwnSchedule(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(scheduleService.findOwnSchedule(auth.requireRole(Role.ADMIN, Role.MANAGER, Role.EMPLOYEE), queryParameters));
    }

    @PutMapping("/api/employees/{employeeId}/schedule-entries/{date}")
    public ApiResult<ScheduleDayView> setScheduleEntry(@PathVariable UUID employeeId, @PathVariable String date,
            @RequestBody(required = false) ScheduleEntryBody body) {
        return ApiResult.success("Schedule saved.", scheduleService.setEntry(auth.requireRole(Role.ADMIN), employeeId, date, body));
    }

    @DeleteMapping("/api/employees/{employeeId}/schedule-entries/{date}")
    public ApiResult<ScheduleDayView> removeScheduleEntry(@PathVariable UUID employeeId, @PathVariable String date) {
        return ApiResult.success("Schedule entry removed.", scheduleService.removeEntry(auth.requireRole(Role.ADMIN), employeeId, date));
    }

    @PutMapping("/api/employees/{employeeId}/schedule-entries")
    public ApiResult<List<ScheduleDayView>> setScheduleEntries(@PathVariable UUID employeeId, @RequestBody(required = false) ScheduleEntriesBody body) {
        return ApiResult.success("Schedule saved.", scheduleService.setEntries(auth.requireRole(Role.ADMIN), employeeId, body));
    }
}

package com.jurislpo.juristrack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.CreatedResource;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.service.ShiftService;
import com.jurislpo.juristrack.service.ShiftService.ShiftInput;
import com.jurislpo.juristrack.service.ShiftService.ShiftRow;
import com.jurislpo.juristrack.service.ShiftService.ShiftStatusChange;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/shifts}: the business's shift templates. Everybody of the business reads; the owner (ADMIN) writes. Rules: {@link ShiftService}. */
@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

    private static final Role[] BUSINESS_ROLES = {Role.ADMIN, Role.MANAGER, Role.EMPLOYEE};

    private final Auth auth;
    private final ShiftService shiftService;
    private final ObjectMapper objectMapper;

    public ShiftController(Auth auth, ShiftService shiftService, ObjectMapper objectMapper) {
        this.auth = auth;
        this.shiftService = shiftService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResult<List<ShiftRow>> listShifts(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(shiftService.list(auth.requireRole(BUSINESS_ROLES), queryParameters));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<CreatedResource> createShift(@RequestBody(required = false) ShiftInput body) {
        return ApiResult.success("Shift added.", new CreatedResource(shiftService.create(auth.requireRole(Role.ADMIN), body)));
    }

    @GetMapping("/{shiftId}")
    public ApiResult<ShiftRow> findShift(@PathVariable UUID shiftId) {
        return ApiResult.success(shiftService.find(auth.requireRole(BUSINESS_ROLES), shiftId));
    }

    @PatchMapping("/{shiftId}")
    public ApiResult<ShiftRow> updateShift(@PathVariable UUID shiftId, @RequestBody(required = false) JsonNode body) {
        return ApiResult.success("Shift saved.", shiftService.update(auth.requireRole(Role.ADMIN), shiftId, PatchBody.of(body, objectMapper)));
    }

    @PutMapping("/{shiftId}/status")
    public ApiResult<ShiftRow> setShiftStatus(@PathVariable UUID shiftId, @RequestBody(required = false) ShiftStatusChange body) {
        ShiftRow shift = shiftService.setStatus(auth.requireRole(Role.ADMIN), shiftId, body);
        return ApiResult.success(shift.isActive() ? "Shift is active." : "Shift deactivated.", shift);
    }
}

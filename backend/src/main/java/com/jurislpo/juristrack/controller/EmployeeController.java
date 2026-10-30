package com.jurislpo.juristrack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.CreatedResource;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.EmployeeRow;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.EmployeeService;
import com.jurislpo.juristrack.service.EmployeeService.EmployeeChanges;
import com.jurislpo.juristrack.service.EmployeeService.EmployeeInput;
import com.jurislpo.juristrack.service.EmployeeService.EmployeeStatusChange;
import com.jurislpo.juristrack.service.EmployeeService.ShiftAssignmentInput;
import com.jurislpo.juristrack.service.EmployeeService.LoginAccessChange;
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

/** {@code /api/v1/employees}: the owner (ADMIN) manages the people of their own business. Rules: {@link EmployeeService}; contract: references/API.md. */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final Auth auth;
    private final EmployeeService employeeService;
    private final ObjectMapper objectMapper;

    public EmployeeController(Auth auth, EmployeeService employeeService, ObjectMapper objectMapper) {
        this.auth = auth;
        this.employeeService = employeeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResult<ListPage<EmployeeRow>> listEmployees(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(employeeService.list(auth.requireRole(Role.ADMIN), queryParameters));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<CreatedResource> createEmployee(@RequestBody(required = false) EmployeeInput body) {
        return ApiResult.success("Employee added.", new CreatedResource(employeeService.create(auth.requireRole(Role.ADMIN), body)));
    }

    @GetMapping("/{employeeId}")
    public ApiResult<EmployeeRow> findEmployee(@PathVariable UUID employeeId) {
        return ApiResult.success(employeeService.find(auth.requireRole(Role.ADMIN), employeeId));
    }

    @PatchMapping("/{employeeId}")
    public ApiResult<EmployeeRow> updateEmployee(@PathVariable UUID employeeId, @RequestBody(required = false) JsonNode body) {
        CurrentUser owner = auth.requireRole(Role.ADMIN);
        return ApiResult.success("Employee saved.", employeeService.update(owner, employeeId, EmployeeChanges.from(PatchBody.of(body, objectMapper))));
    }

    @PutMapping("/{employeeId}/shift")
    public ApiResult<EmployeeRow> assignEmployeeShift(@PathVariable UUID employeeId, @RequestBody(required = false) ShiftAssignmentInput body) {
        return ApiResult.success("Shift saved.", employeeService.assignShift(auth.requireRole(Role.ADMIN), employeeId, body));
    }

    @PutMapping("/{employeeId}/status")
    public ApiResult<EmployeeRow> setEmployeeStatus(@PathVariable UUID employeeId, @RequestBody(required = false) EmployeeStatusChange body) {
        EmployeeRow employee = employeeService.setStatus(auth.requireRole(Role.ADMIN), employeeId, body);
        return ApiResult.success(employee.isActive() ? "Employee is active." : "Employee deactivated.", employee);
    }

    @PutMapping("/{employeeId}/login-access")
    public ApiResult<EmployeeRow> setEmployeeLoginAccess(@PathVariable UUID employeeId, @RequestBody(required = false) LoginAccessChange body) {
        EmployeeRow employee = employeeService.setLoginAccess(auth.requireRole(Role.ADMIN), employeeId, body);
        return ApiResult.success(employee.isLoginEnabled() ? "Login enabled." : "Login disabled.", employee);
    }
}

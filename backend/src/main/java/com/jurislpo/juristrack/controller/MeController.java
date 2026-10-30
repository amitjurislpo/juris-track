package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.EmployeeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/me}: the signed-in user (standalone session or JurisOne's token alike). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final Auth auth;
    private final EmployeeService employeeService;

    public MeController(Auth auth, EmployeeService employeeService) {
        this.auth = auth;
        this.employeeService = employeeService;
    }

    /** {@code {id, firstName, lastName, email, role, businessName, employeeCode, managerName, businessTimeZone}}; 401 when signed out. */
    @GetMapping
    public ApiResult<SignedInUserProfile> describeCurrentUser() {
        CurrentUser currentUser = auth.requireUser();
        return ApiResult.success(SignedInUserProfile.of(currentUser, employeeService.findProfileDetails(currentUser)));
    }
}

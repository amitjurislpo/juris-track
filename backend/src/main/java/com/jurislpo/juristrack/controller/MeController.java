package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.security.Auth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/me}: the signed-in user (standalone session or JurisOne's token alike). */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final Auth auth;

    public MeController(Auth auth) {
        this.auth = auth;
    }

    /** {@code {id, firstName, lastName, email, role, businessName}}; 401 when signed out. */
    @GetMapping
    public ApiResult<SignedInUser> describeCurrentUser() {
        return ApiResult.success(SignedInUser.of(auth.requireUser()));
    }
}

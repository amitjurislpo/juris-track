package com.jurislpo.juristrack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.BusinessService;
import com.jurislpo.juristrack.service.BusinessService.BusinessChanges;
import com.jurislpo.juristrack.service.BusinessService.BusinessSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/business}: the owner's (ADMIN's) own business settings (a singular resource: the business of the signed-in owner).
 * A new time zone applies to workdays started from then on. Rules: {@link BusinessService}; contract: references/API.md.
 */
@RestController
@RequestMapping("/api/v1/business")
public class BusinessSettingsController {

    private final Auth auth;
    private final BusinessService businessService;
    private final ObjectMapper objectMapper;

    public BusinessSettingsController(Auth auth, BusinessService businessService, ObjectMapper objectMapper) {
        this.auth = auth;
        this.businessService = businessService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResult<BusinessSettings> findBusinessSettings() {
        return ApiResult.success(businessService.findSettings(auth.requireRole(Role.ADMIN)));
    }

    @PatchMapping
    public ApiResult<BusinessSettings> updateBusinessSettings(@RequestBody(required = false) JsonNode body) {
        CurrentUser owner = auth.requireRole(Role.ADMIN);
        return ApiResult.success("Settings saved.", businessService.updateSettings(owner, BusinessChanges.from(PatchBody.of(body, objectMapper))));
    }
}

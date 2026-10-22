package com.jurislpo.juristrack.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.repository.BusinessQueryRepository.BusinessRow;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.BusinessService;
import com.jurislpo.juristrack.service.BusinessService.BusinessChanges;
import com.jurislpo.juristrack.service.BusinessService.BusinessDetail;
import com.jurislpo.juristrack.service.BusinessService.BusinessInput;
import com.jurislpo.juristrack.service.BusinessService.BusinessStatusChange;
import com.jurislpo.juristrack.service.BusinessService.CreatedBusiness;
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

/**
 * {@code /api/businesses}: the platform super admin's businesses (SUPER_ADMIN only; the super admin has no business, so these requests
 * run in system mode and see every business). Rules: {@link BusinessService}; contract: references/API.md.
 */
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final Auth auth;
    private final BusinessService businessService;
    private final ObjectMapper objectMapper;

    public BusinessController(Auth auth, BusinessService businessService, ObjectMapper objectMapper) {
        this.auth = auth;
        this.businessService = businessService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResult<ListPage<BusinessRow>> listBusinesses(@RequestParam Map<String, String> queryParameters) {
        return ApiResult.success(businessService.list(auth.requireRole(Role.SUPER_ADMIN), queryParameters));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<CreatedBusiness> createBusiness(@RequestBody(required = false) BusinessInput body) {
        return ApiResult.success("Business created.", businessService.create(auth.requireRole(Role.SUPER_ADMIN), body));
    }

    @GetMapping("/{businessId}")
    public ApiResult<BusinessDetail> findBusiness(@PathVariable UUID businessId) {
        return ApiResult.success(businessService.find(auth.requireRole(Role.SUPER_ADMIN), businessId));
    }

    @PatchMapping("/{businessId}")
    public ApiResult<BusinessDetail> updateBusiness(@PathVariable UUID businessId, @RequestBody(required = false) JsonNode body) {
        CurrentUser superAdmin = auth.requireRole(Role.SUPER_ADMIN);
        return ApiResult.success("Business saved.", businessService.update(superAdmin, businessId, BusinessChanges.from(PatchBody.of(body, objectMapper))));
    }

    @PutMapping("/{businessId}/status")
    public ApiResult<BusinessDetail> setBusinessStatus(@PathVariable UUID businessId, @RequestBody(required = false) BusinessStatusChange body) {
        BusinessDetail business = businessService.setStatus(auth.requireRole(Role.SUPER_ADMIN), businessId, body);
        return ApiResult.success(business.isSuspended() ? "Business suspended." : "Business is active.", business);
    }
}

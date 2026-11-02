package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.common.ApiResult;
import com.jurislpo.juristrack.security.Auth;
import com.jurislpo.juristrack.security.IntegrationTokenVerifier;
import com.jurislpo.juristrack.service.BusinessService;
import com.jurislpo.juristrack.service.BusinessService.BusinessStatusChange;
import com.jurislpo.juristrack.service.RosterSyncService;
import com.jurislpo.juristrack.service.RosterSyncService.RosterSyncInput;
import com.jurislpo.juristrack.service.RosterSyncService.RosterSyncResult;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/integration}: calls JurisOne makes on its own behalf, not for a signed-in user. The caller is JurisOne's service token
 * (scope {@code employees:sync}); a cookie session or a user token is refused, and the service token works nowhere else. Rules:
 * {@link RosterSyncService}.
 */
@RestController
@RequestMapping("/api/v1/integration")
public class IntegrationRosterController {

    private final Auth auth;
    private final RosterSyncService rosterSyncService;
    private final BusinessService businessService;

    public IntegrationRosterController(Auth auth, RosterSyncService rosterSyncService, BusinessService businessService) {
        this.businessService = businessService;
        this.auth = auth;
        this.rosterSyncService = rosterSyncService;
    }

    /**
     * JurisOne suspended or reactivated its business: the linked business follows ({@code {isSuspended, reason?}}). Service token of scope
     * {@code business:status} only; the roster token cannot call this and this token cannot call the roster.
     */
    @PutMapping("/business/status")
    public ApiResult<BusinessService.JurisoneStatusResult> setBusinessStatus(@RequestBody(required = false) BusinessStatusChange body) {
        return ApiResult.success(businessService.setStatusFromJurisone(auth.requireIntegrationService(IntegrationTokenVerifier.BUSINESS_STATUS_SCOPE).jurisoneBusinessId(), body));
    }

    /** Applies JurisOne's shifts and employees to the business the token names (idempotent); see {@link RosterSyncResult}. */
    @PutMapping("/employees")
    public ApiResult<RosterSyncResult> syncEmployees(@RequestBody(required = false) RosterSyncInput body) {
        return ApiResult.success(rosterSyncService.sync(auth.requireIntegrationService(IntegrationTokenVerifier.ROSTER_SYNC_SCOPE), body));
    }
}

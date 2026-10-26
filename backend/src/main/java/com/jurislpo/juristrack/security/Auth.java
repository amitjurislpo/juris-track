package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.exception.ForbiddenException;
import com.jurislpo.juristrack.exception.UnauthenticatedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The permission checks every controller makes before it calls a service: signed out answers 401
 * ("Your session has ended..."), a role that may not do this answers 403 ("You don't have permission...").
 */
@Component
public class Auth {

    /** The signed-in user of this request, or null when signed out. */
    public CurrentUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser ? currentUser : null;
    }

    /**
     * JurisOne's service token, which only the roster sync accepts (see RequestAuthenticationFilter): 401 when nobody is signed in, 403 for a
     * signed-in user (a cookie session or a user token can never call the roster sync).
     */
    public IntegrationServiceClaims requireIntegrationService() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof IntegrationServiceClaims serviceClaims) return serviceClaims;
        if (current() == null) throw new UnauthenticatedException();
        throw new ForbiddenException();
    }

    /** The signed-in user (any role). */
    public CurrentUser requireUser() {
        CurrentUser currentUser = current();
        if (currentUser == null) throw new UnauthenticatedException();
        return currentUser;
    }

    /** The signed-in user, who must have one of {@code roles}. */
    public CurrentUser requireRole(Role... roles) {
        CurrentUser currentUser = requireUser();
        if (!currentUser.hasRole(roles)) throw new ForbiddenException();
        return currentUser;
    }
}

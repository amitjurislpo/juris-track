package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.UserProfileDetails;
import com.jurislpo.juristrack.security.CurrentUser;
import java.util.UUID;

/**
 * {@code GET /api/v1/me}: the fields of {@link SignedInUser} plus the signed-in user's employee code, manager and business time zone.
 *
 * @param businessName     null for the SUPER_ADMIN (a platform user without a business)
 * @param employeeCode     null when none was given
 * @param managerName      the reporting manager's "first last", or null
 * @param businessTimeZone the business calendar's IANA zone; null for the SUPER_ADMIN
 */
public record SignedInUserProfile(UUID id, String firstName, String lastName, String email, Role role, String businessName, String employeeCode,
        String managerName, String businessTimeZone) {

    static SignedInUserProfile of(CurrentUser currentUser, UserProfileDetails details) {
        return new SignedInUserProfile(currentUser.userId(), currentUser.firstName(), currentUser.lastName(), currentUser.email(), currentUser.role(),
                currentUser.businessName(), details.employeeCode(), details.managerName(), details.businessTimeZone());
    }
}

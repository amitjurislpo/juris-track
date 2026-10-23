package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.entity.Role;
import java.util.UUID;

/**
 * The signed-in user of the current request, the same in both modes (standalone cookie session or JurisOne's token).
 * Re-read from the database on every request, so a deactivation or role change applies at once.
 *
 * @param userId       the JurisTrack user
 * @param businessId   their business; null for the SUPER_ADMIN (platform user, system mode)
 * @param businessName the business's name; null for the SUPER_ADMIN
 * @param role         what they may do besides tracking their own time
 * @param firstName    first name
 * @param lastName     last name
 * @param email        the sign-in email (unique across every business)
 * @param source       how this request was authenticated
 * @param sessionId    the standalone session (the refresh-token family); null for an integration token
 */
public record CurrentUser(UUID userId, UUID businessId, String businessName, Role role, String firstName, String lastName, String email,
        AuthenticationSource source, String sessionId) {

    public String fullName() {
        return (firstName + " " + lastName).trim();
    }

    public boolean hasRole(Role... roles) {
        for (Role allowedRole : roles) if (allowedRole == role) return true;
        return false;
    }
}

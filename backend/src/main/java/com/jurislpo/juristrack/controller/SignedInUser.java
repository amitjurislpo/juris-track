package com.jurislpo.juristrack.controller;

import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.security.CurrentUser;
import java.util.UUID;

/**
 * Who is signed in, as {@code GET /api/me} and a successful sign-in return it.
 *
 * @param businessName null for the SUPER_ADMIN (a platform user without a business)
 */
public record SignedInUser(UUID id, String firstName, String lastName, String email, Role role, String businessName) {

    static SignedInUser of(CurrentUser currentUser) {
        return new SignedInUser(currentUser.userId(), currentUser.firstName(), currentUser.lastName(), currentUser.email(), currentUser.role(),
                currentUser.businessName());
    }
}

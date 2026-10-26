package com.jurislpo.juristrack.security;

import com.jurislpo.juristrack.entity.Role;
import java.util.UUID;

/**
 * What a verified JurisOne token says about its caller (see {@link IntegrationTokenVerifier}).
 *
 * @param jurisoneEmployeeId  {@code sub}: the JurisOne employee (stable)
 * @param email               {@code email}
 * @param name                {@code name}: the full name
 * @param jurisoneBusinessId  {@code businessId}: the JurisOne business
 * @param businessName        {@code businessName}
 * @param jurisoneManagerId   {@code managerId}: the JurisOne employee this one reports to, or null
 * @param role                from {@code trackRole}: MANAGER, EMPLOYEE, or ADMIN when JurisOne sends it; anything else is EMPLOYEE
 * @param shiftCode           {@code shiftCode}: the code of the shift JurisOne assigned (given to a user this token creates); null when
 *                            absent or blank
 */
public record IntegrationClaims(UUID jurisoneEmployeeId, String email, String name, UUID jurisoneBusinessId, String businessName,
        UUID jurisoneManagerId, Role role, String shiftCode) {}

package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.security.AuthenticationSource;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.security.IntegrationClaims;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import com.jurislpo.juristrack.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Embedded in JurisOne: turns a verified JurisOne token into a JurisTrack business and user, just in time, on every request
 * (system mode, one transaction):
 * <ol>
 *   <li>the business by {@code jurisone_business_id}; when there is none yet, a new business is created with the JurisOne name and
 *       the defaults, unless the token's person already exists (a refusal, see below). A token never adopts the business of an
 *       existing user: a standalone business moves to JurisOne only through the super admin's explicit link
 *       ({@code PUT /api/businesses/{businessId}/jurisone-link}). Its name follows JurisOne's.</li>
 *   <li>the user by {@code jurisone_employee_id}, else by email inside that same business (an existing user of the linked business is
 *       linked), else created.</li>
 *   <li>first and last name from {@code name}, the role from {@code trackRole} (JurisOne decides who is a JurisTrack manager; an
 *       existing owner stays the owner unless the token says ADMIN),
 *       and the reporting manager from {@code managerId}, resolved through the manager's {@code jurisone_employee_id} inside the
 *       same business (null while that manager has not been seen yet; never the user themselves).</li>
 * </ol>
 * A deactivated user or a suspended business is refused ({@link AccessRefusedException}, 403), and so is a token whose email belongs to a user of
 * another business or to the platform super admin. Changes are written to the audit log; an unchanged user writes nothing.
 */
@Service
public class IntegrationProvisioningService {

    /**
     * The token is valid but this person may not use JurisTrack (deactivated, the email belongs to someone else, or the business is
     * suspended: {@link #isBusinessSuspended()}, answered as 403 {@code BUSINESS_SUSPENDED}). The message is the reason code of the
     * audit entry; {@code businessId} and {@code userId} name the existing records it is about (either may be null).
     */
    public static class AccessRefusedException extends RuntimeException {
        private final boolean isBusinessSuspended;
        private final UUID businessId;
        private final UUID userId;

        AccessRefusedException(String reasonCode, boolean isBusinessSuspended, UUID businessId, UUID userId) {
            super(reasonCode);
            this.isBusinessSuspended = isBusinessSuspended;
            this.businessId = businessId;
            this.userId = userId;
        }

        public boolean isBusinessSuspended() { return isBusinessSuspended; }

        public UUID businessId() { return businessId; }

        public UUID userId() { return userId; }
    }

    public static final String REFUSED_BUSINESS_SUSPENDED = "business_suspended";
    public static final String REFUSED_USER_DEACTIVATED = "user_deactivated";
    public static final String REFUSED_EMAIL_OF_ANOTHER_BUSINESS = "email_of_another_business";

    private static final String BUSINESS_ENTITY = "business";
    private static final String USER_ENTITY = "user";

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public IntegrationProvisioningService(BusinessRepository businessRepository, UserRepository userRepository, AuditService auditService) {
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** The current user for this token, creating or updating the business and the user as needed. Call in system mode. */
    @Transactional
    public CurrentUser provision(IntegrationClaims claims) {
        if (TenantContext.current() != null) throw new IllegalStateException("Provisioning runs in system mode, outside any business");
        Optional<User> userByEmployeeId = userRepository.findByJurisoneEmployeeId(claims.jurisoneEmployeeId());
        User userByEmail = userByEmployeeId.isPresent() ? null : userRepository.findByEmail(claims.email()).orElse(null);
        User user = userByEmployeeId.orElse(userByEmail);
        Optional<Business> linkedBusiness = businessRepository.findByJurisoneBusinessId(claims.jurisoneBusinessId());
        if (linkedBusiness.isEmpty() && user != null) {
            // Never adopt the business of an existing user by email: a standalone owner can plant any email. A business moves to JurisOne
            // only through the super admin's explicit link (PUT /api/businesses/{businessId}/jurisone-link).
            throw new AccessRefusedException(REFUSED_EMAIL_OF_ANOTHER_BUSINESS, false, user.getBusinessId(), user.getId());
        }
        Business business = linkedBusiness.map(existingBusiness -> renameFromToken(existingBusiness, claims))
                .orElseGet(() -> createBusiness(claims));
        if (business.isSuspended()) {
            boolean isUserOfBusiness = user != null && business.getId().equals(user.getBusinessId());
            throw new AccessRefusedException(REFUSED_BUSINESS_SUSPENDED, true, business.getId(), isUserOfBusiness ? user.getId() : null);
        }
        if (user != null && !user.isActive()) throw new AccessRefusedException(REFUSED_USER_DEACTIVATED, false, user.getBusinessId(), user.getId());
        if (user != null && !business.getId().equals(user.getBusinessId())) {
            throw new AccessRefusedException(REFUSED_EMAIL_OF_ANOTHER_BUSINESS, false, user.getBusinessId(), user.getId());
        }
        if (user == null) user = createUser(claims, business);
        else if (user.getJurisoneEmployeeId() == null) linkUser(user, claims);
        updateFromToken(user, claims, business);
        return new CurrentUser(user.getId(), business.getId(), business.getName(), user.getRole(), user.getFirstName(), user.getLastName(),
                user.getEmail(), AuthenticationSource.INTEGRATION, null);
    }

    /** A linked business follows JurisOne's name. */
    private Business renameFromToken(Business business, IntegrationClaims claims) {
        if (!business.getName().equals(claims.businessName())) {
            Map<String, Object> before = businessDetails(business);
            business.setName(claims.businessName());
            auditService.record(new AuditEntry(business.getId(), null, null, AuditAction.BUSINESS_UPDATED_FROM_TOKEN, BUSINESS_ENTITY,
                    business.getId().toString(), before, businessDetails(business), null));
        }
        return business;
    }

    private Business createBusiness(IntegrationClaims claims) {
        Business business = new Business();
        business.setName(claims.businessName());
        business.setJurisoneBusinessId(claims.jurisoneBusinessId());
        businessRepository.saveAndFlush(business);
        auditService.record(new AuditEntry(business.getId(), null, null, AuditAction.BUSINESS_PROVISIONED, BUSINESS_ENTITY, business.getId().toString(),
                null, businessDetails(business), null));
        return business;
    }

    private User createUser(IntegrationClaims claims, Business business) {
        User user = new User();
        user.setBusinessId(business.getId());
        user.setEmail(claims.email());
        user.setJurisoneEmployeeId(claims.jurisoneEmployeeId());
        applyName(user, claims.name());
        user.setRole(claims.role());
        userRepository.saveAndFlush(user);
        auditService.record(new AuditEntry(business.getId(), user.getId(), user.getId(), AuditAction.USER_PROVISIONED, USER_ENTITY, user.getId().toString(),
                null, userDetails(user), null));
        return user;
    }

    private void linkUser(User user, IntegrationClaims claims) {
        user.setJurisoneEmployeeId(claims.jurisoneEmployeeId());
        auditService.record(new AuditEntry(user.getBusinessId(), user.getId(), user.getId(), AuditAction.USER_LINKED, USER_ENTITY, user.getId().toString(),
                null, Map.of("jurisoneEmployeeId", claims.jurisoneEmployeeId().toString()), null));
    }

    /** Name, email, role and manager follow the token; writes an audit entry only when something changed. */
    private void updateFromToken(User user, IntegrationClaims claims, Business business) {
        Map<String, Object> before = userDetails(user);
        applyName(user, claims.name());
        user.setEmail(claims.email());
        // JurisOne moves people between EMPLOYEE and MANAGER; an owner (ADMIN) stays the owner unless the token itself says otherwise
        if (user.getRole() != Role.ADMIN) user.setRole(claims.role());
        user.setManagerId(resolveManagerId(claims, business, user.getId()));
        Map<String, Object> after = userDetails(user);
        if (!before.equals(after)) {
            userRepository.saveAndFlush(user);
            auditService.record(new AuditEntry(business.getId(), user.getId(), user.getId(), AuditAction.USER_UPDATED_FROM_TOKEN, USER_ENTITY,
                    user.getId().toString(), before, after, null));
        }
    }

    private UUID resolveManagerId(IntegrationClaims claims, Business business, UUID userId) {
        if (claims.jurisoneManagerId() == null || claims.jurisoneManagerId().equals(claims.jurisoneEmployeeId())) return null;
        return userRepository.findByBusinessIdAndJurisoneEmployeeId(business.getId(), claims.jurisoneManagerId())
                .map(User::getId)
                .filter(managerId -> !managerId.equals(userId))
                .orElse(null);
    }

    /** "Asha Rao" -> Asha / Rao; a single word is the first name with an empty last name. */
    private static void applyName(User user, String name) {
        String trimmedName = name.trim().replaceAll("\\s+", " ");
        int spaceIndex = trimmedName.indexOf(' ');
        user.setFirstName(spaceIndex < 0 ? trimmedName : trimmedName.substring(0, spaceIndex));
        user.setLastName(spaceIndex < 0 ? "" : trimmedName.substring(spaceIndex + 1));
    }

    private static Map<String, Object> userDetails(User user) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("firstName", user.getFirstName());
        details.put("lastName", user.getLastName());
        details.put("email", user.getEmail());
        details.put("role", Objects.toString(user.getRole(), Role.EMPLOYEE.name()));
        details.put("managerId", user.getManagerId() == null ? null : user.getManagerId().toString());
        return details;
    }

    private static Map<String, Object> businessDetails(Business business) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", business.getName());
        details.put("jurisoneBusinessId", business.getJurisoneBusinessId() == null ? null : business.getJurisoneBusinessId().toString());
        return details;
    }
}

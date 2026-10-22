package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.engine.ReportingLine;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.EmployeeFilter;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.EmployeeRow;
import com.jurislpo.juristrack.repository.EmployeeQueryRepository.UserProfileDetails;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's (ADMIN's) employees: list, add, edit, deactivate and reactivate, always inside the owner's own business (another
 * business's user answers 404). Every write is audited in the same transaction with the record before and after.
 * <ul>
 *   <li>Emails are unique across every business (409 {@code EMAIL_IN_USE}); employee codes are optional and unique inside the
 *       business (409 {@code EMPLOYEE_CODE_IN_USE}).</li>
 *   <li>The owner adds EMPLOYEEs and MANAGERs only; ADMIN and SUPER_ADMIN come from the platform (422 {@code ROLE_NOT_ASSIGNABLE}),
 *       and an ADMIN's role is not changed here.</li>
 *   <li>A manager must be an active MANAGER or ADMIN of the same business, not the employee, and not someone who reports
 *       (directly or further down) to the employee (422 {@code MANAGER_*}).</li>
 *   <li>A MANAGER or ADMIN with active reports cannot become an EMPLOYEE (422 {@code MANAGER_HAS_REPORTS}): move the reports first.</li>
 *   <li>Deactivating ends every session of the person at once (they also get no new sign-in codes); the owner cannot deactivate
 *       themself, and the last active owner of the business cannot be deactivated.</li>
 * </ul>
 */
@Service
public class EmployeeService {

    /** {@code POST /api/employees} body; {@code role} defaults to EMPLOYEE, {@code employeeCode} and {@code managerId} are optional. */
    public record EmployeeInput(String employeeCode, String firstName, String lastName, String email, Role role, UUID managerId) {}

    /**
     * {@code PATCH /api/employees/{id}} body: a field that is left out stays as it is ({@code null} here); a field sent as
     * {@code null} is cleared ({@code Optional.empty()}), which only {@code employeeCode} and {@code managerId} allow.
     */
    public record EmployeeChanges(Optional<String> employeeCode, Optional<String> firstName, Optional<String> lastName, Optional<String> email,
            Optional<Role> role, Optional<UUID> managerId) {

        /** Reads the fields of a PATCH body (400 for a value of the wrong type, such as an unknown role or a malformed id). */
        public static EmployeeChanges from(PatchBody body) {
            EmployeeChanges changes = new EmployeeChanges(body.field("employeeCode", String.class), body.field("firstName", String.class),
                    body.field("lastName", String.class), body.field("email", String.class), body.field("role", Role.class),
                    body.field("managerId", UUID.class));
            body.throwIfInvalid();
            return changes;
        }
    }

    /** {@code PUT /api/employees/{id}/status} body; {@code reason} is optional (at most 500 characters) and goes to the audit log. */
    public record EmployeeStatusChange(@JsonProperty("isActive") Boolean isActive, String reason) {}

    /** The sort keys of {@code GET /api/employees}, in the order the API documents them. */
    public static final List<String> SORT_KEYS = List.of("name", "employeeCode", "email", "role", "status", "lastLoginAt");
    private static final List<Role> LISTED_ROLES = List.of(Role.ADMIN, Role.MANAGER, Role.EMPLOYEE);
    private static final String USER_ENTITY = "user";
    private static final String NOT_FOUND = "Employee not found.";

    private final UserRepository userRepository;
    private final EmployeeQueryRepository employeeQueryRepository;
    private final SessionService sessionService;
    private final AuditService auditService;

    public EmployeeService(UserRepository userRepository, EmployeeQueryRepository employeeQueryRepository, SessionService sessionService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.employeeQueryRepository = employeeQueryRepository;
        this.sessionService = sessionService;
        this.auditService = auditService;
    }

    /**
     * One page of the owner's business: {@code search} (name, email, employee code), {@code status} ({@code active} (default),
     * {@code inactive}, {@code all}), {@code role} (one or more of EMPLOYEE, MANAGER, ADMIN, comma-separated), {@code managerId}
     * (direct reports of that user), {@code sort} ({@link #SORT_KEYS}, default {@code name}), {@code sortDirection}, {@code page},
     * {@code pageSize}. 400 for an unknown value.
     */
    @Transactional(readOnly = true)
    public ListPage<EmployeeRow> list(CurrentUser owner, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(queryParameters, SORT_KEYS, "name", validator);
        Boolean isActive = switch (queryParameters.getOrDefault("status", "active").trim().toLowerCase(Locale.ROOT)) {
            case "", "active" -> Boolean.TRUE;
            case "inactive" -> Boolean.FALSE;
            case "all" -> null;
            default -> {
                validator.reject("status", "The status is active, inactive or all.");
                yield null;
            }
        };
        List<Role> roles = new ArrayList<>();
        for (String roleName : queryParameters.getOrDefault("role", "").split(",")) {
            String trimmedRoleName = roleName.trim().toUpperCase(Locale.ROOT);
            if (trimmedRoleName.isEmpty()) continue;
            Optional<Role> role = LISTED_ROLES.stream().filter(listedRole -> listedRole.name().equals(trimmedRoleName)).findFirst();
            if (role.isPresent()) roles.add(role.get());
            else validator.reject("role", "The role is EMPLOYEE, MANAGER or ADMIN.");
        }
        UUID managerId = null;
        String rawManagerId = queryParameters.getOrDefault("managerId", "").trim();
        if (!rawManagerId.isEmpty()) {
            try {
                managerId = UUID.fromString(rawManagerId);
            } catch (IllegalArgumentException e) {
                validator.reject("managerId", "The manager id is not valid.");
            }
        }
        validator.throwIfInvalid();
        EmployeeFilter filter = new EmployeeFilter(isActive, roles, managerId);
        return new ListPage<>(employeeQueryRepository.findPage(owner.businessId(), filter, listQuery),
                employeeQueryRepository.count(owner.businessId(), filter, listQuery), listQuery.page(), listQuery.pageSize());
    }

    /** One user of the owner's business; 404 when unknown or in another business. */
    @Transactional(readOnly = true)
    public EmployeeRow find(CurrentUser owner, UUID employeeId) {
        return employeeQueryRepository.findRow(owner.businessId(), employeeId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    /** Adds an employee (active, signs in with an emailed code); returns the new id. */
    @Transactional
    public UUID create(CurrentUser owner, EmployeeInput input) {
        EmployeeInput employeeInput = input == null ? new EmployeeInput(null, null, null, null, null, null) : input;
        InputValidator validator = new InputValidator();
        String employeeCode = validator.employeeCode("employeeCode", employeeInput.employeeCode());
        String firstName = validator.requiredText("firstName", "First name", employeeInput.firstName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String lastName = validator.requiredText("lastName", "Last name", employeeInput.lastName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String email = validator.email("email", employeeInput.email());
        validator.throwIfInvalid();
        Role role = employeeInput.role() == null ? Role.EMPLOYEE : employeeInput.role();
        requireAssignableRole(role);
        requireUniqueEmail(email, null);
        requireUniqueEmployeeCode(owner.businessId(), employeeCode, null);
        requireValidManager(owner.businessId(), null, employeeInput.managerId());

        User employee = new User();
        employee.setBusinessId(owner.businessId());
        employee.setEmployeeCode(employeeCode);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setRole(role);
        employee.setManagerId(employeeInput.managerId());
        userRepository.saveAndFlush(employee);
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employee.getId(), AuditAction.EMPLOYEE_CREATED, USER_ENTITY,
                employee.getId().toString(), null, employeeDetails(employee), null));
        return employee.getId();
    }

    /** Changes the fields that were sent; nothing changed writes no audit entry. Returns the employee as now stored. */
    @Transactional
    public EmployeeRow update(CurrentUser owner, UUID employeeId, EmployeeChanges changes) {
        User employee = loadEmployee(owner, employeeId);
        if (changes == null) return find(owner, employeeId);
        Map<String, Object> before = employeeDetails(employee);

        InputValidator validator = new InputValidator();
        String employeeCode = changes.employeeCode() == null ? employee.getEmployeeCode()
                : validator.employeeCode("employeeCode", changes.employeeCode().orElse(null));
        String firstName = changes.firstName() == null ? employee.getFirstName()
                : validator.requiredText("firstName", "First name", changes.firstName().orElse(null), InputValidator.MAXIMUM_NAME_LENGTH);
        String lastName = changes.lastName() == null ? employee.getLastName()
                : validator.requiredText("lastName", "Last name", changes.lastName().orElse(null), InputValidator.MAXIMUM_NAME_LENGTH);
        String email = changes.email() == null ? employee.getEmail() : validator.email("email", changes.email().orElse(null));
        if (changes.role() != null && changes.role().isEmpty()) validator.reject("role", "Role is required.");
        validator.throwIfInvalid();
        Role role = changes.role() == null ? employee.getRole() : changes.role().get();
        UUID managerId = changes.managerId() == null ? employee.getManagerId() : changes.managerId().orElse(null);

        if (role != employee.getRole()) {
            if (employee.getRole() == Role.ADMIN) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.ROLE_NOT_ASSIGNABLE, "The owner's role cannot be changed here.");
            }
            requireAssignableRole(role);
            if (role == Role.EMPLOYEE && employeeQueryRepository.findRow(owner.businessId(), employeeId).orElseThrow().activeReportCount() > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_HAS_REPORTS,
                        "This manager still has active reports. Give them another manager first.");
            }
        }
        if (!email.equals(employee.getEmail())) requireUniqueEmail(email, employeeId);
        if (employeeCode != null && !employeeCode.equals(employee.getEmployeeCode())) requireUniqueEmployeeCode(owner.businessId(), employeeCode, employeeId);
        if (!Objects.equals(managerId, employee.getManagerId())) requireValidManager(owner.businessId(), employeeId, managerId);

        employee.setEmployeeCode(employeeCode);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setRole(role);
        employee.setManagerId(managerId);
        Map<String, Object> after = employeeDetails(employee);
        if (!after.equals(before)) {
            userRepository.saveAndFlush(employee);
            auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId, AuditAction.EMPLOYEE_UPDATED, USER_ENTITY,
                    employeeId.toString(), before, after, null));
        }
        return find(owner, employeeId);
    }

    /**
     * Deactivates (ends every session at once) or reactivates an employee. Setting the state the employee already has changes
     * nothing and writes no audit entry.
     */
    @Transactional
    public EmployeeRow setStatus(CurrentUser owner, UUID employeeId, EmployeeStatusChange statusChange) {
        User employee = loadEmployee(owner, employeeId);
        InputValidator validator = new InputValidator();
        if (statusChange == null || statusChange.isActive() == null) validator.reject("isActive", "Say whether the employee is active (isActive).");
        String reason = validator.optionalText("reason", "Reason", statusChange == null ? null : statusChange.reason(), InputValidator.MAXIMUM_REASON_LENGTH);
        validator.throwIfInvalid();
        boolean isActive = statusChange.isActive();
        if (isActive == employee.isActive()) return find(owner, employeeId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("isActive", isActive);
        if (!isActive) {
            if (employeeId.equals(owner.userId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.CANNOT_DEACTIVATE_SELF, "You cannot deactivate your own account.");
            }
            if (employee.getRole() == Role.ADMIN && employeeQueryRepository.countOtherActiveOwners(owner.businessId(), employeeId) == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.LAST_ACTIVE_OWNER, "The business must keep at least one active owner.");
            }
        }
        employee.setActive(isActive);
        userRepository.saveAndFlush(employee);
        if (!isActive) after.put("endedSessions", sessionService.revokeAllOfUser(employeeId));
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), employeeId,
                isActive ? AuditAction.EMPLOYEE_REACTIVATED : AuditAction.EMPLOYEE_DEACTIVATED, USER_ENTITY, employeeId.toString(),
                Map.of("isActive", !isActive), after, reason));
        return find(owner, employeeId);
    }

    /** What {@code GET /api/me} adds for the signed-in user: employee code, manager name, business time zone. */
    @Transactional(readOnly = true)
    public UserProfileDetails findProfileDetails(CurrentUser currentUser) {
        return employeeQueryRepository.findProfileDetails(currentUser.userId());
    }

    private User loadEmployee(CurrentUser owner, UUID employeeId) {
        return userRepository.findById(employeeId)
                .filter(user -> owner.businessId().equals(user.getBusinessId()) && user.getRole() != Role.SUPER_ADMIN)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private static void requireAssignableRole(Role role) {
        if (role != Role.EMPLOYEE && role != Role.MANAGER) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.ROLE_NOT_ASSIGNABLE, "An employee's role is EMPLOYEE or MANAGER.");
        }
    }

    /** 409 when another user (any business) has the email. Also used for a new business's owner. */
    void requireUniqueEmail(String email, UUID excludedUserId) {
        if (employeeQueryRepository.isEmailInUse(email, excludedUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.EMAIL_IN_USE, "An account with this email already exists.");
        }
    }

    private void requireUniqueEmployeeCode(UUID businessId, String employeeCode, UUID excludedUserId) {
        if (employeeCode != null && employeeQueryRepository.isEmployeeCodeInUse(businessId, employeeCode, excludedUserId)) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.EMPLOYEE_CODE_IN_USE, "This employee code is already in use.");
        }
    }

    /** {@code employeeId} null for a new employee (no cycle possible yet); {@code managerId} null = no manager (always fine). */
    private void requireValidManager(UUID businessId, UUID employeeId, UUID managerId) {
        if (managerId == null) return;
        if (managerId.equals(employeeId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_IS_SELF, "An employee cannot be their own manager.");
        }
        User manager = userRepository.findById(managerId).filter(user -> businessId.equals(user.getBusinessId()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_NOT_FOUND, "Choose a manager from this business."));
        if (!manager.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_INACTIVE, "The chosen manager is deactivated.");
        }
        if (manager.getRole() != Role.MANAGER && manager.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_ROLE_REQUIRED, "The chosen manager needs the MANAGER role.");
        }
        // a loop: the chosen manager is somewhere under the employee (every depth, so ReportingLine walks the whole line down)
        if (employeeId != null && new ReportingLine(employeeQueryRepository.findManagerIdsByEmployeeId(businessId))
                .visibleEmployeeIds(employeeId, Integer.MAX_VALUE).contains(managerId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.MANAGER_CYCLE,
                    "The chosen manager reports to this employee, so the reporting line would go round in a circle.");
        }
    }

    /** The audited fields of a user (before / after). */
    static Map<String, Object> employeeDetails(User user) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("employeeCode", user.getEmployeeCode());
        details.put("firstName", user.getFirstName());
        details.put("lastName", user.getLastName());
        details.put("email", user.getEmail());
        details.put("role", user.getRole().name());
        details.put("isActive", user.isActive());
        details.put("managerId", user.getManagerId() == null ? null : user.getManagerId().toString());
        return details;
    }
}

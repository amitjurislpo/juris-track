package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.ListPage;
import com.jurislpo.juristrack.common.ListQuery;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.entity.Business;
import com.jurislpo.juristrack.entity.Role;
import com.jurislpo.juristrack.entity.User;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.BusinessQueryRepository;
import com.jurislpo.juristrack.repository.BusinessQueryRepository.BusinessFilter;
import com.jurislpo.juristrack.repository.BusinessQueryRepository.BusinessOwner;
import com.jurislpo.juristrack.repository.BusinessQueryRepository.BusinessRow;
import com.jurislpo.juristrack.repository.BusinessRepository;
import com.jurislpo.juristrack.repository.UserRepository;
import com.jurislpo.juristrack.repository.WorkdayRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Businesses: the platform super admin lists, creates (with the owner), edits, suspends and reactivates them (system mode, every
 * business); the owner (ADMIN) reads and edits their own business's settings. Every write is audited with the record before and
 * after, in the business it concerns. The super admin reading the list or one business (with its owner) is audited
 * ({@code access.business_list_viewed} in the platform log, {@code access.business_viewed} in the business's log).
 *
 * <p>Settings: {@code name} (1 to 80 characters), {@code timeZone} (the business calendar: a workday's date is the calendar date in
 * this zone when it started), {@code staleWorkdayHours} (1 to 48: a workday still open after this many hours is shown as not
 * ended), {@code displayTimeZones} (at most 8 zones viewers may switch the display to; the business time zone is always offered as
 * well). Time zones are checked with {@code BusinessCalendar} and stored in their canonical form. <b>Changing the time zone applies to
 * new workdays only</b>: a workday keeps the date and zone it was started with.
 */
@Service
public class BusinessService {

    /** {@code POST /api/businesses} body; {@code timeZone} defaults to {@value #DEFAULT_TIME_ZONE}. */
    public record BusinessInput(String name, String timeZone, OwnerInput owner) {}

    /** The new business's owner (ADMIN): signs in with an emailed code; {@code employeeCode} is optional. */
    public record OwnerInput(String firstName, String lastName, String email, String employeeCode) {}

    /** What {@code POST /api/businesses} answers: the new business and its owner. */
    public record CreatedBusiness(UUID id, UUID ownerId) {}

    /**
     * {@code PATCH /api/businesses/{id}} and {@code PATCH /api/business} body: a field left out stays as it is ({@code null} here); a
     * field sent as {@code null} is refused (every setting has a value).
     */
    public record BusinessChanges(Optional<String> name, Optional<String> timeZone, Optional<Integer> staleWorkdayHours,
            Optional<List<String>> displayTimeZones) {

        /** Reads the fields of a PATCH body (400 for a value of the wrong type). */
        public static BusinessChanges from(PatchBody body) {
            BusinessChanges changes = new BusinessChanges(body.field("name", String.class), body.field("timeZone", String.class),
                    body.field("staleWorkdayHours", Integer.class), body.textListField("displayTimeZones"));
            body.throwIfInvalid();
            return changes;
        }
    }

    /** {@code PUT /api/businesses/{id}/status} body; {@code reason} is optional (at most 500 characters) and goes to the audit log. */
    public record BusinessStatusChange(@JsonProperty("isSuspended") Boolean isSuspended, String reason) {}

    /** {@code PUT /api/businesses/{id}/jurisone-link} body: the JurisOne business this one is linked to (a UUID, as text so a malformed id is a field error). */
    public record JurisoneLinkInput(String jurisoneBusinessId) {}

    /** A business's settings as its owner sees them ({@code GET /api/business}). */
    public record BusinessSettings(UUID id, String name, String timeZone, int staleWorkdayHours, List<String> displayTimeZones, Instant createdAt,
            Instant updatedAt) {}

    /** A business as the super admin sees it ({@code GET /api/businesses/{id}}); {@code owner} is null when it has no ADMIN. */
    public record BusinessDetail(UUID id, String name, String timeZone, int staleWorkdayHours, List<String> displayTimeZones,
            @JsonProperty("isSuspended") boolean isSuspended, @JsonProperty("isLinkedToJurisone") boolean isLinkedToJurisone, Instant createdAt,
            Instant updatedAt, BusinessOwner owner, long employeeCount, long activeEmployeeCount) {}

    public static final String DEFAULT_TIME_ZONE = "Asia/Kolkata";
    /** The sort keys of {@code GET /api/businesses}. */
    public static final List<String> SORT_KEYS = List.of("name", "createdAt", "employeeCount", "status");
    public static final int MINIMUM_STALE_WORKDAY_HOURS = 1;
    public static final int MAXIMUM_STALE_WORKDAY_HOURS = 48;
    public static final int MAXIMUM_DISPLAY_TIME_ZONES = 8;
    /** The most entries a request may list (duplicates are dropped afterwards); more is refused before any zone is looked up. */
    public static final int MAXIMUM_SENT_DISPLAY_TIME_ZONES = 32;
    /** The reason of the {@code workday.closed} entries a suspension writes. */
    static final String SUSPENSION_REASON = "business_suspended";
    private static final String BUSINESS_ENTITY = "business";
    private static final String USER_ENTITY = "user";
    private static final String NOT_FOUND = "Business not found.";
    /** The query parameters of {@code GET /api/businesses} that the access entry keeps. */
    private static final List<String> LIST_PARAMETERS = List.of("search", "status", "sort", "sortDirection", "page", "pageSize");

    private final BusinessRepository businessRepository;
    private final BusinessQueryRepository businessQueryRepository;
    private final UserRepository userRepository;
    private final EmployeeService employeeService;
    private final TimeTrackingService timeTrackingService;
    private final WorkdayRepository workdayRepository;
    private final AuditService auditService;

    public BusinessService(BusinessRepository businessRepository, BusinessQueryRepository businessQueryRepository, UserRepository userRepository,
            EmployeeService employeeService, TimeTrackingService timeTrackingService, WorkdayRepository workdayRepository, AuditService auditService) {
        this.businessRepository = businessRepository;
        this.businessQueryRepository = businessQueryRepository;
        this.userRepository = userRepository;
        this.employeeService = employeeService;
        this.timeTrackingService = timeTrackingService;
        this.workdayRepository = workdayRepository;
        this.auditService = auditService;
    }

    // ── super admin ──

    /**
     * One page of every business: {@code search} (business name, owner name or email), {@code status} ({@code all} (default),
     * {@code active}, {@code suspended}), {@code sort} ({@link #SORT_KEYS}, default {@code name}), {@code sortDirection}, {@code page},
     * {@code pageSize}. 400 for an unknown value.
     */
    @Transactional
    public ListPage<BusinessRow> list(CurrentUser superAdmin, Map<String, String> queryParameters) {
        ListPage<BusinessRow> page = findPage(queryParameters);
        Map<String, Object> details = new LinkedHashMap<>();
        for (String parameter : LIST_PARAMETERS) if (queryParameters.containsKey(parameter)) details.put(parameter, queryParameters.get(parameter));
        details.put("rowCount", page.rows().size());
        details.put("total", page.total());
        auditService.recordAccess(AuditAction.BUSINESS_LIST_VIEWED, superAdmin, null, BUSINESS_ENTITY, null, details);
        return page;
    }

    private ListPage<BusinessRow> findPage(Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        ListQuery listQuery = ListQuery.of(queryParameters, SORT_KEYS, "name", validator);
        Boolean isSuspended = switch (queryParameters.getOrDefault("status", "all").trim().toLowerCase(Locale.ROOT)) {
            case "", "all" -> null;
            case "active" -> Boolean.FALSE;
            case "suspended" -> Boolean.TRUE;
            default -> {
                validator.reject("status", "The status is active, suspended or all.");
                yield null;
            }
        };
        validator.throwIfInvalid();
        BusinessFilter filter = new BusinessFilter(isSuspended);
        return new ListPage<>(businessQueryRepository.findPage(filter, listQuery), businessQueryRepository.count(filter, listQuery), listQuery.page(),
                listQuery.pageSize());
    }

    /** The business with its owner and counts; 404 when unknown. Audited in the business's log. */
    @Transactional
    public BusinessDetail find(CurrentUser superAdmin, UUID businessId) {
        BusinessDetail business = findDetail(businessId);
        auditService.record(new AuditEntry(businessId, superAdmin.userId(), business.owner() == null ? null : business.owner().id(),
                AuditAction.BUSINESS_VIEWED, BUSINESS_ENTITY, businessId.toString(), null, null, null));
        return business;
    }

    private BusinessDetail findDetail(UUID businessId) {
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        BusinessRow row = businessQueryRepository.findRow(businessId).orElseThrow();
        return new BusinessDetail(business.getId(), business.getName(), business.getTimeZone(), business.getStaleWorkdayHours(),
                List.copyOf(business.getDisplayTimeZones()), business.isSuspended(), business.getJurisoneBusinessId() != null, business.getCreatedAt(),
                business.getUpdatedAt(), businessQueryRepository.findOwner(businessId).orElse(null), row.employeeCount(), row.activeEmployeeCount());
    }

    /**
     * Creates a business with the defaults (stale workday hours 14, display zones Asia/Kolkata and America/New_York) and its owner
     * (ADMIN, active, no manager) in one transaction. 409 {@code EMAIL_IN_USE} when the owner's email belongs to anybody.
     */
    @Transactional
    public CreatedBusiness create(CurrentUser superAdmin, BusinessInput input) {
        BusinessInput businessInput = input == null ? new BusinessInput(null, null, null) : input;
        OwnerInput ownerInput = businessInput.owner() == null ? new OwnerInput(null, null, null, null) : businessInput.owner();
        InputValidator validator = new InputValidator();
        String name = validator.requiredText("name", "Business name", businessInput.name(), InputValidator.MAXIMUM_BUSINESS_NAME_LENGTH);
        String timeZone = businessInput.timeZone() == null || businessInput.timeZone().isBlank() ? DEFAULT_TIME_ZONE
                : validator.timeZone("timeZone", businessInput.timeZone());
        String firstName = validator.requiredText("owner.firstName", "Owner's first name", ownerInput.firstName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String lastName = validator.requiredText("owner.lastName", "Owner's last name", ownerInput.lastName(), InputValidator.MAXIMUM_NAME_LENGTH);
        String email = validator.email("owner.email", ownerInput.email());
        String employeeCode = validator.employeeCode("owner.employeeCode", ownerInput.employeeCode());
        validator.throwIfInvalid();
        employeeService.requireUniqueEmail(email, null);

        Business business = new Business();
        business.setName(name);
        business.setTimeZone(timeZone);
        businessRepository.saveAndFlush(business);
        User owner = new User();
        owner.setBusinessId(business.getId());
        owner.setFirstName(firstName);
        owner.setLastName(lastName);
        owner.setEmail(email);
        owner.setEmployeeCode(employeeCode);
        owner.setRole(Role.ADMIN);
        userRepository.saveAndFlush(owner);

        auditService.record(new AuditEntry(business.getId(), superAdmin.userId(), null, AuditAction.BUSINESS_CREATED, BUSINESS_ENTITY,
                business.getId().toString(), null, businessDetails(business), null));
        auditService.record(new AuditEntry(business.getId(), superAdmin.userId(), owner.getId(), AuditAction.EMPLOYEE_CREATED, USER_ENTITY,
                owner.getId().toString(), null, EmployeeService.employeeDetails(owner), null));
        return new CreatedBusiness(business.getId(), owner.getId());
    }

    /** The super admin changes a business's settings (see the class comment); returns the business as now stored. */
    @Transactional
    public BusinessDetail update(CurrentUser superAdmin, UUID businessId, BusinessChanges changes) {
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        applyChanges(superAdmin, business, changes);
        return findDetail(businessId);
    }

    /** Suspends or reactivates a business. Setting the state it already has changes nothing and writes no audit entry. */
    @Transactional
    public BusinessDetail setStatus(CurrentUser superAdmin, UUID businessId, BusinessStatusChange statusChange) {
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        InputValidator validator = new InputValidator();
        if (statusChange == null || statusChange.isSuspended() == null) validator.reject("isSuspended", "Say whether the business is suspended (isSuspended).");
        String reason = validator.optionalText("reason", "Reason", statusChange == null ? null : statusChange.reason(), InputValidator.MAXIMUM_REASON_LENGTH);
        validator.throwIfInvalid();
        boolean isSuspended = statusChange.isSuspended();
        if (isSuspended != business.isSuspended()) {
            business.setSuspended(isSuspended);
            businessRepository.saveAndFlush(business);
            auditService.record(new AuditEntry(businessId, superAdmin.userId(), null,
                    isSuspended ? AuditAction.BUSINESS_SUSPENDED : AuditAction.BUSINESS_REACTIVATED, BUSINESS_ENTITY, businessId.toString(),
                    Map.of("isSuspended", !isSuspended), Map.of("isSuspended", isSuspended), reason));
            if (isSuspended) {
                // nobody can end or correct a day while the business is suspended, so the suspension must not count as work time:
                // every open workday ends now (audited as workday.closed); reactivating reopens nothing
                for (UUID employeeId : workdayRepository.findEmployeeIdsWithOpenWorkday(businessId)) {
                    timeTrackingService.closeOpenWorkday(businessId, superAdmin.userId(), employeeId, SUSPENSION_REASON);
                }
            }
        }
        return findDetail(businessId);
    }

    /**
     * Links a standalone business to its JurisOne business ({@code PUT /api/businesses/{businessId}/jurisone-link}): from then on the
     * tokens of that JurisOne business resolve to it, and its people are linked by email on their first token. This is the only way a
     * business is linked to an existing one (a token never adopts a business). The same link again changes nothing and writes no entry.
     * 400 for a missing or malformed id, 404 for an unknown business, 409 {@code BUSINESS_ALREADY_LINKED} when it is linked to another
     * JurisOne business, 409 {@code JURISONE_BUSINESS_IN_USE} when another business has that JurisOne id.
     */
    @Transactional
    public BusinessDetail linkToJurisone(CurrentUser superAdmin, UUID businessId, JurisoneLinkInput input) {
        InputValidator validator = new InputValidator();
        UUID jurisoneBusinessId = null;
        String rawId = input == null || input.jurisoneBusinessId() == null ? "" : input.jurisoneBusinessId().trim();
        if (rawId.isEmpty()) {
            validator.reject("jurisoneBusinessId", "The JurisOne business id is required.");
        } else {
            try {
                jurisoneBusinessId = UUID.fromString(rawId);
            } catch (IllegalArgumentException e) {
                validator.reject("jurisoneBusinessId", "The JurisOne business id is not valid.");
            }
        }
        validator.throwIfInvalid();
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (jurisoneBusinessId.equals(business.getJurisoneBusinessId())) return findDetail(businessId);
        if (business.getJurisoneBusinessId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.BUSINESS_ALREADY_LINKED, "This business is already linked to another JurisOne business. Unlink it first.");
        }
        if (businessRepository.findByJurisoneBusinessId(jurisoneBusinessId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.JURISONE_BUSINESS_IN_USE, "Another business is already linked to that JurisOne business.");
        }
        business.setJurisoneBusinessId(jurisoneBusinessId);
        businessRepository.saveAndFlush(business);
        auditService.record(new AuditEntry(businessId, superAdmin.userId(), null, AuditAction.BUSINESS_LINKED, BUSINESS_ENTITY, businessId.toString(),
                null, Map.of("jurisoneBusinessId", jurisoneBusinessId.toString()), null));
        return findDetail(businessId);
    }

    /** Removes the link to JurisOne ({@code DELETE /api/businesses/{businessId}/jurisone-link}); a business that is not linked is left as it is. */
    @Transactional
    public BusinessDetail unlinkFromJurisone(CurrentUser superAdmin, UUID businessId) {
        Business business = businessRepository.findById(businessId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
        UUID jurisoneBusinessId = business.getJurisoneBusinessId();
        if (jurisoneBusinessId != null) {
            business.setJurisoneBusinessId(null);
            businessRepository.saveAndFlush(business);
            auditService.record(new AuditEntry(businessId, superAdmin.userId(), null, AuditAction.BUSINESS_UNLINKED, BUSINESS_ENTITY, businessId.toString(),
                    Map.of("jurisoneBusinessId", jurisoneBusinessId.toString()), null, null));
        }
        return findDetail(businessId);
    }

    // ── owner ──

    /** The owner's own business. */
    @Transactional(readOnly = true)
    public BusinessSettings findSettings(CurrentUser owner) {
        return settingsOf(loadOwnBusiness(owner));
    }

    /**
     * The owner changes their business's settings (see the class comment). A new time zone applies to workdays started from now on;
     * workdays already recorded keep their date and zone.
     */
    @Transactional
    public BusinessSettings updateSettings(CurrentUser owner, BusinessChanges changes) {
        Business business = loadOwnBusiness(owner);
        applyChanges(owner, business, changes);
        return settingsOf(business);
    }

    private Business loadOwnBusiness(CurrentUser owner) {
        return businessRepository.findById(owner.businessId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    private void applyChanges(CurrentUser actor, Business business, BusinessChanges changes) {
        if (changes == null) return;
        Map<String, Object> before = businessDetails(business);
        InputValidator validator = new InputValidator();
        String name = changes.name() == null ? business.getName()
                : validator.requiredText("name", "Business name", changes.name().orElse(null), InputValidator.MAXIMUM_BUSINESS_NAME_LENGTH);
        String timeZone = changes.timeZone() == null ? business.getTimeZone() : validator.timeZone("timeZone", changes.timeZone().orElse(null));
        Integer staleWorkdayHours = changes.staleWorkdayHours() == null ? Integer.valueOf(business.getStaleWorkdayHours())
                : validator.wholeNumber("staleWorkdayHours", "Stale workday hours", changes.staleWorkdayHours().orElse(null),
                        MINIMUM_STALE_WORKDAY_HOURS, MAXIMUM_STALE_WORKDAY_HOURS);
        List<String> displayTimeZones = changes.displayTimeZones() == null ? business.getDisplayTimeZones()
                : displayTimeZones(changes.displayTimeZones().orElse(null), validator);
        validator.throwIfInvalid();

        business.setName(name);
        business.setTimeZone(timeZone);
        business.setStaleWorkdayHours(staleWorkdayHours);
        business.setDisplayTimeZones(new ArrayList<>(displayTimeZones));
        Map<String, Object> after = businessDetails(business);
        if (!after.equals(before)) {
            businessRepository.saveAndFlush(business);
            auditService.record(new AuditEntry(business.getId(), actor.userId(), null, AuditAction.BUSINESS_UPDATED, BUSINESS_ENTITY,
                    business.getId().toString(), before, after, null));
        }
    }

    /** Each zone checked and made canonical, duplicates dropped (first one kept), at most {@value #MAXIMUM_DISPLAY_TIME_ZONES}. */
    private static List<String> displayTimeZones(List<String> rawTimeZones, InputValidator validator) {
        if (rawTimeZones == null) {
            validator.reject("displayTimeZones", "Display time zones are required (send [] for none).");
            return List.of();
        }
        if (rawTimeZones.size() > MAXIMUM_SENT_DISPLAY_TIME_ZONES) {
            // bounded before any zone is resolved
            validator.reject("displayTimeZones", "Send at most " + MAXIMUM_SENT_DISPLAY_TIME_ZONES + " display time zones.");
            return List.of();
        }
        Set<String> timeZones = new LinkedHashSet<>();
        for (int i = 0; i < rawTimeZones.size(); i++) {
            String timeZone = validator.timeZone("displayTimeZones[" + i + "]", rawTimeZones.get(i));
            if (timeZone != null) timeZones.add(timeZone);
        }
        if (timeZones.size() > MAXIMUM_DISPLAY_TIME_ZONES) {
            validator.reject("displayTimeZones", "Offer at most " + MAXIMUM_DISPLAY_TIME_ZONES + " display time zones.");
        }
        return List.copyOf(timeZones);
    }

    private static BusinessSettings settingsOf(Business business) {
        return new BusinessSettings(business.getId(), business.getName(), business.getTimeZone(), business.getStaleWorkdayHours(),
                List.copyOf(business.getDisplayTimeZones()), business.getCreatedAt(), business.getUpdatedAt());
    }

    private static Map<String, Object> businessDetails(Business business) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", business.getName());
        details.put("timeZone", business.getTimeZone());
        details.put("staleWorkdayHours", business.getStaleWorkdayHours());
        details.put("displayTimeZones", List.copyOf(business.getDisplayTimeZones()));
        return details;
    }
}

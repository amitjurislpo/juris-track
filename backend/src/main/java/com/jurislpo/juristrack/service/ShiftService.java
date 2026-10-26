package com.jurislpo.juristrack.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jurislpo.juristrack.common.InputValidator;
import com.jurislpo.juristrack.common.PatchBody;
import com.jurislpo.juristrack.engine.ShiftSchedule;
import com.jurislpo.juristrack.entity.Shift;
import com.jurislpo.juristrack.exception.ApiException;
import com.jurislpo.juristrack.exception.OutOfScopeException;
import com.jurislpo.juristrack.exception.Problems;
import com.jurislpo.juristrack.repository.ShiftAssignmentRepository;
import com.jurislpo.juristrack.repository.ShiftRepository;
import com.jurislpo.juristrack.security.CurrentUser;
import com.jurislpo.juristrack.service.AuditService.AuditEntry;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business's shift templates (owner writes, everybody of the business reads). A shift has a start and an end on the business
 * time zone's wall clock (an end at or before the start means the next day) and the minutes before the start and after the end in
 * which work still belongs to it. The default shift "General day" (the calendar day) always exists and cannot be deactivated;
 * changing a shift affects future occurrences only (every workday keeps the snapshot it started with). Every write is audited.
 */
@Service
public class ShiftService {

    /** {@code POST /api/shifts} body: times as {@code HH:mm}; the grace minutes default to 180. */
    public record ShiftInput(String name, String startTime, String endTime, Integer earlyStartMinutes, Integer lateEndMinutes) {}

    /** {@code PUT /api/shifts/{shiftId}/status} body. */
    public record ShiftStatusChange(@JsonProperty("isActive") Boolean isActive) {}

    /**
     * A shift as the API shows it.
     *
     * @param scheduledMinutes the nominal length (24 hours when the start equals the end)
     * @param employeeCount    active employees whose current or pending assignment is this shift (the default shift also counts those
     *                         with no assignment)
     */
    public record ShiftRow(UUID id, String name, String startTime, String endTime, boolean crossesMidnight, int scheduledMinutes,
            int earlyStartMinutes, int lateEndMinutes, @JsonProperty("isDefault") boolean isDefault, @JsonProperty("isActive") boolean isActive,
            long employeeCount) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SHIFT_ENTITY = "shift";
    private static final String NOT_FOUND = "Shift not found.";

    private final ShiftRepository shiftRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final AuditService auditService;

    public ShiftService(ShiftRepository shiftRepository, ShiftAssignmentRepository shiftAssignmentRepository, AuditService auditService) {
        this.shiftRepository = shiftRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.auditService = auditService;
    }

    /** The business's shifts, the default first, then by start time; {@code isActive} (true / false) filters. 400 for another value. */
    @Transactional(readOnly = true)
    public List<ShiftRow> list(CurrentUser user, Map<String, String> queryParameters) {
        InputValidator validator = new InputValidator();
        Boolean isActive = switch (queryParameters.getOrDefault("isActive", "").trim().toLowerCase(Locale.ROOT)) {
            case "" -> null;
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> {
                validator.reject("isActive", "isActive is true or false.");
                yield null;
            }
        };
        validator.throwIfInvalid();
        return shiftRepository.findByBusinessId(user.businessId()).stream().filter(shift -> isActive == null || shift.isActive() == isActive)
                .sorted(Comparator.comparing(Shift::isDefault).reversed().thenComparing(Shift::getStartTime).thenComparing(Shift::getName))
                .map(shift -> rowOf(user.businessId(), shift)).toList();
    }

    @Transactional(readOnly = true)
    public ShiftRow find(CurrentUser user, UUID shiftId) {
        return rowOf(user.businessId(), loadShift(user.businessId(), shiftId));
    }

    /** Creates a shift (active, not the default); returns its id. 400 for bad input, 409 {@code SHIFT_NAME_IN_USE}. */
    @Transactional
    public UUID create(CurrentUser owner, ShiftInput input) {
        ShiftInput shiftInput = input == null ? new ShiftInput(null, null, null, null, null) : input;
        InputValidator validator = new InputValidator();
        String name = validator.requiredText("name", "Name", shiftInput.name(), InputValidator.MAXIMUM_NAME_LENGTH);
        LocalTime startTime = readTime(validator, "startTime", shiftInput.startTime());
        LocalTime endTime = readTime(validator, "endTime", shiftInput.endTime());
        int earlyStartMinutes = readGrace(validator, "earlyStartMinutes", shiftInput.earlyStartMinutes());
        int lateEndMinutes = readGrace(validator, "lateEndMinutes", shiftInput.lateEndMinutes());
        validator.throwIfInvalid();
        requireFreeName(owner.businessId(), name, null);

        Shift shift = new Shift();
        shift.setBusinessId(owner.businessId());
        shift.setName(name);
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setEarlyStartMinutes(earlyStartMinutes);
        shift.setLateEndMinutes(lateEndMinutes);
        shiftRepository.saveAndFlush(shift);
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), null, AuditAction.SHIFT_CREATED, SHIFT_ENTITY,
                shift.getId().toString(), null, details(shift), null));
        return shift.getId();
    }

    /**
     * Changes the fields that were sent (name, startTime, endTime, earlyStartMinutes, lateEndMinutes). Nothing changed writes no
     * audit entry. The default shift keeps its times (it is the calendar day); only its name can change.
     */
    @Transactional
    public ShiftRow update(CurrentUser owner, UUID shiftId, PatchBody body) {
        Shift shift = loadShift(owner.businessId(), shiftId);
        Optional<String> name = body.field("name", String.class);
        Optional<String> startTime = body.field("startTime", String.class);
        Optional<String> endTime = body.field("endTime", String.class);
        Optional<Integer> earlyStartMinutes = body.field("earlyStartMinutes", Integer.class);
        Optional<Integer> lateEndMinutes = body.field("lateEndMinutes", Integer.class);
        body.throwIfInvalid();
        Map<String, Object> before = details(shift);
        InputValidator validator = new InputValidator();
        String newName = name == null ? shift.getName() : validator.requiredText("name", "Name", name.orElse(null), InputValidator.MAXIMUM_NAME_LENGTH);
        LocalTime newStart = startTime == null ? shift.getStartTime() : readTime(validator, "startTime", startTime.orElse(null));
        LocalTime newEnd = endTime == null ? shift.getEndTime() : readTime(validator, "endTime", endTime.orElse(null));
        int newEarly = earlyStartMinutes == null ? shift.getEarlyStartMinutes() : readGrace(validator, "earlyStartMinutes", earlyStartMinutes.orElse(null));
        int newLate = lateEndMinutes == null ? shift.getLateEndMinutes() : readGrace(validator, "lateEndMinutes", lateEndMinutes.orElse(null));
        if (shift.isDefault() && (!newStart.equals(shift.getStartTime()) || !newEnd.equals(shift.getEndTime()) || newEarly != shift.getEarlyStartMinutes()
                || newLate != shift.getLateEndMinutes())) {
            validator.reject("startTime", "The default shift is the calendar day; only its name can change.");
        }
        validator.throwIfInvalid();
        if (!newName.equalsIgnoreCase(shift.getName())) requireFreeName(owner.businessId(), newName, shiftId);

        shift.setName(newName);
        shift.setStartTime(newStart);
        shift.setEndTime(newEnd);
        shift.setEarlyStartMinutes(newEarly);
        shift.setLateEndMinutes(newLate);
        Map<String, Object> after = details(shift);
        if (!after.equals(before)) {
            shiftRepository.saveAndFlush(shift);
            auditService.record(new AuditEntry(owner.businessId(), owner.userId(), null, AuditAction.SHIFT_UPDATED, SHIFT_ENTITY, shiftId.toString(),
                    before, after, null));
        }
        return rowOf(owner.businessId(), shift);
    }

    /**
     * Deactivates or reactivates a shift. 422 {@code DEFAULT_SHIFT_REQUIRED} for the default shift, 422 {@code SHIFT_IN_USE} while
     * active employees work it. Setting the state it already has changes nothing.
     */
    @Transactional
    public ShiftRow setStatus(CurrentUser owner, UUID shiftId, ShiftStatusChange change) {
        Shift shift = loadShift(owner.businessId(), shiftId);
        if (change == null || change.isActive() == null) {
            InputValidator validator = new InputValidator();
            validator.reject("isActive", "Say whether the shift is active (isActive).");
            validator.throwIfInvalid();
        }
        boolean isActive = change.isActive();
        if (isActive == shift.isActive()) return rowOf(owner.businessId(), shift);
        if (!isActive) {
            if (shift.isDefault()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.DEFAULT_SHIFT_REQUIRED, "The default shift cannot be deactivated.");
            }
            if (shiftAssignmentRepository.countActiveEmployeesOfShift(owner.businessId(), shiftId) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SHIFT_IN_USE,
                        "Active employees still work this shift. Give them another shift first.");
            }
        }
        shift.setActive(isActive);
        shiftRepository.saveAndFlush(shift);
        auditService.record(new AuditEntry(owner.businessId(), owner.userId(), null, AuditAction.SHIFT_STATUS_CHANGED, SHIFT_ENTITY, shiftId.toString(),
                Map.of("isActive", !isActive), Map.of("isActive", isActive), null));
        return rowOf(owner.businessId(), shift);
    }

    /** The shift of this business, active or not; 404 when unknown or of another business. */
    Shift loadShift(UUID businessId, UUID shiftId) {
        return shiftRepository.findById(shiftId).filter(shift -> shift.getBusinessId().equals(businessId))
                .orElseThrow(() -> new OutOfScopeException(SHIFT_ENTITY, NOT_FOUND));
    }

    /** An active shift of this business to give to an employee or put on the roster; 422 {@code SHIFT_NOT_FOUND} / {@code SHIFT_INACTIVE}. */
    Shift requireActiveShift(UUID businessId, UUID shiftId) {
        Shift shift = shiftRepository.findById(shiftId).filter(found -> found.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SHIFT_NOT_FOUND, "Choose a shift of this business."));
        if (!shift.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, Problems.SHIFT_INACTIVE, "The chosen shift is deactivated. Choose an active shift.");
        }
        return shift;
    }

    private void requireFreeName(UUID businessId, String name, UUID excludedShiftId) {
        if (shiftRepository.existsByName(businessId, name, excludedShiftId)) {
            throw new ApiException(HttpStatus.CONFLICT, Problems.SHIFT_NAME_IN_USE, "Another shift has this name.");
        }
    }

    private ShiftRow rowOf(UUID businessId, Shift shift) {
        ShiftSchedule schedule = shift.toSchedule();
        long employeeCount = shiftAssignmentRepository.countActiveEmployeesOfShift(businessId, shift.getId())
                + (shift.isDefault() ? shiftAssignmentRepository.countActiveEmployeesWithoutAssignment(businessId) : 0);
        return new ShiftRow(shift.getId(), shift.getName(), CLOCK.format(shift.getStartTime()), CLOCK.format(shift.getEndTime()),
                schedule.crossesMidnight(), schedule.scheduledMinutes(), shift.getEarlyStartMinutes(), shift.getLateEndMinutes(), shift.isDefault(),
                shift.isActive(), employeeCount);
    }

    static Map<String, Object> details(Shift shift) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", shift.getName());
        details.put("startTime", CLOCK.format(shift.getStartTime()));
        details.put("endTime", CLOCK.format(shift.getEndTime()));
        details.put("earlyStartMinutes", shift.getEarlyStartMinutes());
        details.put("lateEndMinutes", shift.getLateEndMinutes());
        details.put("isDefault", shift.isDefault());
        details.put("isActive", shift.isActive());
        return details;
    }

    static LocalTime readTime(InputValidator validator, String field, String value) {
        try {
            if (value != null && value.trim().length() == 5) return LocalTime.parse(value.trim(), CLOCK);
        } catch (DateTimeParseException e) {
            // reported below
        }
        validator.reject(field, "Send the time as HH:mm, for example 19:30.");
        return LocalTime.MIDNIGHT;
    }

    static int readGrace(InputValidator validator, String field, Integer value) {
        if (value == null) return Shift.DEFAULT_GRACE_MINUTES;
        Integer minutes = validator.wholeNumber(field, "The grace", value, 0, Shift.MAXIMUM_GRACE_MINUTES);
        return minutes == null ? Shift.DEFAULT_GRACE_MINUTES : minutes;
    }
}

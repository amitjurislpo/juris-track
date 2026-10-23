package com.jurislpo.juristrack.service;

import com.jurislpo.juristrack.engine.EmployeeSchedule;
import com.jurislpo.juristrack.engine.ShiftAttribution;
import com.jurislpo.juristrack.engine.ShiftSchedule;
import com.jurislpo.juristrack.entity.Shift;
import com.jurislpo.juristrack.entity.ShiftAssignment;
import com.jurislpo.juristrack.entity.ScheduleEntry;
import com.jurislpo.juristrack.repository.ScheduleEntryRepository;
import com.jurislpo.juristrack.repository.ShiftAssignmentRepository;
import com.jurislpo.juristrack.repository.ShiftRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What each employee is scheduled to work on a shift date (roster entry, else the assigned shift, else the business's default shift
 * which is the calendar day: {@link EmployeeSchedule}) and which shift occurrence an instant belongs to ({@link ShiftAttribution}).
 * Reads the shifts, the assignments and the roster entries of all the people asked for in three queries; call it inside the caller's
 * transaction.
 */
@Component
public class ShiftResolver {

    /** Dates before and after an instant whose roster entries are needed to find its occurrence (see {@link ShiftAttribution}). */
    private static final int DAYS_BEFORE = 3;
    private static final int DAYS_AFTER = 2;

    private final ShiftRepository shiftRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;

    public ShiftResolver(ShiftRepository shiftRepository, ShiftAssignmentRepository shiftAssignmentRepository,
            ScheduleEntryRepository scheduleEntryRepository) {
        this.shiftRepository = shiftRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
        this.scheduleEntryRepository = scheduleEntryRepository;
    }

    /** The shift occurrence {@code instant} belongs to for one employee. */
    public ShiftAttribution attributionOf(UUID businessId, UUID employeeId, Instant instant, ZoneId zone) {
        return attributionsOf(businessId, List.of(employeeId), instant, zone).get(employeeId);
    }

    /** The shift occurrence {@code instant} belongs to for each of these employees. */
    public Map<UUID, ShiftAttribution> attributionsOf(UUID businessId, Collection<UUID> employeeIds, Instant instant, ZoneId zone) {
        LocalDate date = LocalDate.ofInstant(instant, zone);
        Map<UUID, EmployeeSchedule> schedules = schedulesOf(businessId, employeeIds, date.minusDays(DAYS_BEFORE), date.plusDays(DAYS_AFTER));
        Map<UUID, ShiftAttribution> attributions = new HashMap<>();
        schedules.forEach((employeeId, schedule) -> attributions.put(employeeId, ShiftAttribution.of(instant, zone, schedule)));
        return attributions;
    }

    /** The schedule of each employee, with the roster entries from {@code from} to {@code to} loaded. */
    public Map<UUID, EmployeeSchedule> schedulesOf(UUID businessId, Collection<UUID> employeeIds, LocalDate from, LocalDate to) {
        Map<UUID, ShiftSchedule> scheduleById = new HashMap<>();
        ShiftSchedule defaultSchedule = ShiftSchedule.calendarDay();
        for (Shift shift : shiftRepository.findByBusinessId(businessId)) {
            scheduleById.put(shift.getId(), shift.toSchedule());
            if (shift.isDefault()) defaultSchedule = shift.toSchedule();
        }
        ShiftSchedule fallback = defaultSchedule;
        Map<UUID, List<ShiftAssignment>> assignmentsByEmployeeId = employeeIds.isEmpty() ? Map.of()
                : shiftAssignmentRepository.findByEmployeeIdIn(employeeIds).stream().collect(Collectors.groupingBy(ShiftAssignment::getEmployeeId));
        Map<UUID, List<ScheduleEntry>> entriesByEmployeeId = employeeIds.isEmpty() ? Map.of()
                : scheduleEntryRepository.findByEmployeeIdInAndEntryDateBetween(employeeIds, from, to).stream()
                        .collect(Collectors.groupingBy(ScheduleEntry::getEmployeeId));
        Map<UUID, EmployeeSchedule> schedules = new HashMap<>();
        for (UUID employeeId : employeeIds) {
            EmployeeSchedule schedule = new EmployeeSchedule(fallback);
            for (ShiftAssignment assignment : assignmentsByEmployeeId.getOrDefault(employeeId, List.of())) {
                schedule.assign(assignment.getEffectiveFrom(), scheduleById.getOrDefault(assignment.getShiftId(), fallback));
            }
            for (ScheduleEntry entry : entriesByEmployeeId.getOrDefault(employeeId, List.of())) {
                schedule.setEntry(entry.getEntryDate(), entry.getShiftId() == null ? null : scheduleById.getOrDefault(entry.getShiftId(), fallback));
            }
            schedules.put(employeeId, schedule);
        }
        return schedules;
    }
}

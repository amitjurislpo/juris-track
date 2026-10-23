package com.jurislpo.juristrack.engine;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * What one employee is scheduled to work on each shift date. For a date: the roster entry if there is one (a shift, or a week off), else
 * the shift the employee is assigned from the latest assignment effective by that date, else the business's default shift.
 */
public final class EmployeeSchedule {

    /** Where a day's schedule comes from. */
    public enum Source { ENTRY, ASSIGNMENT, DEFAULT }

    /** One date of the schedule; {@code shift} is null on a week off. */
    public record Day(LocalDate date, ShiftSchedule shift, Source source) {
        public boolean isOff() {
            return shift == null;
        }
    }

    private final ShiftSchedule defaultShift;
    private final NavigableMap<LocalDate, ShiftSchedule> assignments = new TreeMap<>();
    /** The roster entries by date; a null value is a week off. */
    private final Map<LocalDate, ShiftSchedule> entries = new HashMap<>();

    public EmployeeSchedule(ShiftSchedule defaultShift) {
        this.defaultShift = defaultShift;
    }

    public EmployeeSchedule assign(LocalDate effectiveFrom, ShiftSchedule shift) {
        assignments.put(effectiveFrom, shift);
        return this;
    }

    /** A roster entry: {@code shift} null = week off. */
    public EmployeeSchedule setEntry(LocalDate date, ShiftSchedule shift) {
        entries.put(date, shift);
        return this;
    }

    /** The shift the employee is assigned on this date, ignoring the roster entries. */
    public ShiftSchedule assignedOn(LocalDate date) {
        Map.Entry<LocalDate, ShiftSchedule> assignment = assignments.floorEntry(date);
        return assignment == null ? defaultShift : assignment.getValue();
    }

    public Day dayOn(LocalDate date) {
        if (entries.containsKey(date)) return new Day(date, entries.get(date), Source.ENTRY);
        Map.Entry<LocalDate, ShiftSchedule> assignment = assignments.floorEntry(date);
        return assignment == null ? new Day(date, defaultShift, Source.DEFAULT) : new Day(date, assignment.getValue(), Source.ASSIGNMENT);
    }
}

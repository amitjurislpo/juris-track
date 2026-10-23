package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The shift occurrence an instant belongs to (a START event, or "now" when reading).
 *
 * @param occurrence     the occurrence; on a week off it is the one the employee's assigned shift would have had that date
 * @param isOutsideShift true when the instant is in no occurrence's attribution window: the work is allowed and counted, and the
 *                       workday is flagged
 * @param isOffDay       the shift date is a week off for the employee (the roster has no occurrence): all of the work is extra time
 */
public record ShiftAttribution(ShiftOccurrence occurrence, boolean isOutsideShift, boolean isOffDay) {

    /** How many dates around the instant's date are searched: enough for a grace of at most 12 hours and a shift of at most 24 hours. */
    private static final int DAYS_BEFORE = 2;
    private static final int DAYS_AFTER = 1;

    /**
     * Rules, over the occurrences of the employee's effective schedule (roster entry, else assignment, else the default shift):
     * <ol>
     *   <li>the occurrence whose attribution window contains the instant; if several do, the one whose scheduled start is nearest
     *       (the earlier date on a tie);</li>
     *   <li>none does and the instant's date is a week off: the occurrence the assigned shift would have had on that date, flagged
     *       outside the shift and as an off day;</li>
     *   <li>none does (a gap between shifts): the occurrence nearest to the instant, flagged outside the shift.</li>
     * </ol>
     * Work is never refused. For the default shift this is the calendar date.
     */
    public static ShiftAttribution of(Instant instant, ZoneId zone, EmployeeSchedule schedule) {
        LocalDate date = LocalDate.ofInstant(instant, zone);
        List<ShiftOccurrence> candidates = new ArrayList<>();
        for (LocalDate candidateDate = date.minusDays(DAYS_BEFORE); !candidateDate.isAfter(date.plusDays(DAYS_AFTER)); candidateDate = candidateDate.plusDays(1)) {
            ShiftSchedule shift = schedule.dayOn(candidateDate).shift();
            if (shift != null) candidates.add(shift.occurrenceOn(candidateDate, zone));
        }
        List<ShiftOccurrence> containing = candidates.stream().filter(occurrence -> occurrence.isAttributable(instant)).toList();
        if (!containing.isEmpty()) {
            return new ShiftAttribution(containing.stream()
                    .min(Comparator.comparingLong((ShiftOccurrence occurrence) -> occurrence.secondsFromScheduledStart(instant))
                            .thenComparing(ShiftOccurrence::date)).orElseThrow(), false, false);
        }
        if (schedule.dayOn(date).isOff()) {
            return new ShiftAttribution(schedule.assignedOn(date).occurrenceOn(date, zone), true, true);
        }
        return new ShiftAttribution(candidates.stream()
                .min(Comparator.comparingLong((ShiftOccurrence occurrence) -> occurrence.secondsFromScheduledWindow(instant))
                        .thenComparing(ShiftOccurrence::date)).orElseThrow(), true, false);
    }
}

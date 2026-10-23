package com.jurislpo.juristrack.engine;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A shift template as the engine sees it: a start and an end time on the business's wall clock (an end at or before the start means
 * the shift ends on the next day; start = end is a full 24 hours) and the minutes before the start and after the end in which work
 * still belongs to the shift (the attribution grace). The default "General day" shift (00:00-00:00, no grace) is the calendar day.
 *
 * @param id                the shift, null for the built-in calendar day used when a business has no default shift row
 * @param earlyStartMinutes work that starts up to this long before the scheduled start belongs to the shift
 * @param lateEndMinutes    work that starts up to this long after the scheduled end still belongs to it
 */
public record ShiftSchedule(UUID id, String name, LocalTime startTime, LocalTime endTime, int earlyStartMinutes, int lateEndMinutes) {

    public static final String DEFAULT_NAME = "General day";

    /** The calendar day: 00:00 to 24:00, no grace. */
    public static ShiftSchedule calendarDay() {
        return new ShiftSchedule(null, DEFAULT_NAME, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 0, 0);
    }

    /** True when the shift ends on the next calendar day (also the 24-hour shift). */
    public boolean crossesMidnight() {
        return !endTime.isAfter(startTime);
    }

    /** The nominal length in minutes (end minus start, 24 hours when they are equal); a real occurrence differs by a DST change. */
    public int scheduledMinutes() {
        long minutes = Duration.between(startTime, endTime).toMinutes();
        return (int) (minutes <= 0 ? minutes + 24 * 60 : minutes);
    }

    /** The occurrence that starts on {@code date} (the shift date) in the business time zone. */
    public ShiftOccurrence occurrenceOn(LocalDate date, ZoneId zone) {
        LocalDate endDate = crossesMidnight() ? date.plusDays(1) : date;
        return new ShiftOccurrence(this, date, date.atTime(startTime).atZone(zone).toInstant(), endDate.atTime(endTime).atZone(zone).toInstant());
    }
}

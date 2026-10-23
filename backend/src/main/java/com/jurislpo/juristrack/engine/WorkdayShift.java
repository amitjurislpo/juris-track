package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The shift a workday was attributed to, as it was when the workday started (a snapshot: changing the shift later does not change it).
 *
 * @param id               the shift, null for the built-in calendar day
 * @param startTime        the scheduled start on the wall clock of the business time zone, {@code HH:mm}
 * @param endTime          the scheduled end, {@code HH:mm} (the next day when it is not after the start)
 * @param scheduledSeconds the real length of this occurrence (a DST change makes it differ from the nominal length)
 */
public record WorkdayShift(UUID id, String name, String startTime, String endTime, Instant scheduledStartAt, Instant scheduledEndAt,
        long scheduledSeconds) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** The snapshot of a stored workday's shift, or null when the workday has none (a record written before shifts existed). */
    public static WorkdayShift of(UUID id, String name, Instant scheduledStartAt, Instant scheduledEndAt, ZoneId zone) {
        if (scheduledStartAt == null || scheduledEndAt == null) return null;
        return new WorkdayShift(id, name == null ? ShiftSchedule.DEFAULT_NAME : name, CLOCK.format(scheduledStartAt.atZone(zone)),
                CLOCK.format(scheduledEndAt.atZone(zone)), scheduledStartAt, scheduledEndAt, ChronoUnit.SECONDS.between(scheduledStartAt, scheduledEndAt));
    }

    public static WorkdayShift of(ShiftOccurrence occurrence, ZoneId zone) {
        return of(occurrence.shift().id(), occurrence.shift().name(), occurrence.scheduledStartAt(), occurrence.scheduledEndAt(), zone);
    }

    /** Productive time minus the scheduled length: positive is over the schedule. */
    public long varianceOf(long productiveSeconds) {
        return productiveSeconds - scheduledSeconds;
    }
}

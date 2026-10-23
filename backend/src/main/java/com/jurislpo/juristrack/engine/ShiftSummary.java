package com.jurislpo.juristrack.engine;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** A shift in short: what a person's row shows as their current shift. Times are {@code HH:mm} on the business time zone's wall clock. */
public record ShiftSummary(UUID id, String name, String startTime, String endTime) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    public static ShiftSummary of(ShiftSchedule shift) {
        return new ShiftSummary(shift.id(), shift.name(), CLOCK.format(shift.startTime()), CLOCK.format(shift.endTime()));
    }
}

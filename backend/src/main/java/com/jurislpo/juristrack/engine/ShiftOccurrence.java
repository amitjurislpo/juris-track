package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * One occurrence of a shift: the shift on its shift date, with the scheduled window as instants (DST-aware). Work that starts inside
 * the attribution window (the scheduled window widened by the shift's grace; the end is not included) belongs to the occurrence.
 */
public record ShiftOccurrence(ShiftSchedule shift, LocalDate date, Instant scheduledStartAt, Instant scheduledEndAt) {

    public Instant attributionStart() {
        return scheduledStartAt.minus(shift.earlyStartMinutes(), ChronoUnit.MINUTES);
    }

    public Instant attributionEnd() {
        return scheduledEndAt.plus(shift.lateEndMinutes(), ChronoUnit.MINUTES);
    }

    public boolean isAttributable(Instant instant) {
        return !instant.isBefore(attributionStart()) && instant.isBefore(attributionEnd());
    }

    /** 0 inside the scheduled window, else the seconds to its nearest edge. */
    long secondsFromScheduledWindow(Instant instant) {
        if (instant.isBefore(scheduledStartAt)) return ChronoUnit.SECONDS.between(instant, scheduledStartAt);
        if (instant.isAfter(scheduledEndAt)) return ChronoUnit.SECONDS.between(scheduledEndAt, instant);
        return 0;
    }

    long secondsFromScheduledStart(Instant instant) {
        return Math.abs(ChronoUnit.SECONDS.between(scheduledStartAt, instant));
    }
}

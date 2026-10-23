package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durations of work and break intervals, always derived from the recorded timestamps and never from a counter: a closed interval
 * lasts {@code endedAt - startedAt}, an open one {@code now - startedAt}, and the totals are sums of those. Ported literally from the
 * old app's {@code src/lib/time/calc.ts} and checked against golden cases generated from it ({@code golden/time-engine.json}).
 *
 * <p>The arithmetic is exactly the JavaScript one: instants are compared in whole milliseconds (a JavaScript {@code Date} has no
 * finer precision, so anything below a millisecond is dropped), the difference is floored to whole seconds
 * ({@code Math.floor(milliseconds / 1000)}, i.e. towards negative infinity) and clamped at zero ({@code Math.max(0, ...)}), so an
 * inverted interval or a clock that runs behind counts as 0 seconds.
 */
public final class IntervalMath {

    private static final long MILLISECONDS_PER_SECOND = 1000;

    private IntervalMath() {
    }

    /** Whole seconds an interval lasted (closed) or has lasted until {@code now} (open); never negative. */
    public static long intervalSeconds(TimeInterval interval, Instant now) {
        long startMilliseconds = interval.startedAt().toEpochMilli();
        long endMilliseconds = interval.isOpen() ? now.toEpochMilli() : interval.endedAt().toEpochMilli();
        return Math.max(0, Math.floorDiv(endMilliseconds - startMilliseconds, MILLISECONDS_PER_SECOND));
    }

    /** Sum of {@link #intervalSeconds} over all intervals, open ones counted until {@code now}. */
    public static long sumSeconds(List<TimeInterval> intervals, Instant now) {
        long total = 0;
        for (TimeInterval interval : intervals) {
            total += intervalSeconds(interval, now);
        }
        return total;
    }

    /** Sum of the closed intervals only; an open interval contributes 0. */
    public static long closedSeconds(List<TimeInterval> intervals) {
        long total = 0;
        for (TimeInterval interval : intervals) {
            if (!interval.isOpen()) {
                total += intervalSeconds(interval, Instant.EPOCH);
            }
        }
        return total;
    }

    /** The first interval that is still running, if any. */
    public static Optional<TimeInterval> findOpenInterval(List<TimeInterval> intervals) {
        return intervals.stream().filter(TimeInterval::isOpen).findFirst();
    }

    /**
     * Totals of a workday: productive = sum of the work sessions, break = sum of the break sessions, span = the workday interval
     * itself, break count = number of break sessions (open or closed).
     */
    public static WorkdayTotals computeTotals(TimeInterval workday, List<TimeInterval> workSessions, List<TimeInterval> breakSessions,
            Instant now) {
        return new WorkdayTotals(
                sumSeconds(workSessions, now),
                sumSeconds(breakSessions, now),
                intervalSeconds(workday, now),
                breakSessions.size());
    }
}

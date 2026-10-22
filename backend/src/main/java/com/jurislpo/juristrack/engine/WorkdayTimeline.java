package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds what a workday looks like at a given moment (its snapshot: totals, running intervals, last activity, timeline, the
 * "not ended" flag) from its recorded intervals only. Ported from the old app's {@code buildSnapshot}, {@code buildSegments} and
 * {@code lastActivityOf}; every duration comes from {@link IntervalMath}.
 */
public final class WorkdayTimeline {

    private static final long SECONDS_PER_HOUR = 3600;

    private WorkdayTimeline() {
    }

    /**
     * The snapshot of a workday as of {@code now}.
     *
     * @param status            WORKING, ON_BREAK or DAY_ENDED
     * @param endedAt           null while the day is open
     * @param workSegments      the work sessions (any order)
     * @param breakSegments     the break sessions (any order)
     * @param staleWorkdayHours the business's threshold for "not ended"
     */
    public static WorkdaySnapshot snapshot(UUID workdayId, LocalDate date, TrackingStatus status, Instant startedAt, Instant endedAt,
            List<TimelineSegment> workSegments, List<TimelineSegment> breakSegments, Instant now, int staleWorkdayHours) {
        List<TimeInterval> workIntervals = intervalsOf(workSegments);
        List<TimeInterval> breakIntervals = intervalsOf(breakSegments);
        WorkdayTotals totals = IntervalMath.computeTotals(new TimeInterval(startedAt, endedAt), workIntervals, breakIntervals, now);
        List<TimelineSegment> segments = orderedSegments(workSegments, breakSegments);
        boolean isOpen = status != TrackingStatus.DAY_ENDED;
        return new WorkdaySnapshot(workdayId, date, status, startedAt, endedAt, isStale(isOpen, totals.spanSeconds(), staleWorkdayHours),
                IntervalMath.closedSeconds(workIntervals), IntervalMath.closedSeconds(breakIntervals),
                IntervalMath.findOpenInterval(workIntervals).map(TimeInterval::startedAt).orElse(null),
                IntervalMath.findOpenInterval(breakIntervals).map(TimeInterval::startedAt).orElse(null),
                totals.productiveSeconds(), totals.breakSeconds(), totals.spanSeconds(), totals.breakCount(),
                lastActivity(startedAt, endedAt, segments), segments);
    }

    /** Work and break segments together, oldest first (a work and a break starting at the same instant: work first). */
    public static List<TimelineSegment> orderedSegments(List<TimelineSegment> workSegments, List<TimelineSegment> breakSegments) {
        List<TimelineSegment> segments = new ArrayList<>(workSegments);
        segments.addAll(breakSegments);
        segments.sort(Comparator.comparing(TimelineSegment::startedAt));
        return segments;
    }

    /**
     * The last activity: the end of the day once it ended; otherwise the start of the last segment (a break, the first work
     * session, or a resumed one); the start of the day when there is no segment at all.
     */
    public static LastActivity lastActivity(Instant startedAt, Instant endedAt, List<TimelineSegment> orderedSegments) {
        if (endedAt != null) return new LastActivity(ActivityType.ENDED_DAY, endedAt);
        if (orderedSegments.isEmpty()) return new LastActivity(ActivityType.STARTED_WORK, startedAt);
        TimelineSegment last = orderedSegments.get(orderedSegments.size() - 1);
        if (last.type() == SegmentType.BREAK) return new LastActivity(ActivityType.STARTED_BREAK, last.startedAt());
        return new LastActivity(orderedSegments.size() == 1 ? ActivityType.STARTED_WORK : ActivityType.RESUMED_WORK, last.startedAt());
    }

    /**
     * An open workday whose span is longer than {@code staleWorkdayHours} ("not ended": probably forgotten). Duration-based only:
     * crossing midnight is normal for a night shift.
     */
    public static boolean isStale(boolean isOpen, long spanSeconds, int staleWorkdayHours) {
        return isOpen && spanSeconds > staleWorkdayHours * SECONDS_PER_HOUR;
    }

    private static List<TimeInterval> intervalsOf(List<TimelineSegment> segments) {
        return segments.stream().map(segment -> new TimeInterval(segment.startedAt(), segment.endedAt())).toList();
    }
}

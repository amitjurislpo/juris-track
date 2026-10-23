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
        long staleSpanSeconds = IntervalMath.intervalSeconds(new TimeInterval(currentStretchStart(startedAt, workIntervals, breakIntervals, now), endedAt), now);
        return new WorkdaySnapshot(workdayId, date, status, startedAt, endedAt, isStale(isOpen, staleSpanSeconds, staleWorkdayHours),
                IntervalMath.closedSeconds(workIntervals), IntervalMath.closedSeconds(breakIntervals),
                IntervalMath.findOpenInterval(workIntervals).map(TimeInterval::startedAt).orElse(null),
                IntervalMath.findOpenInterval(breakIntervals).map(TimeInterval::startedAt).orElse(null),
                totals.productiveSeconds(), totals.breakSeconds(), totals.spanSeconds(), totals.breakCount(),
                lastBreakOf(breakSegments), lastActivity(startedAt, endedAt, segments), segments);
    }

    /** The break that started last (null when there is none); its end is null while it runs. */
    public static LastBreak lastBreakOf(List<TimelineSegment> breakSegments) {
        return breakSegments.stream().max(Comparator.comparing(TimelineSegment::startedAt))
                .map(segment -> new LastBreak(segment.startedAt(), segment.endedAt())).orElse(null);
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
     * Where the day's last uninterrupted stretch began: the start of the day, or after a gap that no work or break interval covers
     * (a day that ended and was started again: the closed hours in between are not part of what was forgotten). "Not ended" is
     * measured from here, so a day reopened late in the evening is not flagged at once because of the morning.
     */
    static Instant currentStretchStart(Instant startedAt, List<TimeInterval> workIntervals, List<TimeInterval> breakIntervals, Instant now) {
        List<TimeInterval> intervals = new ArrayList<>(workIntervals);
        intervals.addAll(breakIntervals);
        intervals.sort(Comparator.comparing(TimeInterval::startedAt));
        Instant stretchStart = startedAt;
        Instant coveredUntil = startedAt;
        for (TimeInterval interval : intervals) {
            if (interval.startedAt().isAfter(coveredUntil)) stretchStart = interval.startedAt();
            Instant intervalEnd = interval.isOpen() ? now : interval.endedAt();
            if (intervalEnd.isAfter(coveredUntil)) coveredUntil = intervalEnd;
        }
        return stretchStart;
    }

    /**
     * An open workday whose last uninterrupted stretch ({@code staleSeconds}, see {@link #currentStretchStart}) is longer than
     * {@code staleWorkdayHours} ("not ended": probably forgotten). Duration-based only: crossing midnight is normal for a night shift.
     */
    public static boolean isStale(boolean isOpen, long staleSeconds, int staleWorkdayHours) {
        return isOpen && staleSeconds > staleWorkdayHours * SECONDS_PER_HOUR;
    }

    private static List<TimeInterval> intervalsOf(List<TimelineSegment> segments) {
        return segments.stream().map(segment -> new TimeInterval(segment.startedAt(), segment.endedAt())).toList();
    }
}

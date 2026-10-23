package com.jurislpo.juristrack.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link WorkdayTimeline}: snapshot figures, segment order, last activity and the "not ended" flag of the old buildSnapshot. */
class WorkdayTimelineTest {

    private static final UUID WORKDAY_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.parse("2026-10-01");

    private static Instant at(String time) {
        return Instant.parse("2026-10-01T" + time + ":00Z");
    }

    private static TimelineSegment work(String start, String end) {
        return new TimelineSegment(UUID.randomUUID(), SegmentType.WORK, at(start), end == null ? null : at(end));
    }

    private static TimelineSegment pause(String start, String end) {
        return new TimelineSegment(UUID.randomUUID(), SegmentType.BREAK, at(start), end == null ? null : at(end));
    }

    @Test
    void anOpenDayOnABreakCountsTheRunningBreakUntilNowAndFreezesWork() {
        List<TimelineSegment> workSegments = List.of(work("09:00", "11:00"));
        List<TimelineSegment> breakSegments = List.of(pause("11:00", null));
        WorkdaySnapshot snapshot = WorkdayTimeline.snapshot(WORKDAY_ID, DATE, TrackingStatus.ON_BREAK, at("09:00"), null, workSegments, breakSegments,
                at("11:20"), 14);
        assertEquals(7200, snapshot.productiveSeconds());
        assertEquals(7200, snapshot.closedProductiveSeconds());
        assertEquals(1200, snapshot.breakSeconds());
        assertEquals(0, snapshot.closedBreakSeconds());
        assertEquals(8400, snapshot.spanSeconds());
        assertEquals(1, snapshot.breakCount());
        assertNull(snapshot.activeWorkStartedAt());
        assertEquals(at("11:00"), snapshot.activeBreakStartedAt());
        assertEquals(new LastActivity(ActivityType.STARTED_BREAK, at("11:00")), snapshot.lastActivity());
        assertFalse(snapshot.isStale());
    }

    @Test
    void anEndedDayUsesItsEndAndTheSegmentsAreOrderedByStart() {
        List<TimelineSegment> workSegments = List.of(work("13:45", "18:00"), work("09:00", "11:15"), work("11:30", "13:00"));
        List<TimelineSegment> breakSegments = List.of(pause("13:00", "13:45"), pause("11:15", "11:30"));
        WorkdaySnapshot snapshot = WorkdayTimeline.snapshot(WORKDAY_ID, DATE, TrackingStatus.DAY_ENDED, at("09:00"), at("18:00"), workSegments,
                breakSegments, at("23:00"), 14);
        assertEquals(8 * 3600, snapshot.productiveSeconds());
        assertEquals(3600, snapshot.breakSeconds());
        assertEquals(9 * 3600, snapshot.spanSeconds());
        assertEquals(List.of(at("09:00"), at("11:15"), at("11:30"), at("13:00"), at("13:45")),
                snapshot.segments().stream().map(TimelineSegment::startedAt).toList());
        assertEquals(new LastActivity(ActivityType.ENDED_DAY, at("18:00")), snapshot.lastActivity());
    }

    @Test
    void theLastActivityIsTheStartOfTheLastSegment() {
        assertEquals(new LastActivity(ActivityType.STARTED_WORK, at("09:00")), WorkdayTimeline.lastActivity(at("09:00"), null, List.of()));
        assertEquals(new LastActivity(ActivityType.STARTED_WORK, at("09:00")),
                WorkdayTimeline.lastActivity(at("09:00"), null, List.of(work("09:00", null))));
        assertEquals(new LastActivity(ActivityType.RESUMED_WORK, at("10:30")), WorkdayTimeline.lastActivity(at("09:00"), null,
                WorkdayTimeline.orderedSegments(List.of(work("09:00", "10:00"), work("10:30", null)), List.of(pause("10:00", "10:30")))));
    }

    @Test
    void aWorkAndABreakStartingAtTheSameInstantKeepWorkFirst() {
        List<TimelineSegment> ordered = WorkdayTimeline.orderedSegments(List.of(work("09:00", "09:00")), List.of(pause("09:00", null)));
        assertEquals(List.of(SegmentType.WORK, SegmentType.BREAK), ordered.stream().map(TimelineSegment::type).toList());
    }

    @Test
    void onlyAnOpenDayLongerThanTheThresholdIsStale() {
        assertFalse(WorkdayTimeline.isStale(true, 14 * 3600, 14));
        assertTrue(WorkdayTimeline.isStale(true, 14 * 3600 + 1, 14));
        assertFalse(WorkdayTimeline.isStale(false, 30 * 3600, 14));
    }
}

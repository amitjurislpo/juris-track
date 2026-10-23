package com.jurislpo.juristrack.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A workday as of {@code now} (the server time of the answer). A client advances the live values with
 * {@code productive = closedProductiveSeconds + (clientNow - activeWorkStartedAt)} and
 * {@code break = closedBreakSeconds + (clientNow - activeBreakStartedAt)}; the database stays the source of truth.
 *
 * @param id                      the workday
 * @param date                    the calendar date in the business time zone when it started (kept when it runs past midnight)
 * @param status                  WORKING, ON_BREAK or DAY_ENDED
 * @param startedAt               when the day started
 * @param endedAt                 when it ended, null while open
 * @param isStale                 still open and its last uninterrupted stretch (since the start, or since it was started again after
 *                                a gap) is longer than the business's stale workday hours ("not ended")
 * @param closedProductiveSeconds the closed work sessions
 * @param closedBreakSeconds      the closed breaks
 * @param activeWorkStartedAt     the running work session's start, or null
 * @param activeBreakStartedAt    the running break's start, or null
 * @param productiveSeconds       all work sessions, the running one counted until now
 * @param breakSeconds            all breaks, the running one counted until now
 * @param spanSeconds             from the start of the day to its end (or now); a reopened day's gap is inside the span (but not in
 *                                "not ended": see {@code isStale})
 * @param breakCount              breaks taken, the running one included
 * @param lastBreak              the break that started last (its end is null while it runs), or null when the day has no break
 * @param lastActivity            the last thing that happened
 * @param segments                every work and break interval, oldest first
 */
public record WorkdaySnapshot(UUID id, LocalDate date, TrackingStatus status, Instant startedAt, Instant endedAt,
        @JsonProperty("isStale") boolean isStale, long closedProductiveSeconds, long closedBreakSeconds, Instant activeWorkStartedAt,
        Instant activeBreakStartedAt, long productiveSeconds, long breakSeconds, long spanSeconds, int breakCount, LastBreak lastBreak,
        LastActivity lastActivity,
        List<TimelineSegment> segments) {
}

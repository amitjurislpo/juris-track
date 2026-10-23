package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.util.UUID;

/**
 * One work or break interval of a workday as the timeline shows it.
 *
 * @param id        the work session's or break session's id
 * @param type      work or break
 * @param startedAt when it started
 * @param endedAt   when it ended, or null while it is running
 */
public record TimelineSegment(UUID id, SegmentType type, Instant startedAt, Instant endedAt) {
}

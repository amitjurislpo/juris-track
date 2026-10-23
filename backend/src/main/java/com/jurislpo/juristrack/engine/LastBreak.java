package com.jurislpo.juristrack.engine;

import java.time.Instant;

/**
 * The most recent break of a workday (the one that started last).
 *
 * @param startedAt when it started
 * @param endedAt   when it ended, or null while it is running
 */
public record LastBreak(Instant startedAt, Instant endedAt) {
}

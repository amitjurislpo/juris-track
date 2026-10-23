package com.jurislpo.juristrack.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * A work or break interval as recorded: when it started and, once closed, when it ended ({@code null} while it is still running).
 * Services build these from the {@code work_sessions} / {@code break_sessions} rows and the workday itself.
 */
public record TimeInterval(Instant startedAt, Instant endedAt) {

    public TimeInterval {
        Objects.requireNonNull(startedAt, "startedAt");
    }

    /** True while the interval is still running (no end recorded yet). */
    public boolean isOpen() {
        return endedAt == null;
    }
}

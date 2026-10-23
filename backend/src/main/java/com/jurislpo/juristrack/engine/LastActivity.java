package com.jurislpo.juristrack.engine;

import java.time.Instant;

/** The last activity of a workday and when it happened. */
public record LastActivity(ActivityType type, Instant at) {
}

package com.jurislpo.juristrack.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Keeps high-volume security events from flooding the audit log: an event with an aggregation key (for example
 * {@code "invalid-session-cookie:invalid_token:203.0.113.7"}) is recorded the first time in each {@link #WINDOW} and not again until
 * the window ends. In memory, like the rate limiter: good for one API instance; restarting the API starts new windows.
 */
@Component
public class AuditAggregation {

    /** One entry per key in this window. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAXIMUM_KEYS = 10_000;

    private final Map<String, Long> windowEndMillisByKey = new ConcurrentHashMap<>();

    /** True for the first event of {@code key} in its window (the caller records it); false while the window lasts. */
    public synchronized boolean isFirstInWindow(String key) {
        long now = System.currentTimeMillis();
        Long windowEndMillis = windowEndMillisByKey.get(key);
        if (windowEndMillis != null && windowEndMillis > now) return false;
        if (windowEndMillisByKey.size() >= MAXIMUM_KEYS) windowEndMillisByKey.values().removeIf(endMillis -> endMillis <= now);
        windowEndMillisByKey.put(key, now + WINDOW.toMillis());
        return true;
    }

    /** Forgets every window (tests). */
    public void clear() {
        windowEndMillisByKey.clear();
    }
}

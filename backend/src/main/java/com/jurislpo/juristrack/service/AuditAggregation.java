package com.jurislpo.juristrack.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Keeps high-volume security events from flooding the audit log: an event with an aggregation key (for example
 * {@code "invalid-session-cookie:invalid_token:203.0.113.7"}) is recorded the first time in each {@link #WINDOW} and not again until
 * the window ends. In memory, like the rate limiter: good for one API instance; restarting the API starts new windows. All windows are
 * equally long, so they end in the order they began: at most {@value #MAXIMUM_KEYS} keys are kept, ended windows are dropped from the
 * oldest end as new keys come, and when the map is still full the oldest key goes (that key may then be recorded again early). No
 * call scans the whole map.
 */
@Component
public class AuditAggregation {

    /** One entry per key in this window. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAXIMUM_KEYS = 10_000;

    /** In order of window start (= order of window end). */
    private final Map<String, Long> windowEndMillisByKey = new LinkedHashMap<>();

    /** True for the first event of {@code key} in its window (the caller records it); false while the window lasts. */
    public synchronized boolean isFirstInWindow(String key) {
        long now = System.currentTimeMillis();
        Long windowEndMillis = windowEndMillisByKey.get(key);
        if (windowEndMillis != null && windowEndMillis > now) return false;
        windowEndMillisByKey.remove(key);
        for (Iterator<Long> oldest = windowEndMillisByKey.values().iterator(); oldest.hasNext();) {
            if (oldest.next() > now) break;
            oldest.remove();
        }
        if (windowEndMillisByKey.size() >= MAXIMUM_KEYS) {
            Iterator<Long> oldest = windowEndMillisByKey.values().iterator();
            oldest.next();
            oldest.remove();
        }
        windowEndMillisByKey.put(key, now + WINDOW.toMillis());
        return true;
    }

    /** Forgets every window (tests). */
    public synchronized void clear() {
        windowEndMillisByKey.clear();
    }
}

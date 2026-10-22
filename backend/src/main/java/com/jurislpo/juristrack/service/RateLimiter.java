package com.jurislpo.juristrack.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in memory, keyed by e.g. {@code "otp-ip:<address>"}. Good for one API instance; several instances
 * would need a shared store. Restarting the API clears them.
 */
@Component
public class RateLimiter {

    private static final int MAXIMUM_KEYS = 10_000;

    private static final class Window {
        int count;
        final long resetsAtMillis;

        Window(long resetsAtMillis) {
            this.count = 1;
            this.resetsAtMillis = resetsAtMillis;
        }
    }

    /** {@code isLimited}: the key has used up its hits; {@code retryInMillis}: until its window ends. */
    public record Result(boolean isLimited, long retryInMillis) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private Window liveWindow(String key) {
        Window window = windows.get(key);
        if (window != null && window.resetsAtMillis <= System.currentTimeMillis()) {
            windows.remove(key);
            return null;
        }
        return window;
    }

    /** Whether {@code key} has used up {@code maximumHits} in its current window (does not count). */
    public synchronized Result check(String key, int maximumHits) {
        Window window = liveWindow(key);
        return window != null && window.count >= maximumHits
                ? new Result(true, window.resetsAtMillis - System.currentTimeMillis())
                : new Result(false, 0);
    }

    /** Counts one hit for {@code key}; a new window lasts {@code windowMillis}. */
    public synchronized void hit(String key, long windowMillis) {
        Window window = liveWindow(key);
        if (window != null) window.count += 1;
        else windows.put(key, new Window(System.currentTimeMillis() + windowMillis));
        if (windows.size() > MAXIMUM_KEYS) for (String windowKey : windows.keySet().toArray(new String[0])) liveWindow(windowKey);
    }

    /** Checks and counts in one step (code requests): {@code isLimited=false} means the call is allowed and was counted. */
    public synchronized Result checkAndHit(String key, int maximumHits, long windowMillis) {
        Result result = check(key, maximumHits);
        if (result.isLimited()) return result;
        hit(key, windowMillis);
        return result;
    }

    /** Forgets every counter (tests). */
    public void clear() {
        windows.clear();
    }
}

package com.jurislpo.juristrack.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in memory, keyed by e.g. {@code "otp-ip:<address>"}. Good for one API instance; several instances
 * would need a shared store. Restarting the API clears them. At most {@value #MAXIMUM_KEYS} keys are kept: windows that ended are
 * dropped from the oldest end as new keys come, and when the map is still full the oldest window goes, so memory is bounded and no
 * call ever scans the whole map (every sign-in request shares this lock).
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

    /** In order of creation, so the oldest windows are at the front. */
    private final Map<String, Window> windows = new LinkedHashMap<>();

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
        if (window != null) {
            window.count += 1;
            return;
        }
        makeRoom();
        windows.put(key, new Window(System.currentTimeMillis() + windowMillis));
    }

    /** Checks and counts in one step: {@code isLimited=false} means the call is allowed and was counted. */
    public synchronized Result checkAndHit(String key, int maximumHits, long windowMillis) {
        Result result = check(key, maximumHits);
        if (result.isLimited()) return result;
        hit(key, windowMillis);
        return result;
    }

    /** Takes one hit back (an attempt that was counted in advance turned out fine). */
    public synchronized void release(String key) {
        Window window = liveWindow(key);
        if (window == null) return;
        window.count -= 1;
        if (window.count <= 0) windows.remove(key);
    }

    /** Before a new key is added: drop ended windows from the oldest end, and the oldest window if the map is still full. */
    private void makeRoom() {
        if (windows.size() < MAXIMUM_KEYS) return;
        long now = System.currentTimeMillis();
        for (Iterator<Window> oldest = windows.values().iterator(); oldest.hasNext() && windows.size() >= MAXIMUM_KEYS;) {
            Window window = oldest.next();
            if (window.resetsAtMillis > now) break;
            oldest.remove();
        }
        if (windows.size() >= MAXIMUM_KEYS) {
            Iterator<Window> oldest = windows.values().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Forgets every counter (tests). */
    public synchronized void clear() {
        windows.clear();
    }
}

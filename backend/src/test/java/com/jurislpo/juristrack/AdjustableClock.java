package com.jurislpo.juristrack;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application clock in the API tests (UTC). It follows the real time until a test sets it ({@link #setTo}), then stands still
 * at that instant and moves only when the test moves it ({@link #advance}). {@link #followSystemTime} goes back to the real time;
 * every test that sets the clock calls it when it ends, because the Spring context (and this clock) is shared by every test class.
 */
public class AdjustableClock extends Clock {

    private final AtomicReference<Instant> fixedInstant = new AtomicReference<>();

    public void setTo(Instant instant) {
        fixedInstant.set(instant);
    }

    public void setTo(String isoInstant) {
        setTo(Instant.parse(isoInstant));
    }

    /** Moves a set clock forward (sets it to the real time first when it was following it). */
    public void advance(Duration duration) {
        fixedInstant.updateAndGet(current -> (current == null ? Instant.now() : current).plus(duration));
    }

    public void followSystemTime() {
        fixedInstant.set(null);
    }

    @Override
    public Instant instant() {
        Instant current = fixedInstant.get();
        return current == null ? Instant.now() : current;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The test clock is always UTC.");
    }
}

package com.porter.wesortmanager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PR3 Step 7 (#142): deterministic test clock with independent wall and
 * monotonic axes.
 *
 * <p>{@link #advance} moves both axes together (normal time flow);
 * {@link #setWall} moves only the wall axis, so tests can prove monotonic
 * leases (which must use {@link #nano}) are immune to wall jumps. {@link #set}
 * moves both axes to an absolute wall instant. {@link #get} is a
 * wall-millis alias kept so existing sweep/presence assertions read naturally.
 */
final class FakeClock implements Clock {
    private final AtomicLong millis;
    private final AtomicLong nano;

    FakeClock(long millis) {
        this.millis = new AtomicLong(millis);
        this.nano = new AtomicLong(millis * 1_000_000L);
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public long nano() {
        return nano.get();
    }

    /** Wall-millis alias for sweep/presence assertions. */
    long get() {
        return millis();
    }

    /** Moves both axes forward (or backward) by {@code ms}. */
    void advance(long ms) {
        millis.addAndGet(ms);
        nano.addAndGet(ms * 1_000_000L);
    }

    /** Moves both axes to an absolute wall instant. */
    void set(long ms) {
        millis.set(ms);
        nano.set(ms * 1_000_000L);
    }

    /** Moves only the wall axis, leaving the monotonic axis untouched. */
    void setWall(long ms) {
        millis.set(ms);
    }
}

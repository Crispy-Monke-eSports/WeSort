package com.porter.wesort.modules;

/** Client-thread-only deadlines using a monotonic clock (milliseconds). */
final class ControlRetrySchedule {
    static final long RECONNECT_MS = 15_000;
    static final long REQUEST_TIMEOUT_MS = 10_000;
    static final long POLL_MS = 2_000;
    static final long VERIFIED_TTL_MS = 35_000;

    private final long pollMs;

    ControlRetrySchedule() {
        this(POLL_MS);
    }

    ControlRetrySchedule(long pollMs) {
        this.pollMs = pollMs;
    }

    private boolean scheduled;
    private boolean started;
    private boolean verified;
    private long nextAt;
    private long startedAt;
    private long verifiedAt;

    boolean due(long now) {
        return !scheduled || now - nextAt >= 0;
    }

    void started(long now) {
        started = scheduled = true;
        startedAt = now;
        nextAt = now + RECONNECT_MS;
    }

    void failed(long now) {
        verified = false;
        if (started) nextAt = startedAt + RECONNECT_MS;
        else if (due(now)) nextAt = now + RECONNECT_MS;
        started = false;
        scheduled = true;
    }

    void succeeded(long now) {
        started = false;
        scheduled = verified = true;
        nextAt = now + pollMs;
        verifiedAt = now;
    }

    boolean recentlyVerified(long now) {
        return verified && now - verifiedAt < VERIFIED_TTL_MS;
    }

    void reset() {
        scheduled = started = verified = false;
    }
}

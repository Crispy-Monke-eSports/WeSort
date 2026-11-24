package com.porter.wesort.modules;

/** Per-channel, bounded retry delays. Only a success on the same channel resets it. */
final class RequestRetry {
    private long delayMs;

    long failureDelayMs() {
        delayMs = delayMs == 0 ? 5_000 : Math.min(60_000, delayMs * 2);
        return delayMs;
    }

    void reset() {
        delayMs = 0;
    }
}

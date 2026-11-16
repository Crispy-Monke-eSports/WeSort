package com.porter.wesort.inventory;

/** Monotonic limits independent of server ticks or Baritone's calculation state. */
public final class NavigationWatchdog {
    private final String dimension;
    private final long started, timeout, stuck;
    private long progressed;
    private double x, y, z;

    public NavigationWatchdog(String dimension, double x, double y, double z, long now, long timeout, long stuck) {
        this.dimension = dimension; this.x = x; this.y = y; this.z = z;
        this.started = this.progressed = now; this.timeout = timeout; this.stuck = stuck;
    }

    public String check(String currentDimension, double x, double y, double z, long now) {
        if (!dimension.equals(currentDimension)) return "PAUSED_WRONG_DIMENSION";
        if (now - started >= timeout) return "PAUSED_NAVIGATION_TIMEOUT";
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        if (dx * dx + dy * dy + dz * dz >= 0.16) {
            this.x = x; this.y = y; this.z = z; progressed = now;
        }
        return now - progressed >= stuck ? "PAUSED_NAVIGATION_STUCK" : "";
    }
}

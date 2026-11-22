package com.porter.wesort.utils;

/**
 * Per-login lobby state. A dimension/world swap counts as a successful portal transit only
 * after an attempted crossing; a fresh login must not inherit the previous login's arrival.
 */
public final class PortalWorldSession {
    private Object initialLevel;
    private String initialDimension = "";
    private boolean crossingAttempted;
    private boolean arrived;

    public boolean started() {
        return initialLevel != null;
    }

    public boolean arrived() {
        return arrived;
    }

    public void crossingAttempted() {
        crossingAttempted = true;
    }

    /** An approach timed out without portal contact; do not count a later unrelated reload as arrival. */
    public void cancelAttempt() {
        if (!arrived) crossingAttempted = false;
    }

    /** Returns true once a crossing is confirmed by a new world or a new dimension. */
    public boolean observe(Object level, String dimension) {
        if (level == null) return false;
        String observedDimension = dimension == null ? "" : dimension;
        if (initialLevel == null) {
            initialLevel = level;
            initialDimension = observedDimension;
        } else if (crossingAttempted && (initialLevel != level || !initialDimension.equals(observedDimension))) {
            arrived = true;
        } else if (initialLevel != level || !initialDimension.equals(observedDimension)) {
            // Unrelated respawn/world reload while still in the lobby: start measuring again.
            initialLevel = level;
            initialDimension = observedDimension;
        }
        return arrived;
    }

    /** Disconnecting/logging out ALWAYS re-arms the lobby entry, even after a previous success. */
    public void reset() {
        initialLevel = null;
        initialDimension = "";
        crossingAttempted = false;
        arrived = false;
    }
}

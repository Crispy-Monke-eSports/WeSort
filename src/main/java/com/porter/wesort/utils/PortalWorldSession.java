package com.porter.wesort.utils;

/**
 * A proxy may move the player from the 6b6t lobby to survival without changing Minecraft
 * dimensions or emitting a client-level change. Require observed lobby evidence before
 * activating portal navigation, and never inherit it across logouts.
 */
public final class PortalWorldSession {
    private static final double PROXY_TRANSFER_DISTANCE_SQR = 128.0 * 128.0;

    private Object initialLevel;
    private String initialDimension = "";
    private boolean crossingAttempted;
    private boolean arrived;
    private boolean lobbyObserved;
    private double lobbyX;
    private double lobbyZ;

    public boolean started() {
        return initialLevel != null;
    }

    public boolean arrived() {
        return arrived;
    }

    /** A server hostname or setting alone does not prove that the player is in the lobby. */
    public boolean inLobby() {
        return lobbyObserved && !arrived;
    }

    /** Called only after finding actual lobby portal blocks in the player's loaded vicinity. */
    public void lobbySeen(double x, double z) {
        if (arrived || !started()) return;
        if (!lobbyObserved) {
            lobbyX = x;
            lobbyZ = z;
        }
        lobbyObserved = true;
    }

    public void crossingAttempted() {
        if (lobbyObserved) crossingAttempted = true;
    }

    /** A timed-out attempt is not evidence that an unrelated reload reached survival. */
    public void cancelAttempt() {
        if (!arrived) crossingAttempted = false;
    }

    public boolean observe(Object level, String dimension) {
        return observe(level, dimension, Double.NaN, Double.NaN);
    }

    /**
     * A confirmed portal attempt plus a world change is arrival. For manually entered
     * portals, a large proxy relocation from a positively identified lobby is equivalent
     * evidence even if the portal contact occurred between client ticks.
     */
    public boolean observe(Object level, String dimension, double x, double z) {
        if (level == null) return arrived;
        String observedDimension = dimension == null ? "" : dimension;
        if (initialLevel == null) {
            initialLevel = level;
            initialDimension = observedDimension;
            return arrived;
        }
        boolean changedWorld = initialLevel != level || !initialDimension.equals(observedDimension);
        boolean relocated = lobbyObserved
                && Double.isFinite(x)
                && Double.isFinite(z)
                && (x - lobbyX) * (x - lobbyX) + (z - lobbyZ) * (z - lobbyZ)
                        >= PROXY_TRANSFER_DISTANCE_SQR;
        if ((changedWorld && crossingAttempted) || relocated) {
            arrived = true;
            lobbyObserved = false;
        } else if (changedWorld) {
            // A respawn or a proxy reload by itself is not proof of entering survival.
            // Reassess nearby portal evidence in the new world instead of inheriting
            // the old lobby state and triggering navigation on a survival worker.
            initialLevel = level;
            initialDimension = observedDimension;
            lobbyObserved = false;
            crossingAttempted = false;
        }
        return arrived;
    }

    public void reset() {
        initialLevel = null;
        initialDimension = "";
        crossingAttempted = false;
        arrived = false;
        lobbyObserved = false;
        lobbyX = lobbyZ = 0;
    }
}

package com.porter.wesort.inventory;

/**
 * Narrow navigation-only exception for joining a server through a lobby portal.
 * It never grants inventory or world mutation permissions.
 */
public final class PortalNavigationPolicy {
    private PortalNavigationPolicy() {}

    public static boolean mayBootstrap(
            boolean dedicatedAgent,
            boolean awaitingPortal,
            boolean portalBlockVerified,
            boolean adjacentApproach,
            boolean approachIsSafe,
            boolean physicalJournalClear) {
        return dedicatedAgent
                && awaitingPortal
                && portalBlockVerified
                && adjacentApproach
                && approachIsSafe
                && physicalJournalClear;
    }

    /** A failed portal route belongs to one login, unlike failures of ordinary warehouse routes. */
    public static boolean clearPauseOnDisconnect(boolean paused, boolean portalRouteFailure) {
        return paused && portalRouteFailure;
    }
}

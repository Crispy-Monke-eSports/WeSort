package com.porter.wesort.inventory;

/**
 * Narrow navigation-only exception for joining a server through a lobby portal.
 * It never grants inventory or world mutation permissions.
 *
 * <p>Manager is truth: lobby transit is navigation-only and must never be fenced by
 * agent-local state such as the physical journal. The journal fence stays on
 * physical mutation paths, not on walking to the portal.
 */
public final class PortalNavigationPolicy {
    private PortalNavigationPolicy() {}

    public static boolean mayBootstrap(
            boolean dedicatedAgent,
            boolean awaitingPortal,
            boolean portalBlockVerified,
            boolean adjacentApproach,
            boolean approachIsSafe) {
        return dedicatedAgent && awaitingPortal && portalBlockVerified && adjacentApproach && approachIsSafe;
    }

    /**
     * Backwards-compatible overload. The journal flag is accepted but intentionally
     * ignored: manager is truth, lobby transit is navigation-only and must never be
     * fenced by agent-local journal state. Physical mutation paths keep their own
     * fence in {@code PhysicalJournalRuntime}. Deprecated: use the 5-arg version.
     */
    @Deprecated
    public static boolean mayBootstrap(
            boolean dedicatedAgent,
            boolean awaitingPortal,
            boolean portalBlockVerified,
            boolean adjacentApproach,
            boolean approachIsSafe,
            boolean physicalJournalClear) {
        return mayBootstrap(dedicatedAgent, awaitingPortal, portalBlockVerified, adjacentApproach, approachIsSafe);
    }

    /** A failed portal route belongs to one login, unlike failures of ordinary warehouse routes. */
    public static boolean clearPauseOnDisconnect(boolean paused, boolean portalRouteFailure) {
        return paused && portalRouteFailure;
    }
}

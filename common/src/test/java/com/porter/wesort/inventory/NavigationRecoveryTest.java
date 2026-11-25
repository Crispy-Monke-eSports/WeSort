package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NavigationRecoveryTest {
    @Test
    void transientFailuresRetryWithBoundedBackoff() {
        assertTrue(NavigationRecovery.retryable("PAUSED_NAVIGATION_RELEASED", ""));
        assertTrue(NavigationRecovery.retryable("PAUSED_NAVIGATION_UNSAFE_BLOCK", "unloaded cell"));
        assertTrue(NavigationRecovery.retryable("PAUSED_NAVIGATION_INVENTORY", ""));
        assertEquals(5000, NavigationRecovery.delayMs(1));
        assertEquals(10000, NavigationRecovery.delayMs(2));
        assertEquals(60000, NavigationRecovery.delayMs(99));
    }

    @Test
    void explicitStopsAndHazardsStayFenced() {
        assertFalse(NavigationRecovery.retryable("PAUSED_NAVIGATION_CANCELLED", ""));
        assertFalse(NavigationRecovery.retryable("PAUSED_NAVIGATION_OTHER_PROCESS", ""));
        assertFalse(NavigationRecovery.retryable("PAUSED_NAVIGATION_UNSAFE_BLOCK", "navigation hazard lava"));
        assertFalse(NavigationRecovery.retryable("PAUSED_NAVIGATION_UNSAFE_BLOCK", "fluid"));
        assertFalse(NavigationRecovery.retryable("PAUSED_NAVIGATION_DISCONNECTED", ""));
    }

    @Test
    void everyLatchHasAnAutomaticWayOut() {
        // Operator-taken latches expire instead of waiting for a human that never comes.
        assertTrue(NavigationRecovery.operatorLatch("PAUSED_NAVIGATION_OTHER_PROCESS"));
        assertTrue(NavigationRecovery.operatorLatch("PAUSED_NAVIGATION_CANCELLED"));
        assertTrue(NavigationRecovery.operatorLatch("FOREIGN_PROCESS_CANCELLED"));
        assertTrue(NavigationRecovery.operatorLatch("PAUSED_NAVIGATION_DISCONNECTED"));
        assertTrue(NavigationRecovery.operatorLatch("PAUSED_WRONG_DIMENSION"));
        assertFalse(NavigationRecovery.operatorLatch("PAUSED_NAVIGATION_RELEASED"));
        assertFalse(NavigationRecovery.operatorLatch("IDLE"));
        // Headless agents replan sooner than attended ones.
        assertEquals(5 * 60_000L, NavigationRecovery.operatorTimeoutMs(true));
        assertEquals(10 * 60_000L, NavigationRecovery.operatorTimeoutMs(false));
        // Non-unloaded unsafe blocks degrade after bounded retries (own counter, not the transient one).
        assertFalse(NavigationRecovery.unsafeDegraded(0));
        assertFalse(NavigationRecovery.unsafeDegraded(2));
        assertTrue(NavigationRecovery.unsafeDegraded(3));
        assertTrue(NavigationRecovery.unsafeDegraded(99));
    }
}

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
}

package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NavigationWatchdogTest {
    private NavigationWatchdog watch() {
        return new NavigationWatchdog("overworld", 0, 64, 0, 0, 60, 8);
    }

    @Test
    void noProgressStopsEvenWhilePathCalculationContinues() {
        assertEquals("PAUSED_NAVIGATION_STUCK", watch().check("overworld", 0, 64, 0, 8));
    }

    @Test
    void smallJitterDoesNotCountAsProgress() {
        assertEquals("PAUSED_NAVIGATION_STUCK", watch().check("overworld", 0.1, 64, 0.1, 8));
    }

    @Test
    void realProgressResetsStuckDeadline() {
        var w = watch();
        assertEquals("", w.check("overworld", 1, 64, 0, 7));
        assertEquals("", w.check("overworld", 1, 64, 0, 14));
        assertEquals("PAUSED_NAVIGATION_STUCK", w.check("overworld", 1, 64, 0, 15));
    }

    @Test
    void continuousMovementCannotExtendAbsoluteDeadline() {
        var w = watch();
        for (int t = 1; t < 60; t++) assertEquals("", w.check("overworld", t, 64, 0, t));
        assertEquals("PAUSED_NAVIGATION_TIMEOUT", w.check("overworld", 60, 64, 0, 60));
    }

    @Test
    void dimensionChangeStopsImmediately() {
        assertEquals("PAUSED_WRONG_DIMENSION", watch().check("nether", 0, 64, 0, 1));
    }
}

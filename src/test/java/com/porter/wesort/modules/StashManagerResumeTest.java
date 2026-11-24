package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The stranded-assignment resume gate is pure logic: no Minecraft needed. */
class StashManagerResumeTest {
    @Test
    void strandedOrderIsResumedWhenNothingIsSaved() {
        assertTrue(StashManager.shouldResumeStranded(null, "o-1234567890ab", 10_000, 0));
    }

    @Test
    void savedResumeIdAlwaysWinsOverStrandedHint() {
        assertFalse(StashManager.shouldResumeStranded("o-abcdefabcdef", "o-1234567890ab", 10_000, 0));
    }

    @Test
    void nothingStrandedMeansNoResume() {
        assertFalse(StashManager.shouldResumeStranded(null, null, 10_000, 0));
    }

    @Test
    void claimThrottleAppliesToStrandedResumesToo() {
        // Last claim 1s ago, throttle is 5s.
        assertFalse(StashManager.shouldResumeStranded(null, "o-1234567890ab", 10_000, 9_000));
        assertTrue(StashManager.shouldResumeStranded(null, "o-1234567890ab", 10_000, 5_000));
    }
}

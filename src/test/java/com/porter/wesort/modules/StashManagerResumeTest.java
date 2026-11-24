package com.porter.wesort.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Sorting-token lifecycle is pure logic: no Minecraft needed. */
class StashManagerResumeTest {
    @Test
    void deadTokenIsDroppedOnlyOnAuthoritativeConflict() {
        assertTrue(StashManager.shouldDropSortingToken(409));
        assertFalse(StashManager.shouldDropSortingToken(500));
        assertFalse(StashManager.shouldDropSortingToken(-1));
        assertFalse(StashManager.shouldDropSortingToken(200));
    }
}

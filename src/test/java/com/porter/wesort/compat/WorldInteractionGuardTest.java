package com.porter.wesort.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * W-061 regression test: the permit model never overrides the fail-closed denial of
 * safety-sensitive blocks. A live permit for a trapped chest, door or redstone device must still
 * be denied, exactly as on main (safeBlock && ...). Pure policy, no Minecraft needed.
 */
class WorldInteractionGuardTest {
    @Test
    void safeBlockWithPermitAllowsOrdinaryUse() {
        assertTrue(WorldInteractionGuard.useDecision("order-1", true, true, true, false));
        assertTrue(WorldInteractionGuard.useDecision("order-1", true, true, false, true));
    }

    @Test
    void permitNeverOverridesSafetySensitiveBlocks() {
        // Trapped chest (or door/redstone): even an exact live permit must not authorize it.
        assertFalse(WorldInteractionGuard.useDecision("order-1", false, true, true, true));
        assertFalse(WorldInteractionGuard.useDecision("order-1", true, false, true, true));
        assertFalse(WorldInteractionGuard.useDecision("order-1", false, false, true, true));
        assertFalse(WorldInteractionGuard.breakDecision("order-1", false, true));
    }

    @Test
    void unknownJobNeverAuthorizes() {
        assertFalse(WorldInteractionGuard.useDecision("unknown", true, true, true, true));
        assertFalse(WorldInteractionGuard.useDecision("", true, true, true, true));
        assertFalse(WorldInteractionGuard.useDecision(null, true, true, true, true));
        assertFalse(WorldInteractionGuard.breakDecision("unknown", true, true));
    }

    @Test
    void noPermitDenies() {
        assertFalse(WorldInteractionGuard.useDecision("order-1", true, true, false, false));
        assertFalse(WorldInteractionGuard.breakDecision("order-1", true, false));
    }

    @Test
    void denialsAreCountedForAudit() {
        long before = WorldInteractionGuard.denialCounts().getOrDefault("use-safety-sensitive", 0L);
        WorldInteractionGuard.useDecision("order-1", false, true, true, true);
        assertEquals(before + 1, WorldInteractionGuard.denialCounts().getOrDefault("use-safety-sensitive", 0L));
    }
}

package com.porter.wesort.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        for (String fallback : new String[] {null, "", " ", "unknown"}) {
            String job = WorldInteractionGuard.jobTag(null, null, fallback, 3, "lease");
            assertEquals("unknown", job);
            assertFalse(WorldInteractionGuard.useDecision(job, true, true, true, true));
            assertFalse(WorldInteractionGuard.breakDecision(job, true, true));
        }
        assertFalse(WorldInteractionGuard.useDecision("unknown", true, true, true, true));
        assertFalse(WorldInteractionGuard.useDecision("", true, true, true, true));
        assertFalse(WorldInteractionGuard.useDecision(null, true, true, true, true));
        assertFalse(WorldInteractionGuard.breakDecision("unknown", true, true));
    }

    @Test
    void repackJobTagDoesNotChangeWhenManagerTransportStatusFlaps() {
        String before = WorldInteractionGuard.jobTag(null, "physical-123", "online", -1, null);
        String after = WorldInteractionGuard.jobTag(null, "physical-123", "awaiting server inventory", -1, null);
        assertEquals("repack:physical-123", before);
        assertEquals(before, after);
        assertNotEquals(after, WorldInteractionGuard.jobTag(null, "physical-456", "online", -1, null));
        assertEquals("order-1", WorldInteractionGuard.jobTag("order-1", "physical-123", "online", -1, null));
    }

    @Test
    void jobTagBindsDirectiveRevisionAndLease() {
        // Same work, no rotation: stable identity (no flapping).
        assertEquals("order-1@d3@labc", WorldInteractionGuard.jobTag("order-1", "physical-123", "online", 3, "abc"));
        // A rotated directive or lease invalidates live permits.
        assertNotEquals(
                WorldInteractionGuard.jobTag("order-1", null, "online", 3, "abc"),
                WorldInteractionGuard.jobTag("order-1", null, "online", 4, "abc"));
        assertNotEquals(
                WorldInteractionGuard.jobTag("order-1", null, "online", 3, "abc"),
                WorldInteractionGuard.jobTag("order-1", null, "online", 3, "def"));
        // Undirected / leaseless sessions keep the plain tag.
        assertEquals("order-1", WorldInteractionGuard.jobTag("order-1", null, "online", -1, null));
    }

    @Test
    void plainAndDyedShulkersQualifyForAuthorizedBreakStop() {
        // Vanilla's undyed block is minecraft:shulker_box, not *_shulker_box.
        // A predicted-air STOP must retain the exact original permit for both.
        assertTrue(WorldInteractionGuard.isShulkerBlockId("minecraft:shulker_box"));
        assertTrue(WorldInteractionGuard.isShulkerBlockId("minecraft:blue_shulker_box"));
        assertFalse(WorldInteractionGuard.isShulkerBlockId("minecraft:air"));
        assertFalse(WorldInteractionGuard.isShulkerBlockId("minecraft:chest"));
        assertFalse(WorldInteractionGuard.isShulkerBlockId(null));
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

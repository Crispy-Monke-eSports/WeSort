package com.porter.wesort.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.wesort.utils.InteractionPermits.Operation;
import org.junit.jupiter.api.Test;

/** Fail-closed semantics: default deny, exact match only, expiry, revocation, no wildcards. */
class InteractionPermitsTest {
    private static final long STONE = asLong(10, 64, 20), NEXT = asLong(11, 64, 20);

    private static long asLong(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }

    @Test
    void deniesEverythingWithoutAPermit() {
        InteractionPermits permits = new InteractionPermits();
        assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest"));
        assertFalse(permits.allows(1000, STONE, Operation.BREAK, "minecraft:chest"));
        assertFalse(permits.allows(1000, STONE, Operation.SIGN, "minecraft:oak_sign"));
        assertNull(permits.jobOf(1000, STONE, Operation.USE));
        assertEquals(0, permits.live(1000));
    }

    @Test
    void allowsOnlyTheExactPositionOperationAndBlock() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.USE, "minecraft:chest", "order-1", 5000, 1000)) {
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:chest"));
            assertEquals("order-1", permits.jobOf(1000, STONE, Operation.USE));
            // Neighbour, other operation, changed block: all denied.
            assertFalse(permits.allows(1000, NEXT, Operation.USE, "minecraft:chest"));
            assertFalse(permits.allows(1000, STONE, Operation.BREAK, "minecraft:chest"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:trapped_chest"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, null));
        }
        assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest"));
    }

    @Test
    void permitsExpireAndArePruned() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.BREAK, "minecraft:shulker_box", "order-9", 100, 1000)) {
            assertTrue(permits.allows(1050, STONE, Operation.BREAK, "minecraft:shulker_box"));
            assertFalse(permits.allows(1100, STONE, Operation.BREAK, "minecraft:shulker_box"));
            assertNull(permits.jobOf(1100, STONE, Operation.BREAK));
            assertEquals(0, permits.live(1100));
        }
    }

    @Test
    void reissueReplacesTheOldPermit() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var first = permits.permit(STONE, Operation.USE, "minecraft:chest", "order-1", 5000, 1000);
                var second = permits.permit(STONE, Operation.USE, "minecraft:barrel", "order-2", 5000, 1000)) {
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest"));
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:barrel"));
            assertEquals("order-2", permits.jobOf(1000, STONE, Operation.USE));
            assertEquals(1, permits.live(1000));
        }
    }

    @Test
    void blankJobIsRecordedAsUnknown() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.USE, "minecraft:chest", "", 5000, 1000)) {
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:chest"));
            assertEquals("unknown", permits.jobOf(1000, STONE, Operation.USE));
        }
    }
}

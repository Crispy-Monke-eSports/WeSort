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
        assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "order-1"));
        assertFalse(permits.allows(1000, STONE, Operation.BREAK, "minecraft:chest", "order-1"));
        assertNull(permits.jobOf(1000, STONE, Operation.USE));
        assertEquals(0, permits.live(1000));
    }

    @Test
    void allowsOnlyTheExactPositionOperationAndBlock() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.USE, "minecraft:chest", "order-1", 5000, 1000)) {
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "order-1"));
            assertEquals("order-1", permits.jobOf(1000, STONE, Operation.USE));
            // Neighbour, other operation, changed block: all denied.
            assertFalse(permits.allows(1000, NEXT, Operation.USE, "minecraft:chest", "order-1"));
            assertFalse(permits.allows(1000, STONE, Operation.BREAK, "minecraft:chest", "order-1"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:trapped_chest", "order-1"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, null, "order-1"));
        }
        assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "order-1"));
    }

    @Test
    void permitsExpireAndArePruned() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.BREAK, "minecraft:shulker_box", "order-9", 100, 1000)) {
            assertTrue(permits.allows(1050, STONE, Operation.BREAK, "minecraft:shulker_box", "order-9"));
            assertFalse(permits.allows(1100, STONE, Operation.BREAK, "minecraft:shulker_box", "order-9"));
            assertNull(permits.jobOf(1100, STONE, Operation.BREAK));
            assertEquals(0, permits.live(1100));
        }
    }

    @Test
    void reissueReplacesTheOldPermit() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var first = permits.permit(STONE, Operation.USE, "minecraft:chest", "order-1", 5000, 1000);
                var second = permits.permit(STONE, Operation.USE, "minecraft:barrel", "order-2", 5000, 1000)) {
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "order-1"));
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:barrel", "order-2"));
            assertEquals("order-2", permits.jobOf(1000, STONE, Operation.USE));
            assertEquals(1, permits.live(1000));
        }
    }

    @Test
    void blankJobIsRecordedAsUnknownAndNeverAuthorizes() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.USE, "minecraft:chest", "", 5000, 1000)) {
            // Audit still records the unknown job, but no mutation is authorized
            // through it: not even the "unknown" job itself may act.
            assertEquals("unknown", permits.jobOf(1000, STONE, Operation.USE));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "order-1"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "unknown"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", ""));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", null));
        }
    }

    @Test
    void jobBindingDeniesCrossJobUse() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.USE, "minecraft:chest", "job-a", 5000, 1000)) {
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "job-a"));
            // Job B, a restarted job with a new id, a blank or unknown job: all denied.
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "job-b"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "unknown"));
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", ""));
            // Same-job re-activation is idempotent and stays allowed (permits are
            // TTL-bound, not consumed: one logical action is gate-checked several
            // times across the mixin, packet and stop-destroy re-checks).
            assertTrue(permits.allows(1001, STONE, Operation.USE, "minecraft:chest", "job-a"));
        }
        assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "job-a"));
    }

    @Test
    void reissueForANewJobInvalidatesTheOldJob() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var first = permits.permit(STONE, Operation.USE, "minecraft:chest", "job-a", 5000, 1000);
                var second = permits.permit(STONE, Operation.USE, "minecraft:chest", "job-b", 5000, 1000)) {
            assertFalse(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "job-a"));
            assertTrue(permits.allows(1000, STONE, Operation.USE, "minecraft:chest", "job-b"));
            assertEquals("job-b", permits.jobOf(1000, STONE, Operation.USE));
            assertEquals(1, permits.live(1000));
        }
    }

    @Test
    void blockNamespaceIsPartOfTheIdentity() throws Exception {
        InteractionPermits permits = new InteractionPermits();
        try (var scope = permits.permit(STONE, Operation.BREAK, "minecraft:stone", "job-a", 5000, 1000)) {
            assertTrue(permits.allows(1000, STONE, Operation.BREAK, "minecraft:stone", "job-a"));
            // Same path, different namespace: a different block, denied.
            assertFalse(permits.allows(1000, STONE, Operation.BREAK, "othermod:stone", "job-a"));
            assertFalse(permits.allows(1000, STONE, Operation.BREAK, "stone", "job-a"));
            // Break self-invalidates: after destruction the block no longer matches.
            assertFalse(permits.allows(1000, STONE, Operation.BREAK, "minecraft:air", "job-a"));
        }
    }
}

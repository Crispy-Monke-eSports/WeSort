package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlacedShulkerRecoveryTest {
    @Test
    void rejectedDepositCanBeReplannedOnlyWithUnchangedInventories() {
        assertTrue(PlacedShulkerRecovery.depositUnchanged(true, 2, 2));
        assertFalse(PlacedShulkerRecovery.depositUnchanged(true, -1, -1));
        assertFalse(PlacedShulkerRecovery.depositUnchanged(false, 2, 2));
        assertFalse(PlacedShulkerRecovery.depositUnchanged(true, 2, 3));
    }

    @Test
    void pickupCanFollowAnExistingCopyInInventoryOrder() {
        assertEquals(7, PlacedShulkerRecovery.pickupSlot(List.of(2, 7), Set.of(2), 1));
        assertEquals(7, PlacedShulkerRecovery.pickupSlot(List.of(7, 2), Set.of(2), 1));
        assertEquals(2, PlacedShulkerRecovery.pickupSlot(List.of(2), Set.of(2), 0));
        assertEquals(-1, PlacedShulkerRecovery.pickupSlot(List.of(2), Set.of(2), 1));
        assertEquals(-1, PlacedShulkerRecovery.pickupSlot(List.of(5, 7), Set.of(2), 1));
    }

    @Test
    void activeOrderRetainsOnlyItsOwnShulkers() {
        assertFalse(PlacedShulkerRecovery.shouldRecover("order-1", "order-1"));
        assertTrue(PlacedShulkerRecovery.shouldRecover("order-1", "order-2"));
        assertTrue(PlacedShulkerRecovery.shouldRecover("order-1", null));
        assertTrue(PlacedShulkerRecovery.shouldRecover("order-1", ""));
    }

    @Test
    void unknownAndPlayerPlacedShulkersAreDropoffs() {
        assertTrue(PlacedShulkerRecovery.shouldRecover("", "order-1"));
        assertTrue(PlacedShulkerRecovery.shouldRecover(null, "order-1"));
        assertTrue(PlacedShulkerRecovery.shouldRecover("", null));
    }

    @Test
    void resumeWorkOnlyAfterBothSidesConfirmTheReturn() {
        assertFalse(PlacedShulkerRecovery.depositConfirmed(true, -1, 0, 1));
        assertFalse(PlacedShulkerRecovery.depositConfirmed(true, 2, 2, 1));
        assertFalse(PlacedShulkerRecovery.depositConfirmed(true, 2, 4, 1));
        assertFalse(PlacedShulkerRecovery.depositConfirmed(false, 2, 3, 1));
        assertTrue(PlacedShulkerRecovery.depositConfirmed(true, 2, 3, 1));
    }
}

package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlacedShulkerRecoveryTest {
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

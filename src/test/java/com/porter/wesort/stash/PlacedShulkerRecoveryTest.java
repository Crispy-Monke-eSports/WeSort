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
}

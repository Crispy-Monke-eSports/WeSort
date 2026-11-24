package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockDeviationTest {
    @Test
    void confirmedSourceCanBeReturnedBeforeTransfer() {
        assertTrue(StockDeviation.mayReturnIntact(true, true, true, true, 0, false));
    }

    @Test
    void uncertainOrRepeatedChangeCannotBeAcceptedAsNewStock() {
        assertFalse(StockDeviation.mayReturnIntact(false, true, true, true, 0, false));
        assertFalse(StockDeviation.mayReturnIntact(true, false, true, true, 0, false));
        assertFalse(StockDeviation.mayReturnIntact(true, true, false, true, 0, false));
        assertFalse(StockDeviation.mayReturnIntact(true, true, true, false, 0, false));
        assertFalse(StockDeviation.mayReturnIntact(true, true, true, true, 1, false));
        assertFalse(StockDeviation.mayReturnIntact(true, true, true, true, 0, true));
    }
}

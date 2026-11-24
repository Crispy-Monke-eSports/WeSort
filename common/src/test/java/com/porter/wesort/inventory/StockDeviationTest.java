package com.porter.wesort.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StockDeviationTest {
    @Test
    void confirmedSourceCanBeReturnedBeforeTransfer() {
        assertTrue(StockDeviation.mayReturnIntact(true, true, true, true, 0, false));
    }

    @Test
    void changedSourceCanBeRebasedButUnrelatedLossCannot() {
        var baseline = Map.of("diamond", 64L, "iron", 8L, "shulker", 1L);
        var previous = Map.of("diamond", 64L);
        var observed = Map.of("diamond", 32L, "gold", 3L);
        assertTrue(StockDeviation.matchesWithObservedSource(
                baseline, previous, observed, Map.of("diamond", 32L, "iron", 8L, "gold", 3L, "shulker", 1L)));
        assertFalse(StockDeviation.matchesWithObservedSource(
                baseline, previous, observed, Map.of("diamond", 32L, "iron", 7L, "gold", 3L, "shulker", 1L)));
        assertFalse(StockDeviation.matchesWithObservedSource(baseline, Map.of("diamond", 65L), observed, baseline));
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

package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class KitAvailabilityTest {
    @Test
    void emptyBoxAndDestinationCapacityBoundBuildableCount() {
        var needs = Map.of("diamond", 32, "sword#damage7", 1);
        var have = Map.of("diamond", 128, "sword#damage7", 8);
        assertEquals(2, KitAvailability.assess(needs, have, List.of(), 4, 2).buildable());
        assertEquals(0, KitAvailability.assess(needs, have, List.of(), 0, 8).buildable());
    }

    @Test
    void wrongVariantIsAnExplicitShortage() {
        var result = KitAvailability.assess(Map.of("sword#damage7", 1), Map.of("sword#damage8", 8), List.of(), 1, 1);
        assertEquals(0, result.buildable());
        assertEquals(Map.of("sword#damage7", 1), result.missing());
    }

    @Test
    void partialBaseUsesItsOwnMaterialOnceAndReusesItsHomeSlot() {
        var result = KitAvailability.assess(
                Map.of("diamond", 32, "sword", 1),
                Map.of("diamond", 16),
                List.of(Map.of("diamond", 16, "sword", 1)),
                0,
                0);
        assertEquals(1, result.buildable());
        assertTrue(result.exact());
        assertTrue(result.missing().isEmpty());
        var two = KitAvailability.assess(
                Map.of("diamond", 32),
                Map.of("diamond", 16),
                List.of(Map.of("diamond", 16), Map.of("diamond", 16)),
                0,
                0);
        assertEquals(1, two.buildable());
    }

    @Test
    void smallPartialSetsSearchAllChoicesInsteadOfChoosingAnInfeasibleFavorite() {
        var required = Map.of("a", 2, "b", 1);
        var result = KitAvailability.assess(
                required,
                Map.of("a", 2, "b", 1),
                List.of(Map.of("b", 1), Map.of("a", 1, "b", 1), Map.of("a", 1, "b", 1), Map.of("a", 2)),
                0,
                0);
        assertEquals(3, result.buildable());
        assertTrue(result.exact());
    }

    @Test
    void largerPartialSetsReportAHonestLowerBound() {
        var partials = new ArrayList<Map<String, Integer>>();
        for (int i = 0; i < 13; i++) partials.add(Map.of("diamond", 31));
        var result = KitAvailability.assess(Map.of("diamond", 32), Map.of("diamond", 3), partials, 0, 0);
        assertEquals(3, result.buildable());
        assertFalse(result.exact());
    }
}

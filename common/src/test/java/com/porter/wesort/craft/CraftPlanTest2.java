package com.porter.wesort.craft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftPlanTest2 {
    private static CraftPlan sample() {
        return new CraftPlan(
                1,
                "plan-1",
                "recipes-sha",
                7L,
                Map.of("minecraft:torch", 4L),
                List.of(new CraftPlan.PlanStep("s1", "r-stick", 1), new CraftPlan.PlanStep("s2", "r-torch", 1)),
                Map.of("minecraft:stick", 4L, "minecraft:torch", 4L),
                Map.of("minecraft:stick", 1L, "minecraft:coal", 1L, "minecraft:planks", 2L),
                Map.of("minecraft:stick", 3L),
                List.of(new CraftPlan.DependencyEdge("s1", "s2")),
                List.of(new CraftPlan.AllocationRef("src-1", "2/4", "item:minecraft:stick", 2L, "fp")));
    }

    @Test
    void hashIsStableAndSensitive() {
        assertEquals(64, sample().canonicalHash().length());
        assertEquals(sample().canonicalHash(), sample().canonicalHash());
        var other = new CraftPlan(
                1,
                "plan-1",
                "recipes-sha",
                8L,
                sample().requestedOutputs(),
                sample().steps(),
                sample().produced(),
                sample().consumed(),
                sample().plannedSurplus(),
                sample().dependencies(),
                sample().physicalAllocations());
        assertNotEquals(sample().canonicalHash(), other.canonicalHash());
    }

    @Test
    void massBalanceVerifierCatchesImbalance() {
        assertTrue(CraftPlan.verifyMassBalance(
                        Map.of("planks", 2L),
                        Map.of("stick", 4L),
                        Map.of("stick", 1L),
                        Map.of("torch", 4L),
                        Map.of("stick", 3L))
                .isPresent());
        assertTrue(CraftPlan.verifyMassBalance(
                        Map.of("planks", 2L, "coal", 1L),
                        Map.of("stick", 4L, "torch", 4L),
                        Map.of("planks", 2L, "stick", 1L, "coal", 1L),
                        Map.of("torch", 4L),
                        Map.of("stick", 3L))
                .isEmpty());
    }

    @Test
    void overflowFailsClosed() {
        assertTrue(
                CraftPlan.verifyMassBalance(Map.of("a", Long.MAX_VALUE), Map.of(), Map.of(), Map.of(), Map.of("a", 1L))
                        .isPresent());
    }

    @Test
    void schemaVersionIsLocked() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CraftPlan(
                        2,
                        "p",
                        "r",
                        1L,
                        Map.of("a", 1L),
                        List.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        List.of(),
                        List.of()));
    }

    @Test
    void previewCacheSingleFlightAndScopeInvalidation() {
        var cache = new PreviewCache<String>(4);
        var key = new PreviewCache.Key("s", "stock", 1L, "recipes", 1L, "preview", "torch", 4L);
        assertTrue(cache.beginCompute(key));
        assertFalse(cache.beginCompute(key));
        cache.publishIfCurrent(key, "result");
        assertEquals("result", cache.getIfCurrent(key));
        assertTrue(cache.beginCompute(key));
        cache.cancelCompute(key);
        cache.invalidateScope("s");
        assertNull(cache.getIfCurrent(key));
    }
}

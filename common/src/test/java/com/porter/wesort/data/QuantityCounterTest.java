package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QuantityCounterTest {
    @Test
    void addsWithCheckedArithmetic() {
        var c = new QuantityCounter();
        c.add("a", 5);
        c.add("a", 3);
        assertEquals(8L, c.get("a"));
        assertEquals(8L, c.snapshot().get("a"));
    }

    @Test
    void zeroRemoves() {
        var c = new QuantityCounter();
        c.add("a", 5);
        c.add("a", -5);
        assertEquals(0L, c.get("a"));
        assertTrue(c.isEmpty());
    }

    @Test
    void overflowFailsClosed() {
        var c = new QuantityCounter();
        c.set("a", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> c.add("a", 1));
        assertEquals(Long.MAX_VALUE, c.get("a"));
    }

    @Test
    void deltaAppliesAtomically() {
        var c = new QuantityCounter();
        c.set("a", 10);
        c.set("b", 4);
        c.applyDelta(Map.of("a", -3L, "c", 7L));
        assertEquals(7L, c.get("a"));
        assertEquals(4L, c.get("b"));
        assertEquals(7L, c.get("c"));
        assertThrows(IllegalArgumentException.class, () -> c.applyDelta(Map.of("a", -100L)));
        assertEquals(7L, c.get("a"));
    }

    @Test
    void snapshotIsImmutableAndSorted() {
        var c = new QuantityCounter();
        c.add("b", 1);
        c.add("a", 2);
        var snap = c.snapshot();
        assertEquals(java.util.List.of("a", "b"), new java.util.ArrayList<>(snap.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> snap.put("c", 1L));
    }

    @Test
    void multiplyChecked() {
        assertEquals(6L, QuantityCounter.multiplyChecked(2, 3));
        assertThrows(ArithmeticException.class, () -> QuantityCounter.multiplyChecked(Long.MAX_VALUE, 2));
    }

    @Test
    void sourceContributionDelta() {
        var old = new SourceContribution("s1", 1L, "d1", Map.of("a", 5L), Map.of("a", 5L, "b", 2L), 100L);
        var next = new SourceContribution("s1", 2L, "d2", Map.of("a", 3L), Map.of("a", 3L, "c", 4L), 200L);
        var delta = SourceContribution.selectableDelta(old, next);
        assertEquals(-2L, delta.get("a"));
        assertEquals(-2L, delta.get("b"));
        assertEquals(4L, delta.get("c"));
        var other = new SourceContribution("s2", 2L, "d2", Map.of(), Map.of(), 200L);
        assertThrows(IllegalArgumentException.class, () -> SourceContribution.selectableDelta(old, other));
    }
}

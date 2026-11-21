package com.porter.wesort.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.wesort.craft.AnvilFinder.Decision;
import com.porter.wesort.craft.AnvilFinder.Memory;
import com.porter.wesort.craft.AnvilFinder.Pos;
import com.porter.wesort.craft.AnvilFinder.Source;
import com.porter.wesort.craft.AnvilFinder.Step;
import com.porter.wesort.stash.Order;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnvilFinderTest {
    /** A world with these placed anvils, a stash with or without an anvil item. */
    private static AnvilFinder.World world(Set<Pos> anvils, Pos found, boolean stock) {
        return world(anvils, found, stock, false);
    }

    private static AnvilFinder.World world(Set<Pos> anvils, Pos found, boolean stock, boolean craftable) {
        return new AnvilFinder.World() {
            @Override
            public boolean isAnvil(Pos p) {
                return anvils.contains(p);
            }

            @Override
            public Pos search() {
                return found;
            }

            @Override
            public boolean stockHasAnvil() {
                return stock;
            }

            @Override
            public boolean craftableAnvil() {
                return craftable;
            }
        };
    }

    @Test
    void findsAPlacedAnvilWithinTheArea() {
        Pos near = new Pos(3, 64, 0), far = new Pos(40, 64, 0);
        Pos from = new Pos(0, 64, 0);
        Pos hit = AnvilFinder.nearest(List.of(far, near), from, p -> Math.abs(p.x()) <= 10);
        assertEquals(near, hit);
        Decision d = AnvilFinder.decide(null, world(Set.of(near), hit, false));
        assertEquals(Step.FOUND, d.step());
        assertEquals(near, d.pos());
        assertEquals(Source.FOUND, d.source());
    }

    @Test
    void ignoresAnAnvilOutsideThePerimeter() {
        Pos outside = new Pos(40, 64, 0);
        assertNull(AnvilFinder.nearest(List.of(outside), new Pos(0, 64, 0), p -> p.x() < 20));
        Decision d = AnvilFinder.decide(null, world(Set.of(outside), null, false));
        assertEquals(Step.FAIL, d.step());
    }

    @Test
    void placesFromStockWhenNoneIsPlaced() {
        Decision d = AnvilFinder.decide(null, world(Set.of(), null, true));
        assertEquals(Step.PLACE_FROM_STOCK, d.step());
        assertEquals(Source.PLACED, d.source());
    }

    @Test
    void craftsFromIronWhenTheStashHasNoAnvil() {
        Decision d = AnvilFinder.decide(null, world(Set.of(), null, false, true));
        assertEquals(Step.CRAFT, d.step());
    }

    @Test
    void placesFromStockBeforeCrafting() {
        Decision d = AnvilFinder.decide(null, world(Set.of(), null, true, true));
        assertEquals(Step.PLACE_FROM_STOCK, d.step());
    }

    @Test
    void craftableNeedsThreeBlocksAndFourIngots() {
        Recipes r = Recipes.load("26.3");
        assertTrue(r != null, "26.3");
        Map<String, Long> full = Map.of("minecraft:iron_block", 3L, "minecraft:iron_ingot", 4L);
        assertTrue(AnvilFinder.craftable(r, full, Set.of()));
        assertFalse(AnvilFinder.craftable(r, Map.of("minecraft:iron_block", 2L, "minecraft:iron_ingot", 4L), Set.of()));
        assertFalse(AnvilFinder.craftable(r, Map.of("minecraft:iron_block", 3L, "minecraft:iron_ingot", 3L), Set.of()));
        assertFalse(AnvilFinder.craftable(r, Map.of("minecraft:cobblestone", 64L), Set.of()));
        assertFalse(AnvilFinder.craftable(r, full, Set.of("minecraft:iron_block")));
        assertFalse(AnvilFinder.craftable(null, full, Set.of()));
    }

    @Test
    void craftsAReplacementWhenTheRememberedAnvilIsGone() {
        Pos old = new Pos(1, 64, 1);
        Decision d = AnvilFinder.decide(new Memory(old, Source.PLACED), world(Set.of(), null, false, true));
        assertTrue(d.forget());
        assertEquals(Step.CRAFT, d.step());
    }

    @Test
    void failsWithTheExactReasonAndTheOrderStillDelivers() {
        Decision d = AnvilFinder.decide(null, world(Set.of(), null, false));
        assertEquals(Step.FAIL, d.step());
        assertEquals("no anvils available to rename with", d.failure());
        // The shulker is delivered unnamed, and the order's note says why
        assertEquals(
                "2 shulker(s) delivered unnamed: no anvils available to rename with",
                Order.unnamedNote(2, List.of(d.failure())));
    }

    @Test
    void searchesAgainWhenTheRememberedAnvilIsGone() {
        Pos old = new Pos(1, 64, 1), fresh = new Pos(5, 64, 5);
        Decision d = AnvilFinder.decide(new Memory(old, Source.FOUND), world(Set.of(fresh), fresh, false));
        assertTrue(d.forget());
        assertEquals(Step.FOUND, d.step());
        assertEquals(fresh, d.pos());
        // Gone and nothing else placed: placed from stock
        Decision p = AnvilFinder.decide(new Memory(old, Source.PLACED), world(Set.of(), null, true));
        assertTrue(p.forget());
        assertEquals(Step.PLACE_FROM_STOCK, p.step());
    }

    @Test
    void aSetAnvilKeepsPriorityAndIsUsedAsIs() {
        Pos set = new Pos(1, 64, 1), other = new Pos(2, 64, 2);
        Decision d = AnvilFinder.decide(new Memory(set, Source.SET), world(Set.of(set, other), other, true));
        assertEquals(Step.USE, d.step());
        assertEquals(set, d.pos());
        assertEquals(Source.SET, d.source());
        assertFalse(d.forget());
    }
}

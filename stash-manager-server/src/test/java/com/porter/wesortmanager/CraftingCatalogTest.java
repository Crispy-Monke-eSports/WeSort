package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CraftingCatalogTest {
    @Test
    void nuggetsCanBeOrderedAsBlocksThroughIngots() {
        var pool = Map.of("minecraft:iron_nugget", 200L);
        var p = CraftingCatalog.plan("minecraft:iron_block", 3, pool);
        assertTrue(p.ok());
        assertEquals(2, p.plan().count());
        assertEquals(162, p.plan().take().get("minecraft:iron_nugget"));
        assertEquals(
                "minecraft:iron_block",
                p.plan().steps().get(p.plan().steps().size() - 1).recipe().result());
    }

    @Test
    void onlyPlainStacksCanSupplyRecipes() {
        var available = Map.of(
                "item:minecraft:iron_nugget", 200L,
                "item:minecraft:iron_nugget#named", 900L,
                "shulker:minecraft:iron_nugget", 99L);
        var pool = CraftingCatalog.eligiblePool(available);
        assertEquals(Map.of("minecraft:iron_nugget", 200L), pool);
        var listing = CraftingCatalog.available(available, true);
        assertEquals(2, listing.get("minecraft:iron_block").getAsInt());
        assertTrue(CraftingCatalog.available(available, false).isEmpty());
    }

    @Test
    void doesNotPretendFurnaceRecipesAreTableCraftable() {
        assertFalse(CraftingCatalog.plan("minecraft:stone", 1, Map.of("minecraft:cobblestone", 64L))
                .ok());
    }
}

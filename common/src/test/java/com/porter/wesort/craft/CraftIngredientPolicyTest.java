package com.porter.wesort.craft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CraftIngredientPolicyTest {
    @Test
    void plainStacksAreAllowed() {
        assertTrue(CraftIngredientPolicy.classify(false, false, false, false, false).allowed());
    }

    @Test
    void modifiedOrDamagedOrContainerIsDenied() {
        assertEquals(
                CraftIngredientPolicy.RejectReason.MODIFIED_COMPONENTS,
                CraftIngredientPolicy.classify(true, false, false, false, false).reason());
        assertEquals(
                CraftIngredientPolicy.RejectReason.DAMAGED,
                CraftIngredientPolicy.classify(false, true, false, false, false).reason());
        assertEquals(
                CraftIngredientPolicy.RejectReason.CONTAINER_CONTENT,
                CraftIngredientPolicy.classify(false, false, true, false, false).reason());
        assertEquals(
                CraftIngredientPolicy.RejectReason.FORBIDDEN_ID,
                CraftIngredientPolicy.classify(false, false, false, true, false).reason());
        assertEquals(
                CraftIngredientPolicy.RejectReason.UNKNOWN_METADATA,
                CraftIngredientPolicy.classify(false, false, false, false, true).reason());
    }

    @Test
    void shulkerAndBundleIdsAreAlwaysForbidden() {
        assertTrue(CraftIngredientPolicy.isForbiddenId("minecraft:shulker_box"));
        assertTrue(CraftIngredientPolicy.isForbiddenId("minecraft:red_shulker_box"));
        assertTrue(CraftIngredientPolicy.isForbiddenId("minecraft:bundle"));
        assertFalse(CraftIngredientPolicy.isForbiddenId("minecraft:iron_ingot"));
        assertTrue(CraftIngredientPolicy.isForbiddenId(null));
    }

    @Test
    void narrowestGroupFirstIsDeterministic() {
        var groups = List.of(List.of("a", "b", "c"), List.of("a"), List.of("b", "a"));
        var ordered = CraftIngredientPolicy.orderGroupsNarrowestFirst(groups);
        assertEquals(List.of("a"), ordered.get(0));
        assertEquals(3, ordered.size());
    }

    @Test
    void cellAllocationRequiresVariantAndLot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CraftIngredientPolicy.CellAllocation(0, 0, "", "lot-1", 1));
        var cell = new CraftIngredientPolicy.CellAllocation(0, 2, "digest", "lot-1", 3);
        assertEquals(2, cell.cellIndex());
    }

    @Test
    void budgetRequiresPositiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new CraftIngredientPolicy.PlanningBudget(0, 1, 1));
        assertDoesNotThrow(CraftIngredientPolicy.PlanningBudget::standard);
    }
}

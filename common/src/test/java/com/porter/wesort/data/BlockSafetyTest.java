package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BlockSafetyTest {
    @Test
    void doorsTrapdoorsGatesAndButtonsAreSensitive() {
        assertTrue(BlockSafety.safetySensitive("oak_door"));
        assertTrue(BlockSafety.safetySensitive("iron_door"));
        assertTrue(BlockSafety.safetySensitive("oak_trapdoor"));
        assertTrue(BlockSafety.safetySensitive("oak_fence_gate"));
        assertTrue(BlockSafety.safetySensitive("stone_button"));
        assertTrue(BlockSafety.safetySensitive("oak_button"));
    }

    @Test
    void passiveTriggersAllowWalkingButRemainSensitiveToActiveInteraction() {
        for (String id : new String[] {
            "minecraft:dark_oak_pressure_plate",
            "minecraft:heavy_weighted_pressure_plate",
            "minecraft:tripwire",
            "minecraft:detector_rail",
            "minecraft:sculk_sensor",
            "minecraft:calibrated_sculk_sensor",
            "minecraft:sculk_shrieker"
        }) {
            assertTrue(BlockSafety.safetySensitive(id), id);
            assertFalse(BlockSafety.blocksNavigation(id), id);
        }
    }

    @Test
    void doorsGatesAndButtonsAllowWalkingThroughButRemainSensitiveToActiveInteraction() {
        // A stash route through an open or pressure-plate-operated door must
        // not pause navigation. Breaking or actively operating it stays forbidden.
        for (String id : new String[] {
            "minecraft:oak_door",
            "minecraft:iron_door",
            "minecraft:oak_fence_gate",
            "minecraft:stone_button",
            "minecraft:oak_button"
        }) {
            assertTrue(BlockSafety.safetySensitive(id), id);
            assertFalse(BlockSafety.blocksNavigation(id), id);
        }
    }

    @Test
    void sensitiveDevicesDoNotImplicitlyBanNavigation() {
        // Geometry and support are Baritone's responsibility, not a registry-id
        // denylist. The dedicated interaction guard still refuses these blocks.
        for (String id : new String[] {
            "oak_trapdoor", "iron_trapdoor", "lever", "tripwire_hook",
            "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "redstone_block", "repeater", "comparator", "observer", "target",
            "piston", "sticky_piston", "piston_head", "dispenser", "dropper",
            "trapped_chest", "crafter", "bell", "lectern", "daylight_detector",
            "lightning_rod", "powered_rail", "activator_rail",
            "future_pressure_plate", "future_door", "future_trapdoor",
            "future_fence_gate", "future_button", "future_sculk_sensor"
        }) {
            for (String namespace : new String[] {"minecraft:", "othermod:", ""}) {
                assertTrue(BlockSafety.safetySensitive(namespace + id), id);
                assertFalse(BlockSafety.blocksNavigation(namespace + id), id);
            }
        }
    }

    @Test
    void onlyExplicitNavigationHazardsBlockPassage() {
        for (String id : new String[] {
            "nether_portal", "end_portal", "end_gateway", "moving_piston"
        }) {
            assertTrue(BlockSafety.blocksNavigation(id), id);
            assertTrue(BlockSafety.blocksNavigation("minecraft:" + id), id);
            assertTrue(BlockSafety.blocksNavigation("othermod:" + id), id);
            assertTrue(BlockSafety.safetySensitive(id), id);
        }
    }

    @Test
    void redstoneAndPortalDevicesAreSensitive() {
        assertTrue(BlockSafety.safetySensitive("lever"));
        assertTrue(BlockSafety.safetySensitive("redstone_wire"));
        assertTrue(BlockSafety.safetySensitive("piston"));
        assertTrue(BlockSafety.safetySensitive("sticky_piston"));
        assertTrue(BlockSafety.safetySensitive("dispenser"));
        assertTrue(BlockSafety.safetySensitive("dropper"));
        assertTrue(BlockSafety.safetySensitive("trapped_chest"));
        assertTrue(BlockSafety.safetySensitive("tripwire_hook"));
        assertTrue(BlockSafety.safetySensitive("nether_portal"));
        assertTrue(BlockSafety.safetySensitive("end_portal"));
    }

    @Test
    void namespacedIdsMatchOnPath() {
        // The guard passes full namespaced ids (minecraft:lever); classification
        // strips the namespace so namespaced dangerous blocks stay denied and
        // namespaced ordinary blocks stay allowed.
        assertTrue(BlockSafety.safetySensitive("minecraft:oak_door"));
        assertTrue(BlockSafety.safetySensitive("minecraft:lever"));
        assertTrue(BlockSafety.safetySensitive("minecraft:tripwire"));
        assertTrue(BlockSafety.safetySensitive("minecraft:redstone_wire"));
        assertTrue(BlockSafety.safetySensitive("minecraft:stone_button"));
        assertFalse(BlockSafety.blocksNavigation("minecraft:redstone_wire"));
        assertTrue(BlockSafety.blocksNavigation("minecraft:nether_portal"));
        assertFalse(BlockSafety.blocksNavigation("minecraft:oak_fence_gate"));
        assertFalse(BlockSafety.safetySensitive("minecraft:stone"));
        assertFalse(BlockSafety.safetySensitive("minecraft:chest"));
        assertFalse(BlockSafety.blocksNavigation("minecraft:stone"));
        // Other namespaces classify the same way (syntactic, path-based).
        assertTrue(BlockSafety.safetySensitive("othermod:oak_door"));
        assertTrue(BlockSafety.safetySensitive("othermod:lever"));
        assertFalse(BlockSafety.safetySensitive("othermod:stone"));
    }

    @Test
    void ordinaryStorageAndBuildingBlocksAreAllowed() {
        assertFalse(BlockSafety.safetySensitive("chest"));
        assertFalse(BlockSafety.safetySensitive("barrel"));
        assertFalse(BlockSafety.safetySensitive("shulker_box"));
        assertFalse(BlockSafety.safetySensitive("anvil"));
        assertFalse(BlockSafety.safetySensitive("crafting_table"));
        assertFalse(BlockSafety.safetySensitive("stone"));
        assertFalse(BlockSafety.safetySensitive("glass"));
    }
}

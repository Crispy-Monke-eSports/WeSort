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
    void passiveTriggersAreSensitive() {
        assertTrue(BlockSafety.passiveTrigger("stone_pressure_plate"));
        assertTrue(BlockSafety.passiveTrigger("tripwire"));
        assertTrue(BlockSafety.passiveTrigger("detector_rail"));
        assertTrue(BlockSafety.passiveTrigger("sculk_sensor"));
        assertTrue(BlockSafety.passiveTrigger("sculk_shrieker"));
        assertFalse(BlockSafety.passiveTrigger("stone"));
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
        assertTrue(BlockSafety.passiveTrigger("minecraft:stone_pressure_plate"));
        assertTrue(BlockSafety.passiveTrigger("minecraft:tripwire"));
        assertFalse(BlockSafety.safetySensitive("minecraft:stone"));
        assertFalse(BlockSafety.safetySensitive("minecraft:chest"));
        assertFalse(BlockSafety.passiveTrigger("minecraft:stone"));
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

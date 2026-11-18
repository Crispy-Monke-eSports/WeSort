package com.porter.wesort.data;

import java.util.Set;

/**
 * W-061: the pure classification of safety-sensitive blocks. In a dedicated agent profile the client refuses to
 * use, break, or route through these blocks by default, and only an explicit Manager policy may allow a specific
 * block after a client-side guard. Purely syntactic (a block registry path), so it is unit-testable without a
 * Minecraft client.
 */
public final class BlockSafety {
    /** Devices that are never used or broken without an explicit, allow-listed operation. */
    private static final Set<String> DEVICES = Set.of("lever", "tripwire_hook", "redstone_wire", "redstone_torch",
        "redstone_wall_torch", "redstone_block", "repeater", "comparator", "observer", "target", "piston", "sticky_piston",
        "piston_head", "moving_piston", "dispenser", "dropper", "trapped_chest", "crafter", "bell", "lectern",
        "daylight_detector", "lightning_rod", "sculk_sensor", "calibrated_sculk_sensor", "powered_rail", "activator_rail",
        "detector_rail", "nether_portal", "end_portal", "end_gateway");

    private BlockSafety() {}

    /** Passively triggered blocks that a route must never cross or touch. */
    public static boolean passiveTrigger(String id) {
        return id.endsWith("_pressure_plate") || id.equals("tripwire") || id.equals("detector_rail")
            || id.endsWith("sculk_sensor") || id.equals("sculk_shrieker");
    }

    /** Whether a block registry path is denied use/break and denied as a route cell in a dedicated profile. */
    public static boolean safetySensitive(String id) {
        return id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("_fence_gate")
            || id.endsWith("_button") || passiveTrigger(id) || DEVICES.contains(id);
    }
}

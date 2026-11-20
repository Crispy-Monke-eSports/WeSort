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
    private static final Set<String> DEVICES = Set.of(
            "lever",
            "tripwire_hook",
            "redstone_wire",
            "redstone_torch",
            "redstone_wall_torch",
            "redstone_block",
            "repeater",
            "comparator",
            "observer",
            "target",
            "piston",
            "sticky_piston",
            "piston_head",
            "moving_piston",
            "dispenser",
            "dropper",
            "trapped_chest",
            "crafter",
            "bell",
            "lectern",
            "daylight_detector",
            "lightning_rod",
            "sculk_sensor",
            "calibrated_sculk_sensor",
            "powered_rail",
            "activator_rail",
            "detector_rail",
            "nether_portal",
            "end_portal",
            "end_gateway");

    private BlockSafety() {}

    /**
     * The registry path of a block id: permits compare full namespaced ids
     * (minecraft:stone is not mod:stone), but this classification is syntactic
     * on the path, so the namespace is stripped before matching.
     */
    static String path(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Passively triggered blocks that a route must never cross or touch. */
    public static boolean passiveTrigger(String id) {
        String p = path(id);
        return p.endsWith("_pressure_plate")
                || p.equals("tripwire")
                || p.equals("detector_rail")
                || p.endsWith("sculk_sensor")
                || p.equals("sculk_shrieker");
    }

    /** Whether a block registry path is denied use/break and denied as a route cell in a dedicated profile. */
    public static boolean safetySensitive(String id) {
        String p = path(id);
        return p.endsWith("_door")
                || p.endsWith("_trapdoor")
                || p.endsWith("_fence_gate")
                || p.endsWith("_button")
                || passiveTrigger(id)
                || DEVICES.contains(p);
    }
}

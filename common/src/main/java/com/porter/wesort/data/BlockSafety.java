package com.porter.wesort.data;

import java.util.Set;

/** Classifies registry paths for the dedicated agent's interaction and navigation guards. */
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

    /** Blocks that can react to movement without a use or break action. */
    private static boolean passiveTrigger(String id) {
        String p = path(id);
        return p.endsWith("_pressure_plate")
                || p.equals("tripwire")
                || p.equals("detector_rail")
                || p.endsWith("sculk_sensor")
                || p.equals("sculk_shrieker");
    }

    /** Whether the dedicated agent must refuse active use or breaking. */
    public static boolean safetySensitive(String id) {
        String p = path(id);
        return p.endsWith("_door")
                || p.endsWith("_trapdoor")
                || p.endsWith("_fence_gate")
                || p.endsWith("_button")
                || passiveTrigger(id)
                || DEVICES.contains(p);
    }

    /**
     * Navigation is deliberately independent of the active-interaction denylist.
     * Baritone checks collision, support, and traversability with the live block
     * state; walking over redstone or through an open passage is not a USE or
     * BREAK operation. Adding a sensitive device must not silently ban walking.
     * Only dimension-changing blocks and a moving piston (unstable collision)
     * require a hard stop here. Fluid and unloaded-world checks live in NavigationGuard.
     * This does not authorize opening a closed door or any other interaction.
     */
    public static boolean blocksNavigation(String id) {
        return switch (path(id)) {
            case "nether_portal", "end_portal", "end_gateway", "moving_piston" -> true;
            default -> false;
        };
    }
}

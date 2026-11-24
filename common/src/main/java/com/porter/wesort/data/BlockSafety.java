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
     * Passage the agent may walk through without operating anything: pressure
     * plates and similar passive triggers, plus doors, fence gates and buttons.
     * A door the route leads through is opened by Baritone itself (wooden) or by
     * the stash's own pressure plate underfoot (already passable); stepping onto
     * a button does nothing. Active use or breaking stays forbidden via
     * {@link #safetySensitive(String)}. Trapdoors stay blocking: an open one is
     * a hole, and the cell check cannot tell open from closed.
     */
    private static boolean walkThrough(String id) {
        String p = path(id);
        return passiveTrigger(id) || p.endsWith("_door") || p.endsWith("_fence_gate") || p.endsWith("_button");
    }

    /** Walking past passive triggers is allowed, including when they activate redstone. */
    public static boolean blocksNavigation(String id) {
        return safetySensitive(id) && !walkThrough(id);
    }
}

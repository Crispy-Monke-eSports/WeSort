package com.porter.wesort.compat;

import com.porter.wesort.modules.StashManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Denies activation and mutation of safety-sensitive blocks in a dedicated agent profile. */
public final class WorldInteractionGuard {
    private static final Set<String> DEVICES = Set.of("lever", "tripwire_hook", "redstone_wire", "redstone_torch",
        "redstone_wall_torch", "redstone_block", "repeater", "comparator", "observer", "target", "piston", "sticky_piston",
        "piston_head", "moving_piston", "dispenser", "dropper", "trapped_chest", "crafter", "bell", "lectern",
        "daylight_detector", "lightning_rod", "sculk_sensor", "calibrated_sculk_sensor", "powered_rail", "activator_rail",
        "detector_rail", "nether_portal", "end_portal", "end_gateway");

    private WorldInteractionGuard() {}

    public static boolean dedicated() {
        var manager = Modules.get().get(StashManager.class);
        return manager != null && manager.dedicatedProfile();
    }

    public static boolean allowUse(BlockHitResult hit) {
        if (!dedicated()) return true;
        var position = hit.getBlockPos();
        return safeBlock(position) && safeBlock(position.relative(hit.getDirection()));
    }

    public static boolean allowBreak(BlockPos position) {
        return !dedicated() || safeBlock(position);
    }

    static boolean safeBlock(BlockPos position) {
        if (mc.level == null || !mc.level.isLoaded(position)) return false;
        var id = blockPath(position);
        return !id.endsWith("_door") && !id.endsWith("_trapdoor") && !id.endsWith("_fence_gate")
            && !id.endsWith("_button") && !passiveTrigger(id) && !DEVICES.contains(id);
    }

    static boolean passiveTrigger(String id) {
        return id.endsWith("_pressure_plate") || id.equals("tripwire") || id.equals("detector_rail")
            || id.endsWith("sculk_sensor") || id.equals("sculk_shrieker");
    }

    static String blockPath(BlockPos position) {
        return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(position).getBlock()).getPath();
    }
}

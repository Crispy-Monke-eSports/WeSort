package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.data.BlockSafety;
import com.porter.wesort.modules.StashManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;

/** Denies activation and mutation of safety-sensitive blocks in a dedicated agent profile. */
public final class WorldInteractionGuard {
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
        return !BlockSafety.safetySensitive(blockPath(position));
    }

    static String blockPath(BlockPos position) {
        return BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(position).getBlock())
                .getPath();
    }
}

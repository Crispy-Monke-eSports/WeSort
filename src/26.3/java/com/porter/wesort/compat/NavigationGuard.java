package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.data.BlockSafety;
import net.minecraft.core.BlockPos;

/** Checks the cells used by a route and the nearby passive triggers on the loaded client world. */
final class NavigationGuard {
    private NavigationGuard() {}

    static boolean permits(BlockPos feet) {
        if (!WorldInteractionGuard.safeBlock(feet)
                || !WorldInteractionGuard.safeBlock(feet.above())
                || !WorldInteractionGuard.safeBlock(feet.below())) return false;
        if (!mc.level.getFluidState(feet).isEmpty()
                || !mc.level.getFluidState(feet.above()).isEmpty()) return false;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                var neighbor = feet.offset(x, 0, z);
                if (!mc.level.isLoaded(neighbor)
                        || BlockSafety.passiveTrigger(WorldInteractionGuard.blockId(neighbor))) return false;
            }
        }
        return true;
    }
}

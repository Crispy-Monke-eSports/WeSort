package com.porter.wesort.compat;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import com.porter.wesort.data.BlockSafety;
import net.minecraft.core.BlockPos;

/** Checks the cells used by a route and the nearby passive triggers on the loaded client world. */
final class NavigationGuard {
    private NavigationGuard() {}

    static boolean permits(BlockPos feet) {
        return denial(feet) == null;
    }

    /** Exact fail-closed reason; logs never need to guess why a portal approach was rejected. */
    static String denial(BlockPos feet) {
        if (feet == null || mc.level == null) return "no connected world";
        for (BlockPos cell : new BlockPos[] {feet, feet.above(), feet.below()}) {
            if (!mc.level.isLoaded(cell)) return "unloaded cell at " + cell.toShortString();
            if (!WorldInteractionGuard.safeBlock(cell))
                return "safety-sensitive " + WorldInteractionGuard.blockId(cell) + " at " + cell.toShortString();
        }
        if (!mc.level.getFluidState(feet).isEmpty()
                || !mc.level.getFluidState(feet.above()).isEmpty()) return "fluid at " + feet.toShortString();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                var neighbor = feet.offset(x, 0, z);
                if (!mc.level.isLoaded(neighbor)) return "unloaded neighbor at " + neighbor.toShortString();
                String block = WorldInteractionGuard.blockId(neighbor);
                if (BlockSafety.passiveTrigger(block))
                    return "passive trigger " + block + " near " + neighbor.toShortString();
            }
        }
        return null;
    }
}

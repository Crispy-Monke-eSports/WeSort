package com.porter.wesort.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Geometry for entering a Nether portal rather than asking Baritone to stand inside a portal block. */
public final class PortalEntry {
    private PortalEntry() {}

    /**
     * The portal plane runs along its block-state axis. Approach it from one of the two
     * perpendicular sides, then walk through the plane under direct, bounded player input.
     */
    public static BlockPos approach(
            BlockPos portal, Direction.Axis axis, double playerX, double playerZ, boolean otherSide) {
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) throw new IllegalArgumentException("portal axis");
        boolean positive = axis == Direction.Axis.X ? playerZ >= portal.getZ() + 0.5 : playerX >= portal.getX() + 0.5;
        if (otherSide) positive = !positive;
        Direction outward = axis == Direction.Axis.X
                ? (positive ? Direction.SOUTH : Direction.NORTH)
                : (positive ? Direction.EAST : Direction.WEST);
        return portal.relative(outward);
    }

    /** Close enough to leave the safe Baritone approach and enter the portal by hand. */
    public static boolean atApproach(BlockPos approach, double x, double y, double z) {
        double dx = x - approach.getX() - 0.5;
        double dz = z - approach.getZ() - 0.5;
        return Math.abs(y - approach.getY()) <= 1.0 && dx * dx + dz * dz <= 1.15 * 1.15;
    }

    /** Minecraft yaw: 0 faces south, 90 faces west. */
    public static float yawTowards(BlockPos portal, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(x - (portal.getX() + 0.5), (portal.getZ() + 0.5) - z));
    }
}

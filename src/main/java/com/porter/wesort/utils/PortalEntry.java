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

    /**
     * A walkable stand can be one block above or below the bottom portal block:
     * lobby paths often use slabs or stairs. Only a perpendicular adjacent cell
     * may be used, never a cell inside the portal or along its frame.
     */
    public static boolean adjacentStand(BlockPos portal, Direction.Axis axis, BlockPos stand) {
        if (portal == null || stand == null) return false;
        if (Math.abs(stand.getY() - portal.getY()) > 1) return false;
        if (axis == Direction.Axis.X) {
            return stand.getX() == portal.getX() && Math.abs(stand.getZ() - portal.getZ()) == 1;
        }
        if (axis == Direction.Axis.Z) {
            return stand.getZ() == portal.getZ() && Math.abs(stand.getX() - portal.getX()) == 1;
        }
        return false;
    }

    /** Close enough to leave the safe Baritone approach and enter the portal by hand. */
    public static boolean atApproach(BlockPos approach, double x, double y, double z) {
        double dx = x - approach.getX() - 0.5;
        double dz = z - approach.getZ() - 0.5;
        return Math.abs(y - approach.getY()) <= 1.0 && dx * dx + dz * dz <= 0.85 * 0.85;
    }

    /** Minecraft yaw: 0 faces south, 90 faces west. */
    public static float yawTowards(BlockPos portal, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(x - (portal.getX() + 0.5), (portal.getZ() + 0.5) - z));
    }
}

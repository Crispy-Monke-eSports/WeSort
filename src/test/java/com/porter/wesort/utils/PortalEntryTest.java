package com.porter.wesort.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PortalEntryTest {
    private static final BlockPos PORTAL = new BlockPos(10, 64, 20);

    @Test
    void approachesFromTheNormalNotAlongThePortalPlane() {
        assertEquals(new BlockPos(10, 64, 21), PortalEntry.approach(PORTAL, Direction.Axis.X, 10.5, 30, false));
        assertEquals(new BlockPos(10, 64, 19), PortalEntry.approach(PORTAL, Direction.Axis.X, 10.5, 30, true));
        assertEquals(new BlockPos(11, 64, 20), PortalEntry.approach(PORTAL, Direction.Axis.Z, 30, 20.5, false));
        assertEquals(new BlockPos(9, 64, 20), PortalEntry.approach(PORTAL, Direction.Axis.Z, 30, 20.5, true));
        assertThrows(
                IllegalArgumentException.class, () -> PortalEntry.approach(PORTAL, Direction.Axis.Y, 10, 20, false));
    }

    @Test
    void transitionsToManualEntryOnlyAtTheApproach() {
        BlockPos approach = new BlockPos(10, 64, 21);
        assertTrue(PortalEntry.atApproach(approach, 10.5, 64, 21.5));
        assertTrue(PortalEntry.atApproach(approach, 10.8, 64.5, 22.0));
        assertFalse(PortalEntry.atApproach(approach, 11.5, 64, 21.5));
        assertFalse(PortalEntry.atApproach(approach, 10.5, 64, 24));
        assertFalse(PortalEntry.atApproach(approach, 10.5, 66, 21.5));
    }

    @Test
    void facesTowardPortalCenter() {
        assertEquals(0, PortalEntry.yawTowards(PORTAL, 10.5, 19.5), 0.01);
        assertEquals(180, PortalEntry.yawTowards(PORTAL, 10.5, 21.5), 0.01);
        assertEquals(90, PortalEntry.yawTowards(PORTAL, 11.5, 20.5), 0.01);
        assertEquals(-90, PortalEntry.yawTowards(PORTAL, 9.5, 20.5), 0.01);
    }
}

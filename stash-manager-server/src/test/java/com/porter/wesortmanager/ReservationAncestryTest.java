package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReservationAncestryTest {
    @Test
    void ancestorRelationIsSegmentWise() {
        assertTrue(PhysicalReservations.isAncestor("2/4", "2/4/0"));
        assertTrue(PhysicalReservations.isAncestor("2", "2/4/0"));
        assertFalse(PhysicalReservations.isAncestor("2/4/0", "2/4/0"));
        assertFalse(PhysicalReservations.isAncestor("2/4/0", "2/4"));
        // "2/10" is NOT a child of "2/1" — never String.startsWith.
        assertFalse(PhysicalReservations.isAncestor("2/1", "2/10"));
        assertFalse(PhysicalReservations.isAncestor("2", "20"));
        assertFalse(PhysicalReservations.isAncestor(null, "2/4"));
        assertFalse(PhysicalReservations.isAncestor("2/4", null));
    }
}

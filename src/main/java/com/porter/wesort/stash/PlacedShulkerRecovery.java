package com.porter.wesort.stash;

import java.util.List;
import java.util.Set;

/**
 * Ownership rule for placed shulker boxes. A box stays placed only while its
 * recorded owner is the currently running order. Unclaimed boxes, cancelled
 * orders, failed orders and orders from previous sessions are recoverable.
 */
public final class PlacedShulkerRecovery {
    private PlacedShulkerRecovery() {}

    public static boolean shouldRecover(String placedOrderId, String activeOrderId) {
        return placedOrderId == null || placedOrderId.isEmpty() || !placedOrderId.equals(activeOrderId);
    }

    /** Identify the additional exact copy independently of inventory iteration order. */
    public static int pickupSlot(List<Integer> matchingSlots, Set<Integer> occupiedBefore, int copiesBefore) {
        if (matchingSlots.size() != copiesBefore + 1) return -1;
        if (copiesBefore == 0) return matchingSlots.getFirst();
        int found = -1;
        for (int slot : matchingSlots) {
            if (occupiedBefore.contains(slot)) continue;
            if (found >= 0) return -1;
            found = slot;
        }
        return found;
    }

    public static boolean depositUnchanged(boolean exactStackStillHeld, int before, int after) {
        return exactStackStillHeld && before >= 0 && after == before;
    }

    public static boolean depositConfirmed(boolean carrySlotEmpty, int before, int after, int carried) {
        return carrySlotEmpty && before >= 0 && carried > 0 && after == before + carried;
    }
}

package com.porter.wesort.stash;

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
}

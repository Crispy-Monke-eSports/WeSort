package com.porter.wesortmanager;

/** Physical order progress. A warehouse handoff ends packing and begins package custody. */
enum OrderStage {
    CREATED,
    RESERVED,
    PACKING,
    PICKUP_READY,
    /**
     * Delivery-chain stages for a future custody flow. The current bot API never emits them:
     * a bot report of {@code delivered} means warehouse staging and lands on PICKUP_READY
     * (or RECOVERING when uncertain), so the implemented terminal set is PICKUP_READY plus
     * the verified sweep release. PICKED_UP and later stay reserved, never assigned.
     */
    PICKED_UP,
    IN_TRANSIT,
    AWAITING_CUSTOMER,
    DELIVERED,
    FAILED,
    RECOVERING,
    /** Retained only for migration of persisted orders from older Manager versions. */
    NEEDS_RECONCILIATION,
    CANCELLED;

    boolean allows(OrderStage next) {
        if (this == next) return true;
        return switch (this) {
            case CREATED -> next == RESERVED || next == CANCELLED || next == FAILED;
            case RESERVED -> next == PACKING || next == CANCELLED || next == FAILED || next == RECOVERING;
            case PACKING -> next == PICKUP_READY || next == CANCELLED || next == FAILED || next == RECOVERING;
            case PICKUP_READY -> next == PICKED_UP || next == RECOVERING;
            case PICKED_UP -> next == IN_TRANSIT || next == RECOVERING;
            case IN_TRANSIT -> next == AWAITING_CUSTOMER || next == RECOVERING;
            case AWAITING_CUSTOMER -> next == DELIVERED || next == RECOVERING;
            case RECOVERING -> next == FAILED || next == CANCELLED;
            case NEEDS_RECONCILIATION -> next == RECOVERING || next == FAILED || next == CANCELLED;
            case DELIVERED, FAILED, CANCELLED -> false;
        };
    }

    boolean archiveable() {
        return this == DELIVERED || this == FAILED || this == CANCELLED;
    }

    static OrderStage legacy(String status) {
        return switch (status) {
            case "pending" -> RESERVED;
            case "accepted", "in_progress" -> PACKING;
            case "packed" -> PICKUP_READY;
            case "delivered", "needs_reconciliation" -> NEEDS_RECONCILIATION;
            case "failed" -> FAILED;
            case "cancelled" -> CANCELLED;
            default -> throw new IllegalArgumentException("Unknown order status");
        };
    }
}

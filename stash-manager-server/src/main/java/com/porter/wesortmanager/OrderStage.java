package com.porter.wesortmanager;

/** Physical order progress. A warehouse handoff ends packing and begins package custody. */
enum OrderStage {
    CREATED,
    RESERVED,
    PACKING,
    PICKUP_READY,
    PICKED_UP,
    IN_TRANSIT,
    AWAITING_CUSTOMER,
    DELIVERED,
    FAILED,
    NEEDS_RECONCILIATION,
    CANCELLED;

    boolean allows(OrderStage next) {
        if (this == next) return true;
        return switch (this) {
            case CREATED -> next == RESERVED || next == CANCELLED || next == FAILED;
            case RESERVED -> next == PACKING || next == CANCELLED || next == FAILED || next == NEEDS_RECONCILIATION;
            case PACKING -> next == PICKUP_READY || next == CANCELLED || next == FAILED || next == NEEDS_RECONCILIATION;
            case PICKUP_READY -> next == PICKED_UP || next == NEEDS_RECONCILIATION;
            case PICKED_UP -> next == IN_TRANSIT || next == NEEDS_RECONCILIATION;
            case IN_TRANSIT -> next == AWAITING_CUSTOMER || next == NEEDS_RECONCILIATION;
            case AWAITING_CUSTOMER -> next == DELIVERED || next == NEEDS_RECONCILIATION;
            case NEEDS_RECONCILIATION -> next == FAILED || next == CANCELLED || next == PICKUP_READY;
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

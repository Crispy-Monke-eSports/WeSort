package com.porter.wesortmanager;

import java.util.List;
import java.util.Map;

/**
 * PR3 (#265): typed order transition changeset (additive).
 *
 * <p>Declares the contract future diff-writes must follow: instead of
 * serializing the whole order book per mutation, a commit carries only the
 * changed order/package/lease/reservation/custody rows plus new immutable
 * events, the idempotency receipt, and an optional terminal resume receipt —
 * all inside the single SQLite transaction (#140). Not yet wired into
 * {@link OrderStore}; {@code OrderBookRepository.commit} still takes the full
 * snapshot until the cutover lands.
 */
public record OrderChangeSet(
        String orderId,
        long expectedRevision,
        long nextRevision,
        List<Map<String, String>> changedRows,
        List<Map<String, String>> newEvents,
        Map<String, String> idempotencyReceipt,
        Map<String, String> terminalResumeReceipt) {
    public OrderChangeSet {
        if (orderId == null || orderId.isEmpty()) throw new IllegalArgumentException("orderId required");
        if (nextRevision != expectedRevision + 1)
            throw new IllegalArgumentException("nextRevision must be expectedRevision + 1");
        changedRows = changedRows == null ? List.of() : List.copyOf(changedRows);
        newEvents = newEvents == null ? List.of() : List.copyOf(newEvents);
        if (changedRows.isEmpty() && newEvents.isEmpty() && terminalResumeReceipt == null)
            throw new IllegalArgumentException("empty changeset commits nothing");
    }
}

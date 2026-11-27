package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * PR3 Step 5: the reservation checks shared by order placement and the order
 * lifecycle, moved verbatim out of OrderStore (no behavior change, Refs #142).
 * The slot reader stays in {@link PhysicalReservations}; this policy only
 * answers whether held, allocated or guarded stock still covers an order.
 */
final class ReservationPolicy {
    private final IndexStore index;

    ReservationPolicy(IndexStore index) {
        this.index = index;
    }

    Map<String, Long> reserved(OrderStore.Book b) {
        Map<String, Long> out = new HashMap<>();
        for (OrderStore.Order o : b.orders) {
            // The waiting queue is intentionally *not* a physical or aggregate
            // reservation. Availability must not advertise it as committed stock.
            if (o.reservationDeferred || (!OrderStore.OPEN.contains(o.status) && !holdsPhysical(o))) continue;
            for (OrderStore.Line l : OrderPlacementService.explodeOrderLines(o)) out.merge(l.ref(), l.count(), Long::sum);
        }
        return out;
    }
    boolean holdsPhysical(OrderStore.Order order) {
        return order.allocations != null
                && order.releasedAt == 0
                && (OrderStore.OPEN.contains(order.status) || order.claimedAt > 0);
    }
    JsonObject sourceGuards(String server, String stash) throws IOException {
        var out = new JsonObject();
        for (var source : index.stock().sources())
            if (source.server().equals(server)
                    && source.stash().equals(stash)
                    && source.status().equals("FRESH")
                    && source.value().has("slotFingerprint"))
                out.add(source.value().get("id").getAsString(), source.value().get("slotFingerprint"));
        return out.isEmpty() ? null : out;
    }
    boolean matchesSources(OrderStore.Order order) throws IOException {
        if (order.allocations != null) {
            return PhysicalReservations.read(index.stock(), order.server, order.stash)
                    .matches(order.allocations);
        }
        if (order.sourceGuards == null) return true; // Earlier protocol orders retain their existing aggregate checks.
        var now = sourceGuards(order.server, order.stash);
        if (now == null) return false;
        for (var expected : order.sourceGuards.entrySet())
            if (!expected.getValue().equals(now.get(expected.getKey()))) return false;
        return true;
    }
    boolean isExecuting(OrderStore.Order order) {
        return order.status.equals("accepted") || order.status.equals("in_progress");
    }
    boolean orderSourcesReady(OrderStore.Order order, IndexStore.OrderCounts counts, String agent) throws IOException {
        if (order.agent != null && !order.agent.equals(agent)) return false;
        // A recovering agent must be allowed to retrieve its existing items
        // even after source fingerprints changed during the interruption.
        if (order.stage() == OrderStage.RECOVERING) return order.agent != null && order.agent.equals(agent);
        if (order.allocations == null) {
            return matchesSources(order)
                    && order.lines.stream().allMatch(line -> counts.total(line.type(), line.key()) >= line.count());
        }
        return agent != null && matchesSources(order);
    }
}

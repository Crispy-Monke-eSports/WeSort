package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;

/** No paths, identities or secrets in health responses. */
final class RuntimeHealth {
    private final AtomicBoolean ready = new AtomicBoolean();
    private volatile boolean userRegistryUnavailable;

    void userRegistryUnavailable(boolean unavailable) {
        userRegistryUnavailable = unavailable;
    }

    RuntimeHealth(boolean ready) {
        this.ready.set(ready);
    }

    boolean ready() {
        return ready.get();
    }

    void ready(boolean value) {
        ready.set(value);
    }

    private record Sample(
            long checkedAt,
            long indexAgeMs,
            boolean staleIndex,
            long stockAgeMs,
            boolean staleStock,
            int stuckOrders,
            int recoveringOrders,
            int reservationHoldingOrders) {}

    private volatile Sample latest;

    void sample(String server, IndexStore index, OrderStore orders, long now, long maxAge) {
        long oldest = index.oldestIndexTime(server, now);
        long age = oldest == 0 ? -1 : now - oldest;
        long stockAge = oldestStockAge(index, server, now);
        Sample next = new Sample(
                now,
                age,
                age < 0 || age >= maxAge,
                stockAge,
                stockAge >= 0 && stockAge >= maxAge,
                orders.stuckOrders(server, now),
                orders.recoveringOrders(server),
                orders.reservationHoldingOrders(server));
        Sample previous = latest;
        latest = next;
        if (previous == null
                || previous.staleIndex != next.staleIndex
                || previous.staleStock != next.staleStock
                || previous.stuckOrders != next.stuckOrders
                || previous.recoveringOrders != next.recoveringOrders) {
            System.out.println("Manager health: staleIndex=" + next.staleIndex + ", staleStock=" + next.staleStock
                    + ", stuckOrders=" + next.stuckOrders + ", recovering=" + next.recoveringOrders);
        }
    }

    /**
     * Oldest verified stock observation on the server, or -1 when there is no
     * stock yet. Follows the ledger clock (including any rescan freeze), so it
     * means what the order path actually reserves against.
     */
    private static long oldestStockAge(IndexStore index, String server, long now) {
        try {
            long oldest = Long.MAX_VALUE;
            boolean any = false;
            for (StockLedger.Source source : index.stock().sources()) {
                if (!source.server().equals(server)) continue;
                any = true;
                oldest = Math.min(oldest, source.verifiedAt());
            }
            if (!any || oldest == Long.MAX_VALUE || oldest <= 0 || oldest > now) return -1;
            return now - oldest;
        } catch (java.io.IOException unavailable) {
            return -1;
        }
    }

    JsonObject detail(long now) {
        JsonObject body = new JsonObject();
        Sample sample = latest;
        String status = "unknown";
        if (sample != null) {
            body.addProperty("oldestIndexAgeMs", sample.indexAgeMs < 0 ? null : Long.valueOf(sample.indexAgeMs));
            body.addProperty("staleIndex", sample.staleIndex);
            body.addProperty("oldestStockAgeMs", sample.stockAgeMs < 0 ? null : Long.valueOf(sample.stockAgeMs));
            body.addProperty("staleStock", sample.staleStock);
            body.addProperty("stuckOrders", sample.stuckOrders);
            body.addProperty("recovering", sample.recoveringOrders);
            body.addProperty("reservationHoldingOrders", sample.reservationHoldingOrders);
            body.addProperty("checkedAt", sample.checkedAt);
            var warnings = new com.google.gson.JsonArray();
            if (sample.staleIndex) warnings.add("STALE_INDEX");
            if (sample.staleStock) warnings.add("STALE_STOCK");
            if (sample.stuckOrders > 0) warnings.add("STUCK_ORDERS");
            if (sample.recoveringOrders > 0) warnings.add("RECOVERING_ORDERS");
            body.add("warnings", warnings);
            // Data freshness is a warning, not a readiness flip: a slow rescan
            // pass must not take the Manager out of rotation. Only a missing
            // sample (the sampler itself is dead) or a real blocker degrades.
            if (now >= sample.checkedAt && now - sample.checkedAt < 60_000) status = "ready";
        }
        if (userRegistryUnavailable) {
            body.addProperty("userRegistryUnavailable", true);
            status = "degraded";
        }
        body.addProperty("status", ready() ? status : "starting");
        return body;
    }

    void register(Router router) {
        router.exact(Router.GET, "/health", Router.Guard.OPEN, ex -> {
                    // WALL-CLOCK (justified, not domain time): point-in-time health snapshot stamp.
                    JsonObject body = detail(System.currentTimeMillis());
                    Http.json(ex, "ready".equals(body.get("status").getAsString()) ? 200 : 503, body);
                })
                .exact(
                        Router.GET,
                        "/health/live",
                        Router.Guard.OPEN,
                        ex -> Http.json(ex, 200, com.google.gson.JsonParser.parseString("{\"status\":\"live\"}")))
                .exact(
                        Router.GET,
                        "/health/ready",
                        Router.Guard.OPEN,
                        ex -> Http.json(
                                ex,
                                ready() ? 200 : 503,
                                com.google.gson.JsonParser.parseString(
                                        ready() ? "{\"status\":\"ready\"}" : "{\"status\":\"starting\"}")));
    }
}

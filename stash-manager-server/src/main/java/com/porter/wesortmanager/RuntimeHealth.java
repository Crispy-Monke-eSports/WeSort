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
            long checkedAt, long indexAgeMs, boolean staleIndex, int stuckOrders, int reservationHoldingOrders) {}

    private volatile Sample latest;

    void sample(String server, IndexStore index, OrderStore orders, long now, long maxAge) {
        long oldest = index.oldestIndexTime(server, now);
        long age = oldest == 0 ? -1 : now - oldest;
        Sample next = new Sample(
                now,
                age,
                age < 0 || age >= maxAge,
                orders.stuckOrders(server, now),
                orders.reservationHoldingOrders(server));
        Sample previous = latest;
        latest = next;
        if (previous == null || previous.staleIndex != next.staleIndex || previous.stuckOrders != next.stuckOrders) {
            System.out.println("Manager health: staleIndex=" + next.staleIndex + ", stuckOrders=" + next.stuckOrders);
        }
    }

    JsonObject detail(long now) {
        JsonObject body = new JsonObject();
        Sample sample = latest;
        String status = "unknown";
        if (sample != null) {
            body.addProperty("oldestIndexAgeMs", sample.indexAgeMs < 0 ? null : Long.valueOf(sample.indexAgeMs));
            body.addProperty("staleIndex", sample.staleIndex);
            body.addProperty("stuckOrders", sample.stuckOrders);
            body.addProperty("reservationHoldingOrders", sample.reservationHoldingOrders);
            body.addProperty("checkedAt", sample.checkedAt);
            if (now >= sample.checkedAt && now - sample.checkedAt < 60_000)
                status = sample.staleIndex || sample.stuckOrders > 0 ? "degraded" : "ready";
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

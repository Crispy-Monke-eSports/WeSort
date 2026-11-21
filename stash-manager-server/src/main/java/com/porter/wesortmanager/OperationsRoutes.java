package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/** Operational counts from the same observed stock, orders and agent sessions used by Manager. */
final class OperationsRoutes {
    private final IndexStore index;
    private final OrderStore orders;
    private final BotHub hub;

    OperationsRoutes(IndexStore index, OrderStore orders, BotHub hub) {
        this.index = index;
        this.orders = orders;
        this.hub = hub;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/operations", Router.Guard.SESSION, Router.Perm.ACTIVITY, this::metrics);
    }

    private void metrics(HttpExchange ex) throws IOException {
        var viewer = Router.user(ex);
        var perms = viewer.perms();
        var alerts = new TreeMap<String, Integer>();
        var agents = new TreeMap<String, Integer>();
        var stock = new TreeMap<String, Integer>();
        long now = hub.now();
        for (var agent : hub.all()) {
            if (!perms.seesBot(agent.botId)) continue;
            synchronized (agent) {
                if (!BotHub.online(agent, now)) {
                    count(agents, "OFFLINE");
                    count(alerts, "DISCONNECTED");
                } else {
                    count(agents, "ONLINE");
                    if (agent.activity != null && now - agent.activityAt < BotHub.ONLINE_MS) {
                        String code = AddonRoutes.string(agent.activity, "incident");
                        if (code != null) count(alerts, code);
                    }
                }
            }
        }
        if (perms.view()) {
            var seen = new HashSet<String>();
            for (var source : index.stock().sources()) {
                if (!perms.seesStash(source.stash())
                        || !seen.add(source.value().get("id").getAsString())) continue;
                String state = source.status();
                count(stock, state);
                if (state.startsWith("STALE")) count(alerts, "STALE");
                else if ("UNREACHABLE".equals(state)) count(alerts, "UNREACHABLE");
            }
        }
        var states = perms.ordersAll() ? orders.activeStates(viewer) : Map.<String, Integer>of();
        var reconciliation = states.get(OrderStage.NEEDS_RECONCILIATION.name());
        if (reconciliation != null) alerts.put("NEEDS_RECONCILIATION", reconciliation);
        var notices = new JsonArray();
        alerts.forEach((code, count) -> {
            var alert = new JsonObject();
            alert.addProperty("code", code);
            alert.addProperty("count", count);
            notices.add(alert);
        });
        var out = new JsonObject();
        out.addProperty("at", now);
        out.add("stock", Json.GSON.toJsonTree(stock));
        out.add("agents", Json.GSON.toJsonTree(agents));
        out.add("orders", Json.GSON.toJsonTree(states));
        out.add("alerts", notices);
        Http.json(ex, 200, out);
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }
}

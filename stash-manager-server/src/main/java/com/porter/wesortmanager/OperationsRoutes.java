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
    /** Alert counts are bounded so one griefed server cannot blow up the payload. */
    static final int MAX_ALERT_COUNT = 999;

    private final IndexStore index;
    private final OrderStore orders;
    private final BotHub hub;
    private final AgentCredentials credentials;

    OperationsRoutes(IndexStore index, OrderStore orders, BotHub hub, AgentCredentials credentials) {
        this.index = index;
        this.orders = orders;
        this.hub = hub;
        this.credentials = credentials;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/operations", Router.Guard.SESSION, Router.Perm.ACTIVITY, this::metrics);
    }

    /**
     * The alert code for one stock state: a STALE source with a rescan
     * outstanding is the bot visibly working (expected), not a new failure.
     * Returns null for states that never alert.
     */
    static String stockAlertCode(String state, boolean rescanPending) {
        if (state.startsWith("STALE")) return rescanPending ? "STALE_RESCAN" : "STALE_AGE";
        return switch (state) {
            case "UNREACHABLE" -> "UNREACHABLE";
            case "UNKNOWN" -> "UNKNOWN";
            case "MISSING" -> "MISSING";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> null;
        };
    }

    private void metrics(HttpExchange ex) throws IOException {
        var viewer = Router.user(ex);
        var perms = viewer.perms();
        var alerts = new TreeMap<String, Integer>();
        var agents = new TreeMap<String, Integer>();
        var stock = new TreeMap<String, Integer>();
        var presence = new JsonArray();
        long now = hub.now();
        for (var agent : hub.all()) {
            if (!perms.seesBot(agent.botId)) continue;
            synchronized (agent) {
                boolean online = BotHub.online(agent, now);
                String cause = online
                        ? BotHub.presenceCause(agent, now)
                        : credentials.recentAuthFailure(agent.botId, now) ? "auth-failure" : "silent";
                if (online) {
                    count(agents, "ONLINE");
                    if (agent.activity != null && now - agent.activityAt < BotHub.ONLINE_MS) {
                        String code = AddonRoutes.string(agent.activity, "incident");
                        if (code != null) count(alerts, code);
                    }
                } else {
                    count(agents, "OFFLINE");
                    count(alerts, "auth-failure".equals(cause) ? "AUTH_FAILURE" : "DISCONNECTED");
                }
                var row = new JsonObject();
                row.addProperty("botId", agent.botId);
                row.addProperty("online", online);
                row.addProperty("lastSeen", agent.lastSeen);
                row.addProperty("cause", cause);
                presence.add(row);
            }
        }
        if (perms.view()) {
            var seen = new HashSet<String>();
            for (var source : index.stock().sources()) {
                if (!perms.seesStash(source.stash())
                        || !seen.add(source.value().get("id").getAsString())) continue;
                String state = source.status();
                count(stock, state);
                String code = stockAlertCode(state, index.rescanPending(source.server(), source.stash()) != null);
                if (code != null) count(alerts, code);
            }
            for (var en : index.stashBlockers().entrySet()) {
                if (!perms.seesStash(en.getKey())) continue;
                for (String code : en.getValue()) count(alerts, code);
            }
        }
        var states = perms.ordersAll() ? orders.activeStates(viewer) : Map.<String, Integer>of();
        if (perms.ordersAll()) {
            int approvals = orders.pendingApprovals(viewer);
            if (approvals > 0) alerts.put("APPROVAL_PENDING", Math.min(approvals, MAX_ALERT_COUNT));
            int cancels = orders.cancelRequestedOpen(viewer);
            if (cancels > 0) alerts.put("CANCEL_REQUESTED", Math.min(cancels, MAX_ALERT_COUNT));
        }
        var notices = new JsonArray();
        alerts.forEach((code, count) -> {
            var alert = new JsonObject();
            alert.addProperty("code", code);
            alert.addProperty("count", Math.min(count, MAX_ALERT_COUNT));
            notices.add(alert);
        });
        var out = new JsonObject();
        out.addProperty("at", now);
        out.add("stock", Json.GSON.toJsonTree(stock));
        out.add("agents", Json.GSON.toJsonTree(agents));
        out.add("presence", presence);
        out.add("orders", Json.GSON.toJsonTree(states));
        out.add("alerts", notices);
        Http.json(ex, 200, out);
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, (old, one) -> Math.min(MAX_ALERT_COUNT, old + one));
    }
}

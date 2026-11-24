package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Locale;

/** Durable guild order history for the installation Owner. */
final class OrderAuditRoutes {
    private final OrderStore orders;
    private final IndexStore stock;

    OrderAuditRoutes(OrderStore orders, IndexStore stock) {
        this.orders = orders;
        this.stock = stock;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/audit/orders", Router.Guard.SESSION, Router.Perm.OWNER, this::search);
    }

    private void search(HttpExchange ex) throws IOException {
        var viewer = Router.user(ex);
        if (!viewer.perms().view()) {
            Router.forbid(ex, "view stashes", "view");
            return;
        }
        var query = Http.query(ex);
        int limit;
        long since;
        long until;
        try {
            limit = Math.clamp(Integer.parseInt(query.getOrDefault("limit", "100")), 1, 200);
            since = Long.parseLong(query.getOrDefault("since", "0"));
            until = Long.parseLong(query.getOrDefault("until", Long.toString(Long.MAX_VALUE)));
            if (since < 0 || until < since) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            Http.error(ex, 400, "Invalid audit limit or time range");
            return;
        }
        String id = query.getOrDefault("order", "").strip();
        String person = query.getOrDefault("person", "").strip();
        String item = query.getOrDefault("item", "").strip();
        String stash = query.getOrDefault("stash", "").strip();
        var out = new JsonObject();
        var selected = orders.auditOrders(
                viewer,
                order -> (id.isEmpty() || order.id.equals(id))
                        && order.createdAt <= until
                        && order.updatedAt >= since
                        && includes(order.stash, stash)
                        && (item.isEmpty() || order.lines.stream().anyMatch(line -> includes(line.key(), item)))
                        && (person.isEmpty()
                                || includes(order.placedById, person)
                                || includes(order.placedByName, person)
                                || includes(order.verifiedRecipient, person)
                                || includes(order.decidedBy, person)),
                limit);
        if (!id.isEmpty() && !selected.isEmpty()) {
            var order = selected.get(0).getAsJsonObject();
            order.add(
                    "physicalOperations",
                    stock.stock()
                            .orderOperationHistory(
                                    id,
                                    order.get("server").getAsString(),
                                    order.get("stash").getAsString()));
        }
        out.add("orders", selected);
        Http.json(ex, 200, out);
    }

    private static boolean includes(String value, String filter) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}

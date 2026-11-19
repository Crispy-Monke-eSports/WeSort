package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.data.Handoff;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** Owner handoff management and the signed agent arrive/no-show/drop endpoint (W-039). */
final class HandoffRoutes {
    private final Handoffs handoffs;
    private final OrderStore orders;
    private final Quotas quotas;
    private final AuditLog audit;

    HandoffRoutes(Handoffs handoffs, OrderStore orders, Quotas quotas, AuditLog audit) {
        this.handoffs = handoffs;
        this.orders = orders;
        this.quotas = quotas;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/handoffs", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/handoffs", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::manage);
        router.exact(Router.POST, "/api/v1/handoffs/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) {
            Http.error(ex, 404, "not found");
            return;
        }
        var out = new JsonObject();
        out.add("handoffs", handoffs.list(Http.query(ex).get("order")));
        Http.json(ex, 200, out);
    }

    private void manage(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a handoff management request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        if (action == null || !List.of("schedule", "confirm", "resolve").contains(action)) {
            Http.error(ex, 400, "action must be schedule, confirm or resolve");
            return;
        }
        try {
            Handoff result;
            switch (action) {
                case "schedule" -> {
                    String orderId = AddonRoutes.string(body, "orderId");
                    String recipient = AddonRoutes.string(body, "recipient");
                    String meetingPoint = AddonRoutes.string(body, "meetingPoint");
                    if (orderId == null || recipient == null || meetingPoint == null) {
                        Http.error(ex, 400, "orderId, recipient and meetingPoint are required");
                        return;
                    }
                    result = handoffs.schedule(orderId, recipient, meetingPoint, AddonRoutes.string(body, "agent"));
                }
                case "confirm" ->
                    result = handoffs.confirm(requireId(body), Router.user(ex).id());
                default ->
                    result = handoffs.resolve(
                            requireId(body), outcome(body), Router.user(ex).id());
            }
            // Step 4 (W-026/W-059): a verified handoff outcome settles the linked order and its quota.
            try {
                settleOrder(result, Router.user(ex), AuthRoutes.address(ex));
            } catch (OrderStore.OrderException conflict) {
                Http.error(ex, conflict.status, conflict.getMessage());
                return;
            }
            audit.add(Router.user(ex), "handoff." + action, result.id(), AuthRoutes.address(ex));
            Http.json(ex, 200, result.json());
        } catch (Handoffs.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a handoff request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        String agent = AddonRoutes.string(body, "agent");
        if (action == null
                || agent == null
                || !List.of("arrive", "no-show", "drop").contains(action)) {
            Http.error(ex, 400, "action (arrive, no-show, drop) and agent are required");
            return;
        }
        try {
            Handoff result;
            switch (action) {
                case "arrive" -> result = handoffs.arrive(requireId(body), agent);
                case "no-show" -> result = handoffs.noShow(requireId(body), agent);
                default -> result = handoffs.dropUnverified(requireId(body), agent, AddonRoutes.string(body, "reason"));
            }
            audit.add(
                    "agent:" + agent, "Signed WeSort agent", "handoff." + action, result.id(), AuthRoutes.address(ex));
            Http.json(ex, 200, result.json());
        } catch (Handoffs.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid handoff request");
        }
    }

    private static String requireId(JsonObject body) {
        String id = AddonRoutes.string(body, "id");
        if (id == null) throw new IllegalArgumentException("id is required");
        return id;
    }

    /**
     * Step 4 (W-026/W-059): a verified handoff outcome settles the linked guild order. DELIVERED walks the
     * order to DELIVERED and consumes its quota; RETURNED fences it for reconciliation (the reservation is
     * kept: a re-driven delivery still consumes exactly once, a terminal cancel releases it); an arrival
     * fences it as awaiting the customer. Unknown legacy orders only affect the handoff.
     */
    private void settleOrder(Handoff result, Users.Principal who, String address)
            throws IOException, OrderStore.OrderException {
        String actor = who == null ? "owner" : who.id();
        try {
            if (result.state() == Handoff.State.DELIVERED) {
                orders.advanceToward(result.orderId(), OrderStage.DELIVERED, actor);
                quotas.consume(result.orderId());
                audit.add(who, "order.quota.consume", result.orderId(), address);
            } else if (result.state() == Handoff.State.RETURNED) {
                orders.advanceToward(result.orderId(), OrderStage.NEEDS_RECONCILIATION, actor);
            } else if (result.state() == Handoff.State.AWAITING_RECIPIENT) {
                orders.advanceToward(result.orderId(), OrderStage.AWAITING_CUSTOMER, actor);
            }
        } catch (OrderStore.OrderException unknown) {
            if (unknown.status != 404) throw unknown;
        }
    }

    private static Handoff.State outcome(JsonObject body) {
        String outcome = AddonRoutes.string(body, "outcome");
        if ("delivered".equals(outcome)) return Handoff.State.DELIVERED;
        if ("returned".equals(outcome)) return Handoff.State.RETURNED;
        throw new IllegalArgumentException("outcome must be delivered or returned");
    }
}

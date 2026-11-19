package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.data.Deposit;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** Owner deposit ledger and the signed agent begin/confirm/reconcile endpoint (W-038). */
final class DepositRoutes {
    private final Deposits deposits;
    private final OrderStore orders;
    private final Quotas quotas;
    private final AuditLog audit;

    DepositRoutes(Deposits deposits, OrderStore orders, Quotas quotas, AuditLog audit) {
        this.deposits = deposits;
        this.orders = orders;
        this.quotas = quotas;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/deposits", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/deposits/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) {
            Http.error(ex, 404, "not found");
            return;
        }
        var out = new JsonObject();
        out.add("deposits", deposits.list(Http.query(ex).get("order")));
        Http.json(ex, 200, out);
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a deposit request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        String packageId = AddonRoutes.string(body, "packageId");
        String agent = AddonRoutes.string(body, "agent");
        if (action == null
                || packageId == null
                || agent == null
                || !List.of("begin", "confirm", "reconcile").contains(action)) {
            Http.error(ex, 400, "action (begin, confirm, reconcile), packageId and agent are required");
            return;
        }
        try {
            Deposit result;
            switch (action) {
                case "begin" -> {
                    String orderId = AddonRoutes.string(body, "orderId");
                    String destination = AddonRoutes.string(body, "destination");
                    Deposit.Kind kind;
                    try {
                        kind = Deposit.Kind.valueOf(AddonRoutes.string(body, "kind"));
                    } catch (RuntimeException e) {
                        Http.error(ex, 400, "kind must be DELIVERY or TRANSFER");
                        return;
                    }
                    if (orderId == null || destination == null) {
                        Http.error(ex, 400, "orderId and destination are required");
                        return;
                    }
                    result = deposits.begin(packageId, orderId, kind, destination, agent);
                }
                case "confirm" -> {
                    var snapshot = body.has("snapshot") && body.get("snapshot").isJsonObject()
                            ? body.getAsJsonObject("snapshot")
                            : null;
                    result = deposits.confirm(packageId, agent, snapshot);
                }
                default -> result = deposits.reconcile(packageId, agent, AddonRoutes.string(body, "reason"));
            }
            audit.add("agent:" + agent, "Signed WeSort agent", "deposit." + action, packageId, AuthRoutes.address(ex));
            // Step 4 (W-026/W-059): a server-confirmed chest snapshot settles the linked order and its quota;
            // an uncertain deposit fences the order for reconciliation without consuming anything.
            try {
                if (result.state() == Deposit.State.DELIVERED) {
                    settleOrder(result.orderId(), OrderStage.DELIVERED, true, agent, AuthRoutes.address(ex));
                } else if (result.state() == Deposit.State.NEEDS_RECONCILIATION) {
                    settleOrder(result.orderId(), OrderStage.NEEDS_RECONCILIATION, false, agent, AuthRoutes.address(ex));
                }
            } catch (OrderStore.OrderException conflict) {
                Http.error(ex, conflict.status, conflict.getMessage());
                return;
            }
            Http.json(ex, 200, result.json());
        } catch (Deposits.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid deposit request");
        }
    }

    /**
     * Step 4 (W-026/W-059): a server-confirmed chest snapshot walks the linked order to DELIVERED and
     * consumes its quota; an uncertain deposit fences the order without touching the reservation (a
     * re-driven delivery still consumes exactly once, a terminal cancel releases it). Unknown legacy
     * orders only affect the deposit.
     */
    private void settleOrder(String orderId, OrderStage target, boolean consume, String agent, String address)
            throws IOException, OrderStore.OrderException {
        if (orderId == null) return;
        try {
            orders.advanceToward(orderId, target, "agent:" + agent);
            if (consume) {
                quotas.consume(orderId);
                audit.add("agent:" + agent, "Signed WeSort agent", "order.quota.consume", orderId, address);
            }
        } catch (OrderStore.OrderException unknown) {
            if (unknown.status != 404) throw unknown;
        }
    }
}

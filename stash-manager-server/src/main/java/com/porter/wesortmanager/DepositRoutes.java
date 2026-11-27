package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.data.Deposit;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** Owner deposit ledger and the signed agent begin/confirm/reconcile endpoint (W-038). */
final class DepositRoutes {
    private final Deposits deposits;
    private final AuditLog audit;

    DepositRoutes(Deposits deposits, AuditLog audit) {
        this.deposits = deposits;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/deposits", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/deposits/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
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
            // The audit entries stage into the same transaction as the deposit
            // write: a failed commit persists neither.
            var trail = audit.batch();
            trail.actor("agent:" + agent, "Signed WeSort agent", AuthRoutes.address(ex));
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
                    result = deposits.begin(packageId, orderId, kind, destination, agent, trail);
                }
                case "confirm" -> {
                    var snapshot = body.has("snapshot") && body.get("snapshot").isJsonObject()
                            ? body.getAsJsonObject("snapshot")
                            : null;
                    result = deposits.confirm(packageId, agent, snapshot, trail);
                }
                default -> result = deposits.reconcile(packageId, agent, AddonRoutes.string(body, "reason"), trail);
            }
            Http.json(ex, 200, result.json());
        } catch (Deposits.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid deposit request");
        }
    }
}

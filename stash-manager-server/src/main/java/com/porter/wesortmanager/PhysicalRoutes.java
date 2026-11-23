package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

final class PhysicalRoutes {
    private final IndexStore index;
    private final AuditLog audit;
    private final Config config;
    private PhysicalOperations operations;

    PhysicalRoutes(IndexStore index, AuditLog audit, Config config) {
        this.index = index;
        this.audit = audit;
        this.config = config;
    }

    private synchronized PhysicalOperations operations() throws IOException {
        if (operations == null) operations = new PhysicalOperations(index.stock());
        return operations;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/physical", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/physical/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        var response = new JsonObject();
        response.add("operations", operations().list());
        Http.json(ex, 200, response);
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null) return;
        try {
            var body = raw.getAsJsonObject();
            var agent = body.get("agent").getAsString();
            // Physical work stays fail-closed until the single server is configured,
            // and the record's server must be the configured one.
            String want = config.minecraftServer();
            if (want == null || want.isEmpty()) {
                Http.error(ex, 400, "set the Minecraft server in Settings first");
                return;
            }
            var record = body.getAsJsonObject("record");
            var context = record == null ? null : record.getAsJsonObject("context");
            String at = context == null ? null : AddonRoutes.string(context, "server");
            if (at != null && !at.isEmpty() && !want.equals(at)) {
                Http.error(ex, 409, "this Manager serves '" + want + "'");
                return;
            }
            var store = operations();
            JsonObject receipt;
            JsonObject decision;
            JsonObject result = body.has("result") ? body.getAsJsonObject("result") : null;
            // Serialized with the index lock: order reconciliation checks unsettled operations
            // and commits under the same lock, so a journal write cannot land in between.
            synchronized (index) {
                receipt = store.accept(agent, body.getAsJsonObject("record"));
                if (result != null) {
                    store.decisionResult(
                            agent,
                            result.get("id").getAsString(),
                            result.get("state").getAsString(),
                            result.get("detail").getAsString());
                }
                decision = store.decision(agent, receipt.get("id").getAsString());
                if (decision == null) decision = store.inspectAutomatically(agent, receipt);
            }
            var response = new JsonObject();
            response.add("receipt", receipt);
            if (decision != null) response.add("decision", decision);
            Http.json(ex, 200, response);
        } catch (PhysicalOperations.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid physical journal or receipt");
        }
    }
}

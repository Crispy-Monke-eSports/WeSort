package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

final class PhysicalRoutes {
    private final IndexStore index;
    private final AuditLog audit;
    private PhysicalOperations operations;

    PhysicalRoutes(IndexStore index, AuditLog audit) {
        this.index = index;
        this.audit = audit;
    }

    private synchronized PhysicalOperations operations() throws IOException {
        if (operations == null) operations = new PhysicalOperations(index.stock());
        return operations;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/physical", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/physical/inspect", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::inspect);
        router.exact(Router.POST, "/api/v1/physical/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) { Http.error(ex, 404, "not found"); return; }
        var response = new JsonObject();
        response.add("operations", operations().list());
        Http.json(ex, 200, response);
    }

    private void inspect(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) { Http.error(ex, 404, "not found"); return; }
        var raw = AddonRoutes.readJson(ex);
        if (raw == null) return;
        try {
            var body = raw.getAsJsonObject();
            var operation = body.get("operation").getAsString();
            var decision = operations().inspect(operation, body.get("revision").getAsBigDecimal().longValueExact(),
                body.get("checksum").getAsString(), Router.user(ex).id());
            audit.add(Router.user(ex), "physical.inspect", operation, AuthRoutes.address(ex));
            Http.json(ex, 200, decision);
        } catch (PhysicalOperations.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid physical inspection request");
        }
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null) return;
        try {
            var body = raw.getAsJsonObject();
            var agent = body.get("agent").getAsString();
            var store = operations();
            var receipt = store.accept(agent, body.getAsJsonObject("record"));
            if (body.has("result")) {
                var result = body.getAsJsonObject("result");
                store.decisionResult(agent, result.get("id").getAsString(), result.get("state").getAsString(),
                    result.get("detail").getAsString());
            }
            var decision = store.decision(agent, receipt.get("id").getAsString());
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

package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.porter.wesort.data.MinecraftServerAddress;
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
        router.exact(
                Router.POST, "/api/v1/physical/inspect", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::inspect);
        router.exact(
                Router.POST, "/api/v1/physical/lease", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::freeLease);
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
            if (at != null && !at.isEmpty() && !MinecraftServerAddress.sameServer(want, at)) {
                Http.error(ex, 409, "this Manager serves '" + want + "'");
                return;
            }
            // The record checksum covers its context. Keep the agent's record intact and
            // use the configured server only as the Manager's storage key.
            var store = operations();
            JsonObject receipt;
            JsonObject decision;
            JsonObject result = body.has("result") ? body.getAsJsonObject("result") : null;
            // Serialized with the index lock: order reconciliation checks unsettled operations
            // and commits under the same lock, so a journal write cannot land in between.
            synchronized (index) {
                receipt = store.accept(agent, body.getAsJsonObject("record"), want);
                if (result != null
                        && !store.decisionResult(
                                agent,
                                result.get("id").getAsString(),
                                result.get("state").getAsString(),
                                result.get("detail").getAsString())) {
                    Http.json(ex, 409, error("no pending inspection", "no_pending_inspection"));
                    return;
                }
                decision = store.decision(agent, receipt.get("id").getAsString());
                if (decision == null) decision = store.inspectAutomatically(agent, receipt);
            }
            var response = new JsonObject();
            response.add("receipt", receipt);
            if (decision != null) response.add("decision", decision);
            Http.json(ex, 200, response);
        } catch (PhysicalOperations.Conflict conflict) {
            Http.json(
                    ex,
                    409,
                    error(
                            conflict.getMessage(),
                            "lease held by other agent".equals(conflict.getMessage())
                                    ? AutomationGate.REASON_HELD_BY_OTHER
                                    : "conflict"));
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid physical journal or receipt");
        }
    }

    /** Owner re-issues a revision-pinned inspection without waiting for the agent poll loop. */
    private void inspect(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a physical inspection request");
            return;
        }
        var body = raw.getAsJsonObject();
        try {
            var decision = operations()
                    .inspect(
                            AddonRoutes.string(body, "id"),
                            body.get("revision").getAsLong(),
                            AddonRoutes.string(body, "checksum"),
                            Router.user(ex).id());
            audit.add(Router.user(ex), "physical.inspect", AddonRoutes.string(body, "id"), AuthRoutes.address(ex));
            Http.json(ex, 200, decision);
        } catch (PhysicalOperations.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "id, revision and checksum are required");
        }
    }

    /**
     * Owner frees an orphaned sorting fence when no journal can ever inspect it. Fails with
     * 409 while live journal rows exist — those go through inspection first.
     */
    private void freeLease(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a lease request");
            return;
        }
        String at = AddonRoutes.string(raw.getAsJsonObject(), "server");
        if (at == null || at.isBlank()) {
            Http.error(ex, 400, "server is required");
            return;
        }
        String configured = config.minecraftServer();
        if (configured != null && !configured.isEmpty()) {
            if (!MinecraftServerAddress.sameServer(configured, at)) {
                Http.error(ex, 409, "this Manager serves '" + configured + "'");
                return;
            }
            at = configured;
        }
        boolean freed = index.automation().freeLease(at);
        audit.add(Router.user(ex), "automation.free_lease", at, AuthRoutes.address(ex));
        if (!freed) {
            var lease = index.automation().current(at);
            String reason = lease != null && "NEEDS_RECONCILIATION".equals(lease.state())
                    ? AutomationGate.REASON_RECONCILIATION_REQUIRED
                    : AutomationGate.REASON_NO_LEASE;
            var out = error(
                    AutomationGate.REASON_RECONCILIATION_REQUIRED.equals(reason)
                            ? "Automation requires reconciliation — post the journal first"
                            : "No fenced sorting lease on this server",
                    reason);
            if (lease != null) out.addProperty("leaseState", lease.state());
            Http.json(ex, 409, out);
            return;
        }
        Http.json(ex, 200, Http.ok());
    }

    private static JsonObject error(String message, String reason) {
        var out = new JsonObject();
        out.addProperty("error", message);
        out.addProperty("reason", reason);
        return out;
    }
}

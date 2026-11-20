package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Package custody view (Owner) and signed agent custody transitions (W-029). */
final class CustodyRoutes {
    private final PackageCustody custody;
    private final AuditLog audit;

    CustodyRoutes(PackageCustody custody, AuditLog audit) {
        this.custody = custody;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/custody", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/custody/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        var out = new JsonObject();
        out.add("packages", custody.list(Http.query(ex).get("order")));
        Http.json(ex, 200, out);
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a custody transition");
            return;
        }
        var body = raw.getAsJsonObject();
        String packageId = AddonRoutes.string(body, "packageId");
        String orderId = AddonRoutes.string(body, "orderId");
        String agent = AddonRoutes.string(body, "agent");
        String action = AddonRoutes.string(body, "action");
        if (packageId == null || orderId == null || agent == null || action == null) {
            Http.error(ex, 400, "packageId, orderId, agent and action are required");
            return;
        }
        try {
            var location = body.has("location") && body.get("location").isJsonObject()
                    ? body.getAsJsonObject("location")
                    : null;
            var evidence = body.has("evidence") && body.get("evidence").isJsonObject()
                    ? body.getAsJsonObject("evidence")
                    : null;
            PackageCustody.Package result;
            if ("stage".equals(action)) {
                result = custody.stage(
                        packageId,
                        orderId,
                        AddonRoutes.string(body, "manifest"),
                        AddonRoutes.string(body, "jobId"),
                        AddonRoutes.string(body, "destination"),
                        AddonRoutes.string(body, "label"),
                        location);
            } else {
                result = custody.transition(
                        packageId, orderId, agent, PackageCustody.State.valueOf(action), location, evidence);
            }
            audit.add("agent:" + agent, "Signed WeSort agent", "custody." + action, packageId, AuthRoutes.address(ex));
            Http.json(ex, 200, json(result));
        } catch (PackageCustody.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid custody transition");
        }
    }

    private static JsonObject json(PackageCustody.Package p) {
        var out = new JsonObject();
        out.addProperty("packageId", p.packageId());
        out.addProperty("orderId", p.orderId());
        out.addProperty("state", p.state().name());
        out.addProperty("agent", p.agent());
        out.addProperty("revision", p.revision());
        return out;
    }
}

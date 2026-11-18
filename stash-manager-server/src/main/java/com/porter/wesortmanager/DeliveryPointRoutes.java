package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.porter.wesort.data.DeliveryPoint;

import java.io.IOException;

/** Owner management of delivery points and the member-facing selectable list (W-037). */
final class DeliveryPointRoutes {
    private final DeliveryPoints points;
    private final AuditLog audit;

    DeliveryPointRoutes(DeliveryPoints points, AuditLog audit) {
        this.points = points;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/delivery-points", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.GET, "/api/v1/delivery-points/selectable", Router.Guard.SESSION, Router.Perm.VIEW, this::selectable);
        router.exact(Router.POST, "/api/v1/delivery-points", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::change);
    }

    private void list(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) { Http.error(ex, 404, "not found"); return; }
        var out = new JsonObject();
        out.add("points", points.list());
        Http.json(ex, 200, out);
    }

    private void selectable(HttpExchange ex) throws IOException {
        var out = new JsonObject();
        out.add("points", points.selectable(Router.user(ex), Http.query(ex).get("server")));
        Http.json(ex, 200, out);
    }

    private void change(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) { Http.error(ex, 400, "Expected a delivery point change"); return; }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        if (action == null || !java.util.List.of("create", "update", "pause").contains(action)) {
            Http.error(ex, 400, "action must be create, update or pause"); return;
        }
        try {
            DeliveryPoint result;
            switch (action) {
                case "create" -> {
                    result = points.create(body, Router.user(ex).id());
                    audit.add(Router.user(ex), "delivery-point.create", result.id(), AuthRoutes.address(ex));
                }
                case "update" -> {
                    var id = AddonRoutes.string(body, "id");
                    var expected = body.get("expectedRevision");
                    if (id == null || !(expected != null && expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isNumber())) {
                        Http.error(ex, 400, "id and expectedRevision are required"); return;
                    }
                    result = points.update(id, body, expected.getAsBigDecimal().longValueExact(), Router.user(ex).id());
                    audit.add(Router.user(ex), "delivery-point.update", id, AuthRoutes.address(ex));
                }
                default -> {
                    var id = AddonRoutes.string(body, "id");
                    var expected = body.get("expectedRevision");
                    if (id == null || !(expected != null && expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isNumber())) {
                        Http.error(ex, 400, "id and expectedRevision are required"); return;
                    }
                    result = points.pause(id, expected.getAsBigDecimal().longValueExact(), Router.user(ex).id());
                    audit.add(Router.user(ex), "delivery-point.pause", id, AuthRoutes.address(ex));
                }
            }
            Http.json(ex, 200, result.json());
        } catch (DeliveryPoints.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }
}

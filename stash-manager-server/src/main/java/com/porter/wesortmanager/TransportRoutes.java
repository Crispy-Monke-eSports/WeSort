package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Owner control of the per-server opt-in {@code /tpa} and {@code /home} transport profile (W-062). */
final class TransportRoutes {
    private final TransportProfiles profiles;
    private final AuditLog audit;

    TransportRoutes(TransportProfiles profiles, AuditLog audit) {
        this.profiles = profiles;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/transport", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/transport", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::update);
    }

    private void list(HttpExchange ex) throws IOException {
        var out = new JsonObject();
        out.add("profiles", profiles.list());
        Http.json(ex, 200, out);
    }

    private void update(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a transport profile update");
            return;
        }
        var body = raw.getAsJsonObject();
        String server = AddonRoutes.string(body, "server");
        var expected = body.get("expectedRevision");
        if (server == null
                || !(expected != null
                        && expected.isJsonPrimitive()
                        && expected.getAsJsonPrimitive().isNumber())) {
            Http.error(ex, 400, "server and expectedRevision are required");
            return;
        }
        try {
            var profile =
                    profiles.update(server, body, expected.getAsBigDecimal().longValueExact());
            audit.add(Router.user(ex), "transport.update", server, AuthRoutes.address(ex));
            Http.json(ex, 200, profile.json());
        } catch (TransportProfiles.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }
}

package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Owner policy control and member usage view for the optional withdrawal quotas (W-059). */
final class QuotaRoutes {
    private final Quotas quotas;
    private final AuditLog audit;

    QuotaRoutes(Quotas quotas, AuditLog audit) {
        this.quotas = quotas;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/quotas", Router.Guard.SESSION, Router.Perm.OWNER, this::policy);
        router.exact(Router.POST, "/api/v1/quotas", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::update);
        router.exact(Router.GET, "/api/v1/quotas/me", Router.Guard.SESSION, Router.Perm.VIEW, this::me);
    }

    private void policy(HttpExchange ex) throws IOException {
        if (Redact.forced(Router.user(ex))) { Http.error(ex, 404, "not found"); return; }
        Http.json(ex, 200, quotas.policy());
    }

    private void update(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) { Http.error(ex, 400, "Expected a quota policy update"); return; }
        var body = raw.getAsJsonObject();
        var expected = body.get("expectedRevision");
        if (!(expected != null && expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isNumber())) {
            Http.error(ex, 400, "expectedRevision is required"); return;
        }
        try {
            var policy = quotas.update(body, expected.getAsBigDecimal().longValueExact());
            audit.add(Router.user(ex), "quotas.update", body.has("enabled") ? body.get("enabled").getAsString() : "rules", AuthRoutes.address(ex));
            Http.json(ex, 200, policy);
        } catch (Quotas.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }

    private void me(HttpExchange ex) throws IOException {
        Http.json(ex, 200, quotas.usageFor(Router.user(ex).id()));
    }
}

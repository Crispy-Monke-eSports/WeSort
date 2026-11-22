package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Owner-managed kit templates: what one ordered shulker box holds, slot for
 * slot. Every website user with stash view may read them (ordering needs the
 * template); only the owner changes them, revision-guarded like stash defs.
 */
final class KitTemplateRoutes {
    private final KitTemplates kits;
    private final AuditLog audit;

    KitTemplateRoutes(KitTemplates kits, AuditLog audit) {
        this.kits = kits;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/kits", Router.Guard.SESSION, Router.Perm.VIEW, this::list)
                .exact(Router.POST, "/api/v1/kits", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::change);
    }

    private void list(HttpExchange ex) throws IOException {
        var out = new JsonArray();
        for (var t : kits.all()) out.add(t.json());
        var o = Http.ok();
        o.add("kits", out);
        Http.json(ex, 200, o);
    }

    private void change(HttpExchange ex) throws IOException {
        var body = AuthRoutes.body(ex);
        if (body == null) return;
        String action = AddonRoutes.string(body, "action");
        if (action == null) {
            Http.error(ex, 400, "action must be create, update or delete");
            return;
        }
        try {
            switch (action) {
                case "create" -> {
                    var t = kits.create(body);
                    audit.add(Router.user(ex), "kit.create", t.name(), AuthRoutes.address(ex));
                    var o = Http.ok();
                    o.add("kit", t.json());
                    Http.json(ex, 200, o);
                }
                case "update" -> {
                    long rev = expected(body);
                    var t = kits.update(body, rev);
                    audit.add(Router.user(ex), "kit.update", t.name(), AuthRoutes.address(ex));
                    var o = Http.ok();
                    o.add("kit", t.json());
                    Http.json(ex, 200, o);
                }
                case "delete" -> {
                    String name = AddonRoutes.string(body, "name");
                    if (name == null || name.isEmpty()) {
                        Http.error(ex, 400, "name is required");
                        return;
                    }
                    kits.delete(name, expected(body));
                    audit.add(Router.user(ex), "kit.delete", name, AuthRoutes.address(ex));
                    Http.json(ex, 200, Http.ok());
                }
                default -> Http.error(ex, 400, "action must be create, update or delete");
            }
        } catch (KitTemplates.Conflict c) {
            Http.error(ex, 409, c.getMessage());
        } catch (IllegalArgumentException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        } catch (IOException e) {
            Http.error(ex, 500, "could not store the kit template");
        }
    }

    private static long expected(com.google.gson.JsonObject body) {
        var e = body.get("expectedRevision");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("expectedRevision is required");
        try {
            return e.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("expectedRevision must be a whole number");
        }
    }
}

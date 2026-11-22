package com.porter.wesortmanager;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Owner-managed stash definitions: each stash is created in the web UI with Position 1 and
 * Position 2 coordinates (the cube between them is the stash), plus drop-off chest,
 * delivery point, anvil and workbench placements. The drop-off chest is where members
 * leave items for sorting into the stash; it is selected here on stash level, never
 * in the game. The bot receives its directive in the heartbeat reply.
 */
final class StashDefRoutes {
    private final StashDefs defs;
    private final AuditLog audit;

    StashDefRoutes(StashDefs defs, AuditLog audit) {
        this.defs = defs;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/stash-defs", Router.Guard.SESSION, Router.Perm.VIEW, this::list);
        router.exact(Router.POST, "/api/v1/stash-defs", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::change);
    }

    private void list(HttpExchange ex) throws IOException {
        var who = Router.user(ex);
        boolean full = who.owner() || who.perms().positions();
        var out = new com.google.gson.JsonArray();
        for (var d : defs.all()) {
            if (!who.owner() && !who.perms().seesStash(d.name())) continue;
            out.add(full ? d.json() : d.redacted());
        }
        var o = Http.ok();
        o.add("defs", out);
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
                    var d = defs.create(body);
                    audit.add(Router.user(ex), "stash-def.create", d.name(), AuthRoutes.address(ex));
                    var o = Http.ok();
                    o.add("def", d.json());
                    Http.json(ex, 200, o);
                }
                case "update" -> {
                    long rev = expected(body);
                    var d = defs.update(body, rev);
                    audit.add(Router.user(ex), "stash-def.update", d.name(), AuthRoutes.address(ex));
                    var o = Http.ok();
                    o.add("def", d.json());
                    Http.json(ex, 200, o);
                }
                case "delete" -> {
                    String name = AddonRoutes.string(body, "name");
                    if (name == null || name.isEmpty()) {
                        Http.error(ex, 400, "name is required");
                        return;
                    }
                    defs.delete(name, expected(body));
                    audit.add(Router.user(ex), "stash-def.delete", name, AuthRoutes.address(ex));
                    Http.json(ex, 200, Http.ok());
                }
                default -> Http.error(ex, 400, "action must be create, update or delete");
            }
        } catch (StashDefs.Conflict c) {
            Http.error(ex, 409, c.getMessage());
        } catch (IllegalArgumentException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        } catch (IOException e) {
            Http.error(ex, 500, "could not save");
        }
    }

    private static long expected(JsonObject body) {
        var e = body.get("expectedRevision");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("expectedRevision is required");
        return e.getAsBigDecimal().longValueExact();
    }
}

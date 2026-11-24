package com.porter.wesortmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/**
 * Delivery storage contents (PROTOCOL.md, "Additions: delivery storage contents"):
 * <ul>
 *   <li>POST /api/v1/delivery: the bot's report (signed, the index's owner check, at most 256 KiB);</li>
 *   <li>GET /api/v1/delivery?stash=: the report for the Orders tab (view permission, stash scope).</li>
 * </ul>
 * Kept in memory only and never logged.
 */
final class DeliveryRoutes {
    private final DeliveryContents contents;
    private final Config config;
    private final StashDefs defs;

    DeliveryRoutes(DeliveryContents contents, Config config, StashDefs defs) {
        this.contents = contents;
        this.config = config;
        this.defs = defs;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/delivery", Router.Guard.ADDON_KEY, this::post)
                .exact(Router.GET, "/api/v1/delivery", Router.Guard.SESSION, Router.Perm.VIEW, this::get);
    }

    private void post(HttpExchange ex) throws IOException {
        Http.readBody(ex, DeliveryContents.MAX_BODY); // 413 above 256 KiB
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        DeliveryContents.Snapshot s;
        try {
            s = contents.parse(json.getAsJsonObject());
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        }
        String server = s.json().get("server").getAsString();
        String stash = s.json().get("stash").getAsString();
        // Fail closed: reports need a configured server, the right server and a defined stash.
        int gate = ServerRoutes.agentServerStatus(config, server);
        if (gate == 400) {
            Http.error(ex, 400, "set the Minecraft server in Settings first");
            return;
        }
        if (gate == 409) {
            Http.error(ex, 409, "this Manager serves '" + config.minecraftServer() + "'");
            return;
        }
        if (ServerRoutes.unknownStash(defs, stash)) {
            Http.error(ex, 404, "unknown stash '" + stash + "': define it in Settings first");
            return;
        }
        // Storage key, not the agent's spelling: the report must land in the same partition the
        // dashboard reads, regardless of port/alias variants the bot was configured with.
        server = config.minecraftServer();
        contents.put(server, stash, s);
        Http.json(ex, 200, Http.ok());
    }

    private void get(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String stash = q.get("stash");
        String server = OrderRoutes.scoped(config, ex, stash);
        if (server == null) return;
        DeliveryContents.Snapshot s = contents.get(server, stash);
        // null: the bot hasn't reported (an older bot, no delivery container, or not yet since the server started).
        boolean positions = Router.user(ex).perms().positions();
        Http.json(ex, 200, s == null || !positions ? JsonNull.INSTANCE : s.json());
    }
}

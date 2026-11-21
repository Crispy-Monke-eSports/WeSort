package com.porter.wesortmanager;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * The single Minecraft server this Manager serves. Set once by the owner in the web UI
 * (manual input). Bottles reconciling: heartbeats from any other server are refused.
 */
final class ServerRoutes {
    private final Config config;
    private final AuditLog audit;

    ServerRoutes(Config config, AuditLog audit) {
        this.config = config;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/server", Router.Guard.SESSION, Router.Perm.VIEW, this::get);
        router.exact(Router.POST, "/api/v1/server", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::set);
    }

    private void get(HttpExchange ex) throws IOException {
        var o = Http.ok();
        String s = config.minecraftServer();
        o.addProperty("server", s == null ? "" : s);
        o.addProperty("configured", s != null && !s.isEmpty());
        Http.json(ex, 200, o);
    }

    private void set(HttpExchange ex) throws IOException {
        var body = AuthRoutes.body(ex);
        if (body == null) return;
        String server = AddonRoutes.string(body, "server");
        if (server == null) server = "";
        server = server.trim();
        if (!server.isEmpty() && (server.length() > 256 || server.contains(" ") || server.contains("/"))) {
            Http.error(ex, 400, "server must be a plain host or IP without path");
            return;
        }
        try {
            config.setMinecraftServer(server);
        } catch (IllegalArgumentException e) {
            Http.error(ex, 400, e.getMessage());
            return;
        } catch (IOException e) {
            Http.error(ex, 500, "could not save");
            return;
        }
        audit.add(Router.user(ex), "server.set", server.isEmpty() ? "(cleared)" : server, AuthRoutes.address(ex));
        get(ex);
    }

    /** True when this bot request's server matches the configured single server (or none is set yet). */
    static boolean accepts(Config config, String server) {
        String want = config.minecraftServer();
        return want == null || want.isEmpty() || want.equals(server);
    }

    /**
     * Fail-closed gate for signed agent calls carrying a server string: 400 when no server is
     * configured yet or the caller sent none, 409 on mismatch, -1 when the call may proceed.
     * Physical work must never run before first setup or against the wrong Minecraft server.
     */
    static int agentServerStatus(Config config, String server) {
        String want = config == null ? null : config.minecraftServer();
        if (want == null || want.isEmpty() || server == null || server.isEmpty()) return 400;
        return want.equals(server) ? -1 : 409;
    }

    /** True when stash definitions exist and none of them has this name. */
    static boolean unknownStash(StashDefs defs, String stash) {
        return defs != null && stash != null && defs.get(stash) == null;
    }
}

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
        server = normalizeServer(server);
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
        return want == null || want.isEmpty() || sameServer(want, server);
    }

    /**
     * Fail-closed gate for signed agent calls carrying a server string: 400 when no server is
     * configured yet or the caller sent none, 409 on mismatch, -1 when the call may proceed.
     * Physical work must never run before first setup or against the wrong Minecraft server.
     */
    static int agentServerStatus(Config config, String server) {
        return serverStatus(config == null ? null : config.minecraftServer(), server);
    }

    static int serverStatus(String want, String server) {
        if (want == null || want.isEmpty() || server == null || server.isEmpty()) return 400;
        if (sameServer(want, server)) return -1;
        System.err.println("WeSort Manager: refusing agent server '" + normalizeServer(server) + "' (serves '"
                + normalizeServer(want) + "')");
        return 409;
    }

    /** Normalize the host spelling, retaining an explicit port as part of the server identity. */
    static String normalizeServer(String server) {
        if (server == null) return "";
        String address = server.trim().toLowerCase(java.util.Locale.ROOT);
        if (address.isEmpty()) return "";
        int colon = portSeparator(address);
        String host = colon < 0 ? address : address.substring(0, colon).trim();
        String port = colon < 0 ? "" : address.substring(colon);
        while (host.endsWith(".") && host.length() > 1) host = host.substring(0, host.length() - 1);
        return host.isEmpty() ? "" : host + port;
    }

    /** A port separator is unambiguous only for a DNS/IPv4 host or bracketed IPv6. */
    private static int portSeparator(String address) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            return end >= 0 && end + 1 < address.length() && address.charAt(end + 1) == ':' ? end + 1 : -1;
        }
        int colon = address.indexOf(':');
        return colon >= 0 && address.indexOf(':', colon + 1) < 0 ? colon : -1;
    }

    /** Compare aliases and the implicit default port, but never conflate distinct explicit ports. */
    static boolean sameServer(String want, String got) {
        String a = comparisonServer(want);
        String b = comparisonServer(got);
        return !a.isEmpty() && a.equals(b);
    }

    private static String comparisonServer(String server) {
        String address = canonicalServer(server);
        int colon = portSeparator(address);
        return colon >= 0 && address.substring(colon).equals(":25565") ? address.substring(0, colon) : address;
    }

    /** Normalize the configured spelling and the alt. SRV alias without losing its explicit port. */
    static String canonicalServer(String server) {
        String host = normalizeServer(server);
        if (host.startsWith("alt.")) host = host.substring(4);
        return host;
    }

    /** True when stash definitions exist and none of them has this name. */
    static boolean unknownStash(StashDefs defs, String stash) {
        return defs != null && stash != null && defs.get(stash) == null;
    }
}

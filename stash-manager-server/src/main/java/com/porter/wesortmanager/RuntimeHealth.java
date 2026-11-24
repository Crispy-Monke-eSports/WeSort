package com.porter.wesortmanager;

import java.util.concurrent.atomic.AtomicBoolean;

/** No paths, identities or secrets in health responses. */
final class RuntimeHealth {
    private final AtomicBoolean ready = new AtomicBoolean();

    RuntimeHealth(boolean ready) {
        this.ready.set(ready);
    }

    boolean ready() {
        return ready.get();
    }

    void ready(boolean value) {
        ready.set(value);
    }

    void register(Router router) {
        register(router, () -> "");
    }

    /**
     * Health with the configured Minecraft server host (public address, no secrets) so a
     * wrong-server 409 can be diagnosed without dashboard login. The supplier is read per
     * request because the owner can change the server at runtime.
     */
    void register(Router router, java.util.function.Supplier<String> server) {
        router.exact(
                        Router.GET,
                        "/health/live",
                        Router.Guard.OPEN,
                        ex -> Http.json(ex, 200, com.google.gson.JsonParser.parseString("{\"status\":\"live\"}")))
                .exact(Router.GET, "/health/ready", Router.Guard.OPEN, ex -> {
                    var out = Http.ok();
                    out.addProperty("status", ready() ? "ready" : "starting");
                    String host = ServerRoutes.normalizeServer(server.get());
                    out.addProperty("server", host);
                    out.addProperty("serverConfigured", !host.isEmpty());
                    Http.json(ex, ready() ? 200 : 503, out);
                });
    }
}

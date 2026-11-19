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
        router.exact(
                        Router.GET,
                        "/health/live",
                        Router.Guard.OPEN,
                        ex -> Http.json(ex, 200, com.google.gson.JsonParser.parseString("{\"status\":\"live\"}")))
                .exact(
                        Router.GET,
                        "/health/ready",
                        Router.Guard.OPEN,
                        ex -> Http.json(
                                ex,
                                ready() ? 200 : 503,
                                com.google.gson.JsonParser.parseString(
                                        ready() ? "{\"status\":\"ready\"}" : "{\"status\":\"starting\"}")));
    }
}

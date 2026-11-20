package com.porter.wesortmanager;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** Owner box-UUID registration jobs and the signed agent claim/advance endpoint (W-063). */
final class BoxRoutes {
    private final IndexStore index;
    private final AuditLog audit;
    private final OrderStore orders;
    private final Config config;
    private final StashDefs defs;
    private BoxJobs jobs;

    BoxRoutes(IndexStore index, AuditLog audit, OrderStore orders, Config config, StashDefs defs) {
        this.index = index;
        this.audit = audit;
        this.orders = orders;
        this.config = config;
        this.defs = defs;
    }

    private synchronized BoxJobs jobs() throws IOException {
        if (jobs == null) jobs = new BoxJobs(index.stock());
        return jobs;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/boxes/jobs", Router.Guard.SESSION, Router.Perm.OWNER, this::jobList);
        router.exact(Router.POST, "/api/v1/boxes/jobs", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::jobCreate);
        router.exact(Router.POST, "/api/v1/boxes/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void jobList(HttpExchange ex) throws IOException {
        var out = new JsonObject();
        out.add("jobs", jobs().list(Http.query(ex).getOrDefault("server", "")));
        Http.json(ex, 200, out);
    }

    private void jobCreate(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a box registration request");
            return;
        }
        try {
            var job = jobs().create(Router.user(ex).id(), raw.getAsJsonObject());
            audit.add(Router.user(ex), "box.job.create", job.get("id").getAsString(), AuthRoutes.address(ex));
            Http.json(ex, 200, job);
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a box job request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        if (action == null
                || !List.of("claim", "renew", "renamed", "complete", "uncertain")
                        .contains(action)) {
            Http.error(ex, 400, "Agent box action required");
            return;
        }
        try {
            String agentId = AddonRoutes.string(body, "agent");
            JsonObject job;
            if ("claim".equals(action)) {
                String server = AddonRoutes.string(body, "server");
                String stash = AddonRoutes.string(body, "stash");
                // A box job is physical work: fail closed before setup, on the wrong server,
                // and for stashes the Manager never defined.
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
                synchronized (index) {
                    job = jobs().claim(server, stash, agentId);
                }
            } else {
                job = jobs().advance(
                                AddonRoutes.string(body, "id"),
                                agentId,
                                AddonRoutes.string(body, "token"),
                                action,
                                AddonRoutes.string(body, "message"));
            }
            var out = new JsonObject();
            out.add("job", job == null ? JsonNull.INSTANCE : job);
            if (job != null && !"renew".equals(action))
                audit.add(
                        "agent:" + agentId,
                        "Signed WeSort agent",
                        "box.job." + action,
                        job.get("id").getAsString(),
                        AuthRoutes.address(ex));
            Http.json(ex, 200, out);
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }
}

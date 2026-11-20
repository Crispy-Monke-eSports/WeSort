package com.porter.wesortmanager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.wesort.data.Job;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;

/** Owner job-scheduler management and the signed agent claim/renew/complete/pause/resume endpoint (W-034). */
final class JobRoutes {
    private final JobScheduler scheduler;
    private final AuditLog audit;

    JobRoutes(JobScheduler scheduler, AuditLog audit) {
        this.scheduler = scheduler;
        this.audit = audit;
    }

    void register(Router router) {
        router.exact(Router.GET, "/api/v1/jobs", Router.Guard.SESSION, Router.Perm.OWNER, this::list);
        router.exact(Router.POST, "/api/v1/jobs", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::manage);
        router.exact(Router.POST, "/api/v1/jobs/agent", Router.Guard.ADDON_KEY, this::agent);
    }

    private void list(HttpExchange ex) throws IOException {
        var out = new JsonObject();
        out.add("jobs", scheduler.queue());
        Http.json(ex, 200, out);
    }

    private void manage(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a job management request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        if (action == null || !List.of("submit", "revoke", "reconcile").contains(action)) {
            Http.error(ex, 400, "action must be submit, revoke or reconcile");
            return;
        }
        try {
            JsonElement result;
            switch (action) {
                case "submit" -> {
                    Job.Category category;
                    try {
                        category = Job.Category.valueOf(AddonRoutes.string(body, "category"));
                    } catch (RuntimeException e) {
                        Http.error(ex, 400, "category is required");
                        return;
                    }
                    String chain = AddonRoutes.string(body, "chain");
                    if (chain == null) {
                        Http.error(ex, 400, "chain is required");
                        return;
                    }
                    result = scheduler
                            .submit(
                                    category,
                                    chain,
                                    AddonRoutes.string(body, "detail"),
                                    priority(body),
                                    Router.user(ex).id())
                            .json();
                }
                case "revoke" ->
                    result = scheduler
                            .revoke(requireId(body), Router.user(ex).id())
                            .json();
                default ->
                    result = scheduler
                            .reconcile(
                                    requireId(body),
                                    outcome(body),
                                    Router.user(ex).id())
                            .json();
            }
            audit.add(Router.user(ex), "job." + action, subject(result), AuthRoutes.address(ex));
            Http.json(ex, 200, result);
        } catch (JobScheduler.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, invalid.getMessage());
        }
    }

    private void agent(HttpExchange ex) throws IOException {
        var raw = AddonRoutes.readJson(ex);
        if (raw == null || !raw.isJsonObject()) {
            Http.error(ex, 400, "Expected a job request");
            return;
        }
        var body = raw.getAsJsonObject();
        String action = AddonRoutes.string(body, "action");
        String agent = AddonRoutes.string(body, "agent");
        if (action == null
                || agent == null
                || !List.of("claim", "renew", "complete", "pause", "resume").contains(action)) {
            Http.error(ex, 400, "action (claim, renew, complete, pause, resume) and agent are required");
            return;
        }
        try {
            JsonElement result;
            switch (action) {
                case "claim" -> result = scheduler.claim(agent);
                case "renew" ->
                    result = scheduler
                            .renew(requireId(body), agent, requireToken(body))
                            .json();
                case "complete" ->
                    result = scheduler
                            .complete(requireId(body), agent, requireToken(body))
                            .json();
                case "pause" -> result = scheduler.pause(requireId(body), agent).json();
                default ->
                    result = scheduler
                            .resume(requireId(body), agent, requireToken(body))
                            .json();
            }
            String id = action.equals("claim")
                            && result.isJsonObject()
                            && result.getAsJsonObject().get("job").isJsonObject()
                    ? result.getAsJsonObject().getAsJsonObject("job").get("id").getAsString()
                    : AddonRoutes.string(body, "id");
            audit.add(
                    "agent:" + agent,
                    "Signed WeSort agent",
                    "job." + action,
                    id == null ? "" : id,
                    AuthRoutes.address(ex));
            Http.json(ex, 200, result);
        } catch (JobScheduler.Conflict conflict) {
            Http.error(ex, 409, conflict.getMessage());
        } catch (RuntimeException invalid) {
            Http.error(ex, 400, "Invalid job request");
        }
    }

    private static String subject(JsonElement result) {
        return result.isJsonObject() && result.getAsJsonObject().has("id")
                ? result.getAsJsonObject().get("id").getAsString()
                : "";
    }

    private static String requireId(JsonObject body) {
        String id = AddonRoutes.string(body, "id");
        if (id == null) throw new IllegalArgumentException("id is required");
        return id;
    }

    private static String requireToken(JsonObject body) {
        String token = AddonRoutes.string(body, "token");
        if (token == null) throw new IllegalArgumentException("token is required");
        return token;
    }

    private static int priority(JsonObject body) {
        var p = body.get("priority");
        return p != null && p.isJsonPrimitive() && p.getAsJsonPrimitive().isNumber() ? p.getAsInt() : 0;
    }

    private static Job.State outcome(JsonObject body) {
        String outcome = AddonRoutes.string(body, "outcome");
        if ("reassign".equals(outcome)) return Job.State.QUEUED;
        if ("complete".equals(outcome)) return Job.State.COMPLETED;
        throw new IllegalArgumentException("outcome must be reassign or complete");
    }
}

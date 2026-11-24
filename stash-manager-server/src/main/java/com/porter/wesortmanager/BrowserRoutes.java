package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The website's API: stash list, index, and the "Link addon" panel. No login; the access check applies. */
final class BrowserRoutes {
    private final IndexStore store;
    private final Config config;
    private final LinkState link;
    private final List<String> urls;
    private final OrderStore orders;
    private final SessionStore sessions;
    private final AuditLog audit;
    private final DeliveryContents delivery;
    private final AgentCredentials credentials;

    BrowserRoutes(
            IndexStore store,
            Config config,
            LinkState link,
            List<String> urls,
            OrderStore orders,
            SessionStore sessions,
            AuditLog audit,
            DeliveryContents delivery,
            AgentCredentials credentials) {
        this.delivery = delivery;
        this.credentials = credentials;
        this.sessions = sessions;
        this.audit = audit;
        this.orders = orders;
        this.store = store;
        this.config = config;
        this.link = link;
        this.urls = List.copyOf(urls);
    }

    void register(Router router) {
        router.exact(
                        Router.GET,
                        "/api/v1/stashes",
                        Router.Guard.SESSION,
                        Router.Perm.VIEW,
                        ex -> Http.json(ex, 200, stashes(Router.user(ex))))
                .exact(Router.GET, "/api/v1/index", Router.Guard.SESSION, Router.Perm.VIEW, this::getIndex)
                .exact(Router.GET, "/api/v1/link", Router.Guard.SESSION, Router.Perm.LINK, this::getLink)
                .exact(
                        Router.POST,
                        "/api/v1/link/regenerate",
                        Router.Guard.BROWSER_WRITE,
                        Router.Perm.OWNER,
                        this::regenerate)
                .exact(Router.POST, "/api/v1/rescan", Router.Guard.BROWSER_WRITE, Router.Perm.RESCAN, this::rescan)
                .exact(Router.GET, "/api/v1/agents", Router.Guard.SESSION, Router.Perm.OWNER, this::listAgents)
                .exact(Router.POST, "/api/v1/agents", Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::issueAgent)
                .pattern(Router.POST, AGENT_REVOKE, Router.Guard.BROWSER_WRITE, Router.Perm.OWNER, this::revokeAgent);
    }

    private static final Pattern AGENT_REVOKE = Pattern.compile("^/api/v1/agents/([A-Za-z0-9_-]{1,64})/revoke$");

    /** GET /api/v1/agents (owner): key slots with recent display-only player activity. */
    private void listAgents(HttpExchange ex) throws IOException {
        JsonArray slots = new JsonArray();
        for (AgentCredentials.Slot slot : credentials.all()) {
            if (!slot.active()) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", slot.id());
            row.addProperty("label", slot.label());
            row.addProperty("active", slot.active());
            if (slot.binding() != null
                    && System.currentTimeMillis() - slot.binding().lastSeen() <= BotHub.ONLINE_MS) {
                JsonObject binding = new JsonObject();
                binding.addProperty("name", slot.binding().playerName());
                binding.addProperty("seen", slot.binding().lastSeen());
                row.add("bound", binding);
            }
            slots.add(row);
        }
        JsonObject out = Http.ok();
        out.add("slots", slots);
        Http.json(ex, 200, out);
    }

    /** POST /api/v1/agents {label?} (owner, elevated): issues an unbound key slot, secret shown once. */
    private void issueAgent(HttpExchange ex) throws IOException {
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        com.google.gson.JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String label = o == null ? null : AddonRoutes.string(o, "label");
        if (label == null || label.isBlank()) label = "bot";
        if (!AgentCredentials.validLabel(label)) {
            Http.error(ex, 400, "label must be 1-64 characters without controls");
            return;
        }
        String token;
        try {
            token = credentials.issue(label);
        } catch (IllegalArgumentException invalid) {
            Http.error(ex, 409, invalid.getMessage());
            return;
        } catch (IOException failed) {
            Http.error(ex, 500, "could not store the agent credential");
            return;
        }
        audit.add(Router.user(ex), "agent.credential.issue", label, AuthRoutes.address(ex));
        JsonObject out = Http.ok();
        out.addProperty("id", token.substring(0, token.indexOf('.')));
        out.addProperty("key", token);
        Http.json(ex, 200, out);
    }

    /** POST /api/v1/agents/{id}/revoke (owner, elevated): revokes the slot, releasing its player. */
    private void revokeAgent(HttpExchange ex) throws IOException {
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        String path = ex.getRequestURI().getRawPath();
        String id = path.substring("/api/v1/agents/".length(), path.length() - "/revoke".length());
        boolean revoked;
        try {
            revoked = credentials.revoke(id);
        } catch (IOException failed) {
            Http.error(ex, 500, "could not store the agent credential");
            return;
        }
        if (!revoked) {
            Http.error(ex, 404, "no active agent credential");
            return;
        }
        audit.add(Router.user(ex), "agent.credential.revoke", id, AuthRoutes.address(ex));
        Http.json(ex, 200, Http.ok());
    }

    /** POST /api/v1/rescan {stash}: asks the bot to re-read every container (PROTOCOL.md, "manual rescan"). */
    private void rescan(HttpExchange ex) throws IOException {
        com.google.gson.JsonElement json = AddonRoutes.readJson(ex);
        JsonObject o = json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        String stash = o == null ? null : AddonRoutes.string(o, "stash");
        String server = OrderRoutes.scoped(config, ex, stash);
        if (server == null) return;
        long id = store.requestRescan(server, stash);
        if (id < 0) {
            Http.error(ex, 404, "no such stash");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("rescanId", id);
        Http.json(ex, 200, out);
    }

    /** The stash list (those the user may see) plus owner, the last ownership conflict, and the order summary. */
    private JsonArray stashes(Users.Principal who) {
        JsonArray all = store.listJson();
        JsonArray list = new JsonArray();
        for (com.google.gson.JsonElement e : all) {
            JsonObject o = e.getAsJsonObject();
            String stash = o.get("stash").getAsString();
            if (!who.perms().seesStash(stash)) continue;
            String server = o.has("server") && o.get("server").isJsonPrimitive()
                    ? o.get("server").getAsString()
                    : null;
            list.add(o);
            if (server == null) {
                o.add(
                        "orders",
                        orders.summaryJson(
                                config.minecraftServer(), stash, who.perms().ordersAll() ? null : who.id()));
            } else {
                o.add("orders", orders.summaryJson(server, stash, who.perms().ordersAll() ? null : who.id()));
            }
            if (server == null) {
                o.add("deliveryContents", com.google.gson.JsonNull.INSTANCE);
            } else {
                // What is already in the delivery storage (the full report: GET /api/v1/delivery). Never in the index.
                DeliveryContents.Snapshot dc = delivery.get(server, stash);
                o.add("deliveryContents", dc == null ? com.google.gson.JsonNull.INSTANCE : dc.summary());
            }
            if (!who.perms().positions()) Redact.stashLocations(o);
        }
        return list;
    }

    private void getIndex(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String stash = q.get("stash");
        String server = OrderRoutes.scoped(config, ex, stash);
        if (server == null) return;
        IndexStore.IndexBytes index = store.index(server, stash);
        if (index == null) {
            Http.error(ex, 404, "no index for that stash");
            return;
        }
        if (!Router.user(ex).perms().positions()) {
            Http.json(ex, 200, Redact.index(index.bytes()));
            return;
        }
        // The gzip variant gets its own ETag ("<hash>-gz"), as HTTP requires for different bytes.
        boolean gzip = Http.acceptsGzip(ex);
        String plainTag = index.etag();
        String gzipTag = plainTag.substring(0, plainTag.length() - 1) + "-gz\"";
        ex.getResponseHeaders().set("ETag", gzip ? gzipTag : plainTag);
        ex.getResponseHeaders().set("Vary", "Accept-Encoding");
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null
                && (inm.contains(plainTag)
                        || inm.contains(gzipTag)
                        || inm.trim().equals("*"))) {
            Http.send(ex, 304, null, null, "private, no-cache");
            return;
        }
        Http.sendJson(ex, 200, index.bytes(), gzip ? index.gzip() : index.bytes(), "private, no-cache");
    }

    private void getLink(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("apiKeySet", true);
        boolean owner = Router.user(ex).owner();
        o.addProperty("canRotateKey", owner && !credentials.scopedOnly());
        o.addProperty("canProvisionAgents", owner);
        JsonArray list = new JsonArray();
        urls.forEach(list::add);
        o.add("urls", list);
        Users.Perms perms = Router.user(ex).perms();
        LinkState.Linked l = link.get();
        if (l != null && !perms.seesStash(l.stash())) l = null;
        if (l == null) {
            o.add("linked", null);
        } else {
            JsonObject lo = new JsonObject();
            lo.addProperty("player", l.player());
            lo.addProperty("server", l.server());
            lo.addProperty("stash", l.stash());
            lo.addProperty("lastSeen", l.lastSeen());
            o.add("linked", lo);
        }
        JsonArray bots = new JsonArray();
        for (LinkState.Linked b : link.bots()) {
            if (!perms.seesStash(b.stash())) continue;
            JsonObject bo = new JsonObject();
            bo.addProperty("player", b.player());
            bo.addProperty("server", b.server());
            bo.addProperty("stash", b.stash());
            bo.addProperty("lastSeen", b.lastSeen());
            bots.add(bo);
        }
        o.add("bots", bots);
        Http.json(ex, 200, o);
    }

    private void regenerate(HttpExchange ex) throws IOException {
        if (credentials.scopedOnly()) {
            Http.error(ex, 409, "Agent credentials are managed on the Manager host");
            return;
        }
        if (!sessions.elevated((String) Http.attr(ex, Router.SESSION_TOKEN))) {
            Http.error(ex, 403, "confirm password");
            return;
        }
        synchronized (config) {
            config.regenerateKey();
            link.reset(config.keyState().generation());
        }
        audit.add(Router.user(ex), "apikey.regenerate", null, AuthRoutes.address(ex));
        Http.json(ex, 200, Http.ok());
    }
}

package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The dashboard's bot side (DASHBOARD-DESIGN.md, "Protocol / Bot"): bot/hello, bot/state, bot/next (long-poll) and
 * bot/results, all using signed agent requests. Every field is checked for type and size.
 */
final class BotRoutes {
    static final Pattern BOT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** Only the warehouse agent's own settings scope. */
    static final Pattern SCOPE = Pattern.compile("^plugin$");
    /** Setting paths that are treated as secrets whatever the bot says (DASHBOARD-DESIGN.md, "Secrets are never sent"). */
    static final Pattern SECRET_PATH = Pattern.compile(
        "(?i)token|passw|pass|pwd|secret|api_?key|key|auth|bearer|jwt|session|cookie|credential|cred|private|licen[sc]e|dsn|salt|signature|webhook|topic");
    /** A URL with user info ("scheme://user:pass@host"): the value itself is a secret. */
    static final Pattern URL_USERINFO = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://[^/@\\s]+@");
    static final Set<String> TYPES = Set.of("group", "bool", "int", "long", "double", "string", "enum", "stringList");
    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_PATH = 200;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_VALUES = 5000;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_CAPS = 16;

    private final BotHub hub;
    private final AuditLog audit;
    private final OrderStore orders;
    private final Owners owners;

    BotRoutes(BotHub hub, AuditLog audit, OrderStore orders, Owners owners) {
        this.hub = hub;
        this.audit = audit;
        this.orders = orders;
        this.owners = owners;
    }

    void register(Router router) {
        router.exact(Router.POST, "/api/v1/bot/hello", Router.Guard.ADDON_KEY, this::hello)
            .exact(Router.POST, "/api/v1/bot/state", Router.Guard.ADDON_KEY, this::state)
            .exact(Router.GET, "/api/v1/bot/next", Router.Guard.ADDON_KEY, this::next)
            .exact(Router.POST, "/api/v1/bot/next", Router.Guard.ADDON_KEY, this::nextNow)
            .exact(Router.POST, "/api/v1/bot/results", Router.Guard.ADDON_KEY, this::results)
            .exact(Router.POST, "/api/v1/bot/manifest", Router.Guard.ADDON_KEY, this::manifest)
            .exact(Router.POST, "/api/v1/bot/manifest/ack", Router.Guard.ADDON_KEY, this::acknowledgeManifest)
            .exact(Router.POST, "/api/v1/bot/activity", Router.Guard.ADDON_KEY, this::activity);
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        JsonElement json = AddonRoutes.readJson(ex);
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return null;
        }
        return json.getAsJsonObject();
    }

    /** The bot named in the body, or null after answering (400 for a bad id, 409 for a bot that hasn't said hello). */
    private BotHub.Bot known(HttpExchange ex, String botId) throws IOException {
        if (botId == null || !BOT_ID.matcher(botId).matches()) {
            Http.error(ex, 400, "botId must be 1-64 letters, digits, - or _");
            return null;
        }
        BotHub.Bot b = hub.bot(botId);
        if (b == null) {
            Http.error(ex, 409, "say hello first");
            return null;
        }
        return b;
    }

    private void hello(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        String botId = AddonRoutes.string(o, "botId");
        if (botId == null || !BOT_ID.matcher(botId).matches()) {
            Http.error(ex, 400, "botId must be 1-64 letters, digits, - or _");
            return;
        }
        String player = AddonRoutes.string(o, "player");
        if (o.has("player") && !o.get("player").isJsonNull() && (player == null || player.length() > 64)) {
            Http.error(ex, 400, "player must be a string of at most 64 characters");
            return;
        }
        JsonObject plugin = smallObject(o, "plugin", "id", "version");
        JsonObject runtime = smallObject(o, "runtime", "version", "mc");
        if (plugin == null || runtime == null) {
            Http.error(ex, 400, "plugin must be {id, version} and runtime {version, mc} (strings)");
            return;
        }
        List<String> caps = new ArrayList<>();
        if (o.has("caps") && !o.get("caps").isJsonNull()) {
            if (!o.get("caps").isJsonArray() || o.getAsJsonArray("caps").size() > MAX_CAPS) {
                Http.error(ex, 400, "caps must be an array of strings");
                return;
            }
            for (JsonElement c : o.getAsJsonArray("caps")) {
                if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || c.getAsString().length() > 32) {
                    Http.error(ex, 400, "caps must be an array of strings");
                    return;
                }
                caps.add(c.getAsString());
            }
        }
        List<String> scopes = new ArrayList<>();
        if (o.has("scopes") && !o.get("scopes").isJsonNull()) {
            if (!o.get("scopes").isJsonArray() || o.getAsJsonArray("scopes").size() > 50) {
                Http.error(ex, 400, "scopes must be an array of at most 50 scope names");
                return;
            }
            for (JsonElement sc : o.getAsJsonArray("scopes")) {
                if (!sc.isJsonPrimitive() || !sc.getAsJsonPrimitive().isString() || !SCOPE.matcher(sc.getAsString()).matches()) {
                    Http.error(ex, 400, "scopes must be plugin");
                    return;
                }
                if (!scopes.contains(sc.getAsString())) scopes.add(sc.getAsString());
            }
        }
        String controlSession = AddonRoutes.string(o, "controlSession");
        if (o.has("controlSession") && (controlSession == null || !controlSession.matches("[0-9a-f]{64}"))) {
            Http.error(ex, 400, "Invalid control session");
            return;
        }
        BotHub.ManifestScope manifestScope = null;
        if (o.has("manifestScope")) {
            manifestScope = manifestScope(o.get("manifestScope"), player);
            if (controlSession == null || manifestScope == null || !botId.equals(manifestScope.botId())) {
                Http.error(ex, 400, "Invalid manifest scope");
                return;
            }
        }
        int status = hub.register(botId, player, plugin, runtime, caps, scopes, controlSession, manifestScope);
        if (status != 200) {
            Http.error(ex, status, status == 409 ? "Bot already has an active control session"
                : "Too many bots (limit " + BotHub.MAX_BOTS + ")");
            return;
        }
        JsonObject out = Http.ok();
        out.addProperty("protocol", controlSession == null ? 1 : 2);
        if (controlSession != null) {
            var bot = hub.bot(botId);
            synchronized (bot) {
                out.addProperty("controlEpoch", bot.controlEpoch);
            }
        }
        Http.json(ex, 200, out);
    }

    private static BotHub.ManifestScope manifestScope(JsonElement input, String player) {
        if (!input.isJsonObject() || player == null || !player.matches("[A-Za-z0-9_]{1,16}")) return null;
        var scope = input.getAsJsonObject();
        String server = AddonRoutes.string(scope, "server");
        String stash = AddonRoutes.string(scope, "stash");
        String dimension = AddonRoutes.string(scope, "dimension");
        String agent = AddonRoutes.string(scope, "agent");
        if (server == null || server.isBlank() || server.length() > 256 || stash == null || stash.isBlank() || stash.length() > 128
            || dimension == null || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            || agent == null || !agent.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return null;
        return new BotHub.ManifestScope(server, stash, dimension, agent, player);
    }

    private void manifest(HttpExchange ex) throws IOException {
        var request = body(ex);
        if (request == null) return;
        String id = AddonRoutes.string(request, "orderId");
        if (id == null || !id.matches("o-[0-9a-f]{12}")) {
            Http.error(ex, 400, "Invalid order ID");
            return;
        }
        var scope = authorizedManifestScope(ex, request);
        if (scope == null) return;
        var manifest = orders.manifest(id, scope);
        if (manifest == null) {
            Http.error(ex, 404, "No manifest in this scope");
            return;
        }
        var out = Http.ok();
        out.addProperty("orderId", id);
        out.add("manifest", manifest);
        Http.json(ex, 200, out);
    }

    private BotHub.ManifestScope authorizedManifestScope(HttpExchange ex, JsonObject request) throws IOException {
        var bot = known(ex, AddonRoutes.string(request, "botId"));
        if (bot == null) return null;
        var scope = currentManifestScope(bot, AddonRoutes.string(request, "controlSession"), AddonRoutes.string(request, "controlEpoch"));
        if (scope == null) Http.error(ex, 404, "No manifest in this scope");
        return scope;
    }

    private BotHub.ManifestScope currentManifestScope(BotHub.Bot bot, String session, String epoch) {
        var scope = hub.manifestScope(bot, session, epoch);
        return scope != null && scope.player().equalsIgnoreCase(owners.owner(scope.server(), scope.stash())) ? scope : null;
    }

    private void acknowledgeManifest(HttpExchange ex) throws IOException {
        var request = body(ex);
        if (request == null) return;
        String id = AddonRoutes.string(request, "orderId");
        String hash = AddonRoutes.string(request, "manifestHash");
        if (id == null || !id.matches("o-[0-9a-f]{12}") || hash == null || !hash.matches("[a-f0-9]{64}")) {
            Http.error(ex, 400, "Invalid manifest receipt");
            return;
        }
        var scope = authorizedManifestScope(ex, request);
        if (scope == null) return;
        try {
            var receipt = orders.acknowledgeManifest(id, scope, hash);
            if (receipt.changed()) audit.add(scope.agent(), scope.player(), "agent.manifest.confirmed", id, AuthRoutes.address(ex));
            var out = Http.ok();
            out.addProperty("orderId", id);
            out.addProperty("manifestHash", hash);
            out.addProperty("acknowledgedAt", receipt.acknowledgedAt());
            Http.json(ex, 200, out);
        } catch (OrderStore.OrderException denied) {
            Http.error(ex, denied.status, denied.getMessage());
        }
    }

    /** {a, b} with optional short string fields only; missing object = empty. Null when malformed. */
    private static JsonObject smallObject(JsonObject o, String field, String... keys) {
        JsonObject out = new JsonObject();
        if (!o.has(field) || o.get(field).isJsonNull()) return out;
        if (!o.get(field).isJsonObject()) return null;
        JsonObject in = o.getAsJsonObject(field);
        for (String k : keys) {
            if (!in.has(k) || in.get(k).isJsonNull()) continue;
            String v = AddonRoutes.string(in, k);
            if (v == null) return null;
            out.addProperty(k, Http.truncate(v, 64));
        }
        return out;
    }

    private void state(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        synchronized (b) {
            if (!controlSession(ex, b, o)) return;
        }
        String scope = AddonRoutes.string(o, "scope");
        String schemaHash = AddonRoutes.string(o, "schemaHash");
        String valuesHash = AddonRoutes.string(o, "valuesHash");
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            Http.error(ex, 400, "scope must be plugin");
            return;
        }
        if (schemaHash == null || schemaHash.isEmpty() || schemaHash.length() > 128 || valuesHash == null || valuesHash.isEmpty() || valuesHash.length() > 128) {
            Http.error(ex, 400, "schemaHash and valuesHash must be short strings");
            return;
        }
        if (!o.has("values") || !o.get("values").isJsonObject() || o.getAsJsonObject("values").size() > MAX_VALUES) {
            Http.error(ex, 400, "values must be an object");
            return;
        }
        JsonObject values = o.getAsJsonObject("values");
        for (String k : values.keySet()) {
            if (k.isEmpty() || k.length() > MAX_PATH) {
                Http.error(ex, 400, "values keys must be setting paths");
                return;
            }
        }
        JsonArray schema = null;
        Map<String, JsonObject> nodes = null;
        if (o.has("schema") && !o.get("schema").isJsonNull()) {
            if (!o.get("schema").isJsonArray()) {
                Http.error(ex, 400, "schema must be an array of nodes");
                return;
            }
            schema = o.getAsJsonArray("schema");
            nodes = new HashMap<>();
            String problem = walk(schema, nodes, 0);
            if (problem != null) {
                Http.error(ex, 400, "schema: " + problem);
                return;
            }
        }
        if (nodes == null) {
            BotHub.Scope stored = hub.scope(b, scope);
            if (stored != null) nodes = stored.nodes;
        }
        values = scrubSecrets(values, nodes);
        JsonArray need;
        synchronized (b) {
            if (!controlSession(ex, b, o)) return;
            need = hub.state(b, scope, schemaHash, valuesHash, schema, nodes, values);
        }
        JsonObject out = Http.ok();
        if (!need.isEmpty()) out.add("need", need);
        Http.json(ex, 200, out);
    }

    /**
     * The values without any secret: a buggy bot must not be able to make the server store or serve one. Drops values
     * of nodes marked secret and of every path that looks like a secret.
     */
    static JsonObject scrubSecrets(JsonObject values, Map<String, JsonObject> nodes) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            String path = e.getKey();
            JsonObject node = nodes == null ? null : nodes.get(path);
            boolean secret = SECRET_PATH.matcher(path).find()
                || (node != null && node.has("secret") && node.get("secret").isJsonPrimitive() && node.get("secret").getAsBoolean());
            JsonElement v = e.getValue();
            if (!secret && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && URL_USERINFO.matcher(v.getAsString()).find()) {
                secret = true;
                if (node != null) {
                    node.addProperty("secret", true);
                    node.addProperty("readOnly", true);
                    node.addProperty("reason", "secret");
                    node.addProperty("set", true);
                }
            }
            if (!secret) out.add(path, v);
        }
        return out;
    }

    /** Checks the schema tree and indexes its nodes by path. Returns a problem or null. */
    static String walk(JsonArray list, Map<String, JsonObject> nodes, int depth) {
        if (depth > MAX_DEPTH) return "nested too deep";
        for (JsonElement e : list) {
            if (!e.isJsonObject()) return "nodes must be objects";
            if (nodes.size() >= MAX_NODES) return "too many nodes";
            JsonObject n = e.getAsJsonObject();
            String path = AddonRoutes.string(n, "path");
            String type = AddonRoutes.string(n, "type");
            if (path == null || path.isEmpty() || path.length() > MAX_PATH) return "every node needs a path";
            if (type == null || !TYPES.contains(type)) return path + ": unknown type";
            for (String f : new String[]{"label", "help", "reason", "section"}) {
                if (n.has(f) && !n.get(f).isJsonNull() && (AddonRoutes.string(n, f) == null || AddonRoutes.string(n, f).length() > MAX_TEXT)) {
                    return path + ": " + f + " must be a short string";
                }
            }
            for (String f : new String[]{"min", "max"}) {
                if (n.has(f) && !(n.get(f).isJsonPrimitive() && n.get(f).getAsJsonPrimitive().isNumber())) return path + ": " + f + " must be a number";
            }
            for (String f : new String[]{"nullable", "readOnly", "secret", "set"}) {
                if (n.has(f) && !(n.get(f).isJsonPrimitive() && n.get(f).getAsJsonPrimitive().isBoolean())) return path + ": " + f + " must be true or false";
            }
            if (n.has("options")) {
                if (!n.get("options").isJsonArray() || n.getAsJsonArray("options").size() > 500) return path + ": options must be a list";
                for (JsonElement opt : n.getAsJsonArray("options")) {
                    if (!opt.isJsonPrimitive() || !opt.getAsJsonPrimitive().isString()) return path + ": options must be strings";
                }
            }
            if (!type.equals("group") && SECRET_PATH.matcher(path).find()
                && !(n.has("secret") && n.get("secret").isJsonPrimitive() && n.get("secret").getAsBoolean())) {
                // Looks like a secret but the bot didn't say so: shown as one, never editable.
                n.addProperty("secret", true);
                n.addProperty("readOnly", true);
                n.addProperty("reason", "secret");
                if (!n.has("set")) n.addProperty("set", false);
            }
            nodes.put(path, n);
            if (type.equals("group")) {
                if (!n.has("children") || !n.get("children").isJsonArray()) return path + ": a group needs children";
                String problem = walk(n.getAsJsonArray("children"), nodes, depth + 1);
                if (problem != null) return problem;
            }
        }
        return null;
    }

    private void next(HttpExchange ex) throws IOException {
        next(ex, Http.query(ex).get("botId"), null, null, BotHub.POLL_WAIT_MS, null);
    }

    private void nextNow(HttpExchange ex) throws IOException {
        var request = body(ex);
        if (request != null) next(ex, AddonRoutes.string(request, "botId"), AddonRoutes.string(request, "controlSession"),
            AddonRoutes.string(request, "controlEpoch"), 0, request.get("activity"));
    }

    private void next(HttpExchange ex, String botId, String controlSession, String epoch, long waitMillis, JsonElement activity) throws IOException {
        BotHub.Bot b = known(ex, botId);
        if (b == null) return;
        if (activity != null) {
            var snapshot = Activity.clean(activity, hub.now());
            if (snapshot == null) { Http.error(ex, 400, "activity must be an object"); return; }
            synchronized (b) {
                if (hub.manifestScope(b, controlSession, epoch) == null) {
                    Http.error(ex, 409, "Control session does not match"); return;
                }
                hub.activity(b, snapshot, new JsonArray());
            }
        }
        BotHub.Poll p;
        try {
            p = hub.next(b, waitMillis, controlSession);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.send(ex, 204, null, null, "no-store");
            return;
        }
        if (p.status() == 429) {
            ex.getResponseHeaders().set("Retry-After", "3");
            Http.error(ex, 429, "too many bots waiting; poll every 3 s");
        } else if (p.status() == 409) {
            Http.error(ex, 409, "Control session does not match");
        } else {
            var scope = currentManifestScope(b, controlSession, epoch);
            var hint = scope == null ? null : orders.pendingManifest(scope);
            var reply = p.body();
            if (hint != null) {
                if (reply == null) {
                    reply = Http.ok();
                    reply.add("jobs", new JsonArray());
                }
                reply.add("pendingManifest", hint);
            }
            if (reply == null) Http.send(ex, 204, null, null, "no-store");
            else Http.json(ex, 200, reply);
        }
    }

    private void results(HttpExchange ex) throws IOException {
        JsonObject o = body(ex);
        if (o == null) return;
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        if (!o.has("results") || !o.get("results").isJsonArray() || o.getAsJsonArray("results").size() > MAX_RESULTS) {
            Http.error(ex, 400, "results must be an array (at most " + MAX_RESULTS + ")");
            return;
        }
        String session = AddonRoutes.string(o, "controlSession");
        synchronized (b) {
            if (!controlSession(ex, b, o)) return;
        }
        var results = o.getAsJsonArray("results");
        if (session != null) {
            if (results.size() != 1 || !results.get(0).isJsonObject()) {
                Http.error(ex, 400, "Expected one leased job result");
                return;
            }
            var receipt = results.get(0).getAsJsonObject();
            var ack = hub.acknowledge(b, session, receipt);
            if (ack == BotHub.Acknowledgement.REFUSED) {
                Http.error(ex, 409, "Expired, conflicting or invalid job receipt");
                return;
            }
            if (ack == BotHub.Acknowledgement.APPLIED) {
                boolean applied = receipt.get("ok").getAsBoolean();
                audit.add(b.botId, b.botId, applied ? "agent.job.applied" : "agent.job.rejected",
                    receipt.get("id").getAsString(), AuthRoutes.address(ex));
            }
            Http.json(ex, 200, Http.ok());
            return;
        }
        for (JsonElement e : results) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            String id = AddonRoutes.string(r, "id");
            if (id == null || id.length() > 64) continue;
            boolean ok = bool(r, "ok");
            boolean more = bool(r, "more");
            String error = AddonRoutes.string(r, "error");
            JsonElement value = r.get("value");
            hub.result(b, id, ok, more, value, error == null ? null : Http.truncate(error, 1000));
        }
        Http.json(ex, 200, Http.ok());
    }

    private static boolean controlSession(HttpExchange ex, BotHub.Bot bot, JsonObject request) throws IOException {
        if (java.util.Objects.equals(bot.controlSession, AddonRoutes.string(request, "controlSession"))) return true;
        Http.error(ex, 409, "Control session does not match");
        return false;
    }

    /** bot/activity {botId, activity}: the latest snapshot of what the runtime is doing (at most 256 KiB). */
    private void activity(HttpExchange ex) throws IOException {
        byte[] bytes = Http.readBody(ex, 256 * 1024); // 413 above that
        JsonElement json;
        try {
            json = com.google.gson.JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null || !json.isJsonObject()) {
            Http.error(ex, 400, "body must be a JSON object");
            return;
        }
        JsonObject o = json.getAsJsonObject();
        BotHub.Bot b = known(ex, AddonRoutes.string(o, "botId"));
        if (b == null) return;
        long now = System.currentTimeMillis();
        JsonObject snapshot = Activity.clean(o.get("activity"), now);
        if (snapshot == null) {
            Http.error(ex, 400, "activity must be an object");
            return;
        }
        synchronized (b) {
            if (!controlSession(ex, b, o)) return;
            hub.activity(b, snapshot, Activity.events(o.get("activity"), now));
        }
        Http.json(ex, 200, Http.ok());
    }

    private static boolean bool(JsonObject o, String f) {
        JsonElement e = o.get(f);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

}

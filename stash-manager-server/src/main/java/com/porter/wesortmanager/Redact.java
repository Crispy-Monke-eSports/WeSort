package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Force streamer mode" (PROTOCOL.md, "Additions: website users", the redaction table): what a sub-user whose perms
 * have streamer=true never receives. Applied by every route that returns positions, player names, server addresses or
 * free text from the bots, on the server: the page's blur is only a second layer. The owner is never forced.
 *
 * <p>Every method works on a JSON tree that belongs to this response (built fresh per request) or returns a new one;
 * shared state is never changed.
 */
final class Redact {
    static final String HIDDEN = "(hidden)";
    static final String ANOTHER_USER = "another user";
    /** Activity sources shown as they are (the plugin's own subsystems); any other source reads "(hidden)". */
    static final java.util.Set<String> SOURCES = java.util.Set.of("Stash sorter", "Stash order", "Pathfinder", "Pearl loader",
        "Queue", "Stash manager", "Dashboard", "Auto eat", "Auto totem", "Delivery");
    /** The viewer offset's range per axis, in chunks (uniform in -2^20..2^20). */

    private Redact() {
    }

    /** Whether this request's user is forced into streamer mode. */
    static boolean on(HttpExchange ex) {
        return forced(Router.user(ex));
    }

    static boolean forced(Users.Principal p) {
        return p != null && !p.owner() && p.perms().streamer();
    }

    /** Free text from a bot (it can hold positions or names): always "(hidden)" when present. */
    private static void scrubField(JsonObject o, String key) {
        if (o == null) return;
        JsonElement e = o.get(key);
        if (e != null && !e.isJsonNull()) o.addProperty(key, HIDDEN);
    }

    /** "minecraft:red_shulker_box" to "Red Shulker Box": a name that can't hold anything a player typed. */
    static String prettyId(String id) {
        if (id == null) return null;
        String path = id.substring(id.indexOf(':') + 1).split("#", 2)[0];
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("[_/.]+")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    // ---------- server labels ----------

    /** "Server 1", "Server 2", ... by sorted server address (of the stashes given: the visible ones). */
    static Map<String, String> serverLabels(Iterable<JsonElement> stashes) {
        TreeMap<String, String> labels = new TreeMap<>();
        for (JsonElement e : stashes) labels.put(e.getAsJsonObject().get("server").getAsString(), "");
        int i = 1;
        for (Map.Entry<String, String> en : labels.entrySet()) en.setValue("Server " + i++);
        return labels;
    }

    // ---------- GET /api/v1/stashes ----------

    /** One stash entry: no delivery position, no player or owner names, no position-like bot text. */
    static void stash(JsonObject o) {
        o.add("delivery", JsonNull.INSTANCE);
        o.add("player", JsonNull.INSTANCE);
        o.add("owner", JsonNull.INSTANCE);
        JsonObject c = obj(o, "conflict");
        if (c != null) c.add("player", JsonNull.INSTANCE);
        scrubField(o, "orderBlocker");
        scrubField(o, "rescanBlocked");
        scrubField(obj(o, "deliveryStorage"), "blocked");
        JsonObject orders = obj(o, "orders");
        if (orders != null) scrubField(obj(orders, "active"), "phase");
    }

    // ---------- GET /api/v1/index ----------

    /**
     * The index without the bot's player name and dimension, and without names players gave items: an item shows its
     * English name, details (lore, custom text) are dropped, a shulker shows its item's name.
     */
    static JsonObject index(byte[] json) {
        JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        o.remove("player");
        o.remove("dimension");
        o.remove("physicalSources");
        o.remove("slotVariants");
        JsonElement items = o.get("items");
        if (items != null && items.isJsonArray()) {
            for (JsonElement e : items.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject it = e.getAsJsonObject();
                String english = text(it, "englishName");
                it.addProperty("name", english != null ? english : prettyId(text(it, "id")));
                if (it.has("details")) it.add("details", new JsonArray());
            }
        }
        JsonElement shulkers = o.get("shulkers");
        if (shulkers != null && shulkers.isJsonArray()) {
            for (JsonElement e : shulkers.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject sh = e.getAsJsonObject();
                String item = text(sh, "itemKey");
                sh.addProperty("name", prettyId(item != null ? item : text(sh, "id")));
            }
        }
        return o;
    }

    // ---------- orders ----------

    /** An order: other users' names and shulker names hidden; the bot's phase and message text hidden. */
    static void order(JsonObject o, String selfId) {
        o.remove("sourceGuards");
        JsonObject by = obj(o, "placedBy");
        boolean mine = by != null && by.has("id") && !by.get("id").isJsonNull() && by.get("id").getAsString().equals(selfId);
        if (by != null && !mine) by.addProperty("name", ANOTHER_USER);
        if (!mine && o.has("names")) o.add("names", JsonNull.INSTANCE);
        scrubField(o, "phase");
        scrubField(o, "message");
    }

    /** GET /api/v1/orders: every order. */
    static void orders(JsonObject list, String selfId) {
        JsonElement e = list.get("orders");
        if (e == null || !e.isJsonArray()) return;
        for (JsonElement o : e.getAsJsonArray()) if (o.isJsonObject()) order(o.getAsJsonObject(), selfId);
    }

    // ---------- bots ----------

    /** A bot in the list: no player, no proxy details (server address, version), no "now doing" text. */
    static void bot(JsonObject o) {
        o.add("player", JsonNull.INSTANCE);
        o.add("runtime", new JsonObject());
        o.add("summary", JsonNull.INSTANCE);
        // Plugin details and the bot's settings scopes (plugin ids) are of no use to a forced user.
        o.add("plugin", new JsonObject());
        o.add("scopes", new com.google.gson.JsonArray());
    }

    /**
     * GET /api/v1/bots/{id}/activity: only structured states, kinds, levels and numbers. No positions, server,
     * dimension or player names; every free-text title, detail, status and event line is dropped.
     */
    static JsonObject activity(JsonObject in) {
        JsonObject out = new JsonObject();
        out.add("receivedAt", in.get("receivedAt"));
        out.add("online", in.get("online"));
        JsonElement ae = in.get("activity");
        if (ae == null || !ae.isJsonObject()) {
            out.add("activity", JsonNull.INSTANCE);
        } else {
            JsonObject a = ae.getAsJsonObject();
            JsonObject ra = new JsonObject();
            ra.add("at", a.get("at"));
            JsonObject c = obj(a, "connection");
            if (c != null) {
                JsonObject rc = new JsonObject();
                for (String f : new String[]{"state", "queuePosition", "health", "food", "ping", "uptimeSeconds", "spectators"}) {
                    if (c.has(f)) rc.add(f, c.get(f));
                }
                ra.add("connection", rc);
            }
            JsonArray current = new JsonArray();
            JsonElement ce = a.get("current");
            if (ce != null && ce.isJsonArray()) {
                for (JsonElement e : ce.getAsJsonArray()) {
                    JsonObject ci = e.getAsJsonObject();
                    JsonObject rc = new JsonObject();
                    for (String f : new String[]{"kind", "progress", "since", "level"}) if (ci.has(f)) rc.add(f, ci.get(f));
                    rc.addProperty("source", source(ci));
                    rc.addProperty("title", HIDDEN);
                    current.add(rc);
                }
            }
            ra.add("current", current);
            // Module and plugin names and versions: dropped (they say what the proxy runs).
            ra.add("modules", new JsonArray());
            ra.add("plugins", new JsonArray());
            out.add("activity", ra);
        }
        JsonArray events = new JsonArray();
        JsonElement ee = in.get("events");
        if (ee != null && ee.isJsonArray()) {
            for (JsonElement e : ee.getAsJsonArray()) {
                JsonObject v = e.getAsJsonObject();
                JsonObject rv = new JsonObject();
                rv.add("at", v.get("at"));
                rv.addProperty("source", source(v));
                rv.add("level", v.get("level"));
                rv.addProperty("text", HIDDEN);
                events.add(rv);
            }
        }
        out.add("events", events);
        return out;
    }

    /** A known activity source as it is, anything else "(hidden)". */
    private static String source(JsonObject o) {
        String s = text(o, "source");
        return s != null && SOURCES.contains(s) ? s : HIDDEN;
    }

    // ---------- link ----------

    /** GET /api/v1/link: no LAN addresses, no player names. */
    static void link(JsonObject o) {
        o.add("urls", new JsonArray());
        JsonObject l = obj(o, "linked");
        if (l != null) l.add("player", JsonNull.INSTANCE);
        JsonElement b = o.get("bots");
        if (b != null && b.isJsonArray()) for (JsonElement e : b.getAsJsonArray()) e.getAsJsonObject().add("player", JsonNull.INSTANCE);
    }

    // ---------- delivery contents ----------

    /** GET /api/v1/delivery: a copy without the bot's player name, and items named after their id (not as players named them). */
    static JsonElement delivery(JsonObject stored) {
        JsonObject o = stored.deepCopy();
        o.add("player", JsonNull.INSTANCE);
        JsonElement chests = o.get("chests");
        if (chests != null && chests.isJsonArray()) {
            for (JsonElement c : chests.getAsJsonArray()) {
                JsonElement items = c.getAsJsonObject().get("items");
                if (items == null || !items.isJsonArray()) continue;
                for (JsonElement it : items.getAsJsonArray()) {
                    JsonObject io = it.getAsJsonObject();
                    io.addProperty("name", prettyId(text(io, "id")));
                }
            }
        }
        return o;
    }

    // ---------- the live viewer ----------

    /**
     * Per-stream coordinate rebasing: every x/z the stream sends is shifted by the same secret amount, a whole number of
     * chunks (so tiles stay aligned): origin = the bot's chunk when the stream first sees it, plus a random shift of
     * up to 2^20 chunks per axis. y is kept. The true position can't be worked out from what the stream shows.
     */

}

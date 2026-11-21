package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/**
 * Position redaction for users without the positions permission.
 *
 * <p>Every method works on a JSON tree that belongs to this response (built fresh per request) or returns a new one;
 * shared state is never changed.
 */
final class Redact {
    /** Activity sources shown as they are; any other source reads "(hidden)". */
    static final java.util.Set<String> SOURCES = java.util.Set.of(
            "Stash sorter",
            "Stash order",
            "Pathfinder",
            "Pearl loader",
            "Queue",
            "Stash manager",
            "Dashboard",
            "Auto eat",
            "Auto totem",
            "Delivery");

    private Redact() {}

    /** Free text from a bot (it can hold positions): always "(hidden)" when present. */
    private static void scrubField(JsonObject o, String key) {
        if (o == null) return;
        JsonElement e = o.get(key);
        if (e != null && !e.isJsonNull()) o.addProperty(key, "(hidden)");
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

    // ---------- GET /api/v1/stashes ----------

    static void stashLocations(JsonObject o) {
        o.addProperty("deliveryConfigured", obj(o, "delivery") != null);
        o.add("delivery", JsonNull.INSTANCE);
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
        JsonObject o =
                JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
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

    static void orderLocations(JsonObject o) {
        o.remove("sourceGuards");
        o.remove("allocations");
        o.remove("agent");
        o.remove("protectedRoots");
        scrubField(o, "phase");
        scrubField(o, "message");
    }

    // ---------- bots ----------

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
                for (String f :
                        new String[] {"state", "queuePosition", "health", "food", "ping", "uptimeSeconds", "spectators"
                        }) {
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
                    for (String f : new String[] {"kind", "progress", "since", "level"})
                        if (ci.has(f)) rc.add(f, ci.get(f));
                    rc.addProperty("source", source(ci));
                    rc.addProperty("title", "(hidden)");
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
                rv.addProperty("text", "(hidden)");
                events.add(rv);
            }
        }
        out.add("events", events);
        return out;
    }

    /** A known activity source as it is, anything else "(hidden)". */
    private static String source(JsonObject o) {
        String s = text(o, "source");
        return s != null && SOURCES.contains(s) ? s : "(hidden)";
    }
}

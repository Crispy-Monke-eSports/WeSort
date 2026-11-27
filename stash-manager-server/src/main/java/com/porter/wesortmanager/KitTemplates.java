package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Owner-managed kit templates: what one shulker box holds, slot for slot.
 * One kit is exactly one box — ordering N kits reserves the components for N
 * boxes and the bot packs each box on demand with this exact placement.
 * Templates are definitions and never stock; finished boxes are never stored.
 *
 * <p>Stored as JSON in the data directory ({@code kit-templates.json}), with
 * revision-guarded writes like the stash definitions: an order pins the
 * template snapshot at submit, so later edits never change a placed order.
 */
final class KitTemplates {
    static final class Conflict extends IOException {
        Conflict() {
            super("Kit template changed; reload before updating");
        }
    }

    private static final int SCHEMA = 1;
    /** One box holds 27 slots. */
    static final int SLOTS = 27;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_KEY = 256;
    /** A slot holds at most one stack; the bot verifies the real max stack at pack time. */
    private static final int MAX_COUNT = 64;

    /** One filled slot: 0-based box slot, the exact observed item key, and how many. */
    record Slot(int slot, String key, long count) {
        JsonObject json() {
            var o = new JsonObject();
            o.addProperty("slot", slot);
            o.addProperty("key", key);
            o.addProperty("count", count);
            return o;
        }
    }

    record Template(String name, long revision, long updatedAt, List<Slot> slots) {
        JsonObject json() {
            var o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("revision", revision);
            o.addProperty("updatedAt", updatedAt);
            var arr = new JsonArray();
            for (var s : slots) arr.add(s.json());
            o.add("slots", arr);
            return o;
        }

        /** Component need per ordered kit: item key to total count in one box. */
        Map<String, Long> components() {
            var out = new LinkedHashMap<String, Long>();
            for (var s : slots) out.merge(s.key(), s.count(), Long::sum);
            return out;
        }
    }

    private final Path file;
    private final Clock clock;
    private final Map<String, Template> templates = new LinkedHashMap<>();

    KitTemplates(Path dataDir) throws IOException {
        this(dataDir, Clock.system());
    }

    KitTemplates(Path dataDir, Clock clock) throws IOException {
        this.clock = clock;
        this.file = dataDir.resolve("kit-templates.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid kit templates location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var e : root.getAsJsonArray("templates")) {
                var t = read(e.getAsJsonObject());
                templates.put(t.name(), t);
            }
        } catch (RuntimeException ex) {
            throw new IOException("Cannot read kit templates", ex);
        }
    }

    synchronized JsonArray list() {
        var out = new JsonArray();
        for (var t : templates.values()) out.add(t.json());
        return out;
    }

    synchronized List<Template> all() {
        return List.copyOf(templates.values());
    }

    synchronized Template get(String name) {
        return name == null ? null : templates.get(name);
    }

    synchronized Template create(JsonObject req) throws IOException {
        var t = parse(req, 0);
        if (templates.containsKey(t.name())) throw new IllegalArgumentException("kit '" + t.name() + "' exists");
        templates.put(t.name(), t);
        save();
        return t;
    }

    synchronized Template update(JsonObject req, long expectedRevision) throws IOException {
        var t = parse(req, expectedRevision);
        var before = templates.get(t.name());
        if (before == null) throw new IllegalArgumentException("no such kit '" + t.name() + "'");
        if (before.revision() != expectedRevision) throw new Conflict();
        templates.put(t.name(), t);
        save();
        return t;
    }

    synchronized void delete(String name, long expectedRevision) throws IOException {
        var before = templates.get(name);
        if (before == null) throw new IllegalArgumentException("no such kit '" + name + "'");
        if (before.revision() != expectedRevision) throw new Conflict();
        templates.remove(name);
        save();
    }

    private Template parse(JsonObject req, long revision) {
        var name = str(req, "name");
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("name must be 1-64 chars: letters, digits, _ . -");
        if (!req.has("slots") || !req.get("slots").isJsonArray())
            throw new IllegalArgumentException("slots must be an array of {slot, key, count}");
        var seen = new boolean[SLOTS];
        var slots = new ArrayList<Slot>();
        for (var e : req.getAsJsonArray("slots")) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("every slot must be {slot, key, count}");
            var s = e.getAsJsonObject();
            var slot = whole(s.get("slot"));
            var key = str(s, "key");
            var count = whole(s.get("count"));
            if (slot == null || slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("slot must be 0-26");
            if (seen[slot]) throw new IllegalArgumentException("slot " + slot + " twice");
            if (key == null || key.isEmpty() || key.length() > MAX_KEY)
                throw new IllegalArgumentException("key must be a non-empty string");
            if (count == null || count < 1 || count > MAX_COUNT)
                throw new IllegalArgumentException("count must be 1-64");
            seen[slot] = true;
            slots.add(new Slot(slot, key, count));
        }
        if (slots.isEmpty()) throw new IllegalArgumentException("a kit needs at least one filled slot");
        slots.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        long rev = revision > 0 ? revision + 1 : 1;
        return new Template(name, rev, clock.millis(), List.copyOf(slots));
    }

    private static Template read(JsonObject o) {
        var slots = new ArrayList<Slot>();
        for (var e : o.getAsJsonArray("slots")) {
            var s = e.getAsJsonObject();
            slots.add(new Slot(
                    s.get("slot").getAsInt(),
                    s.get("key").getAsString(),
                    s.get("count").getAsLong()));
        }
        return new Template(
                o.get("name").getAsString(),
                o.get("revision").getAsLong(),
                o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0,
                List.copyOf(slots));
    }

    private static Integer whole(com.google.gson.JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            var b = e.getAsBigDecimal();
            return b.scale() <= 0 ? b.intValueExact() : null;
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        var e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var arr = new JsonArray();
        for (var t : templates.values()) arr.add(t.json());
        root.add("templates", arr);
        AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
    }
}

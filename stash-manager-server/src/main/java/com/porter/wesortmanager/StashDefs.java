package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Single-server stash definitions, owned by the Manager. The owner creates each stash in the web UI
 * with Position 1 and Position 2 coordinates; the cube between them is the stash. The drop-off chest
 * (where members leave items for sorting into the stash), delivery point, anvil and workbench
 * placements are also stored here. The drop-off chest is selected on stash level in the Manager,
 * never in the game. The bot receives its stash's directive in the
 * heartbeat reply and executes it headlessly (it only needs Manager address, port and API key).
 */
final class StashDefs {
    static final class Conflict extends IOException {
        Conflict() {
            super("Stash definition changed; reload before updating");
        }
    }

    private static final int SCHEMA = 1;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int COORD = 30_000_000;

    record Vec(int x, int y, int z) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            return o;
        }
    }

    record Def(
            String name,
            String dimension,
            Vec pos1,
            Vec pos2,
            Vec dropoff,
            String dropoffDimension,
            Vec delivery,
            String deliveryDimension,
            Vec anvil,
            Vec table,
            long revision,
            long updatedAt) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("dimension", dimension);
            o.add("pos1", pos1.json());
            o.add("pos2", pos2.json());
            o.add("dropoff", dropoff == null ? null : dropoff.json());
            if (dropoffDimension != null) o.addProperty("dropoffDimension", dropoffDimension);
            o.add("delivery", delivery == null ? null : delivery.json());
            if (deliveryDimension != null) o.addProperty("deliveryDimension", deliveryDimension);
            o.add("anvil", anvil == null ? null : anvil.json());
            o.add("table", table == null ? null : table.json());
            o.addProperty("revision", revision);
            o.addProperty("updatedAt", updatedAt);
            return o;
        }

        /**
         * Coordinates stripped: what a website user without the positions permission may see.
         * Name, dimension, revision and timestamp only — no pos1/pos2, delivery, anvil or table.
         */
        JsonObject redacted() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("dimension", dimension);
            o.addProperty("revision", revision);
            o.addProperty("updatedAt", updatedAt);
            return o;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Def> defs = new LinkedHashMap<>();

    StashDefs(Path dataDir) throws IOException {
        this(dataDir, System::currentTimeMillis);
    }

    StashDefs(Path dataDir, LongSupplier clock) throws IOException {
        this.clock = clock;
        this.file = dataDir.resolve("stash-defs.json");
        if (Files.isSymbolicLink(file)) throw new IOException("Invalid stash definitions location");
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schema").getAsBigDecimal().intValueExact() != SCHEMA) throw new IllegalArgumentException();
            for (var e : root.getAsJsonArray("defs")) {
                var o = e.getAsJsonObject();
                var d = read(o);
                defs.put(d.name(), d);
            }
        } catch (RuntimeException ex) {
            throw new IOException("Cannot read stash definitions", ex);
        }
    }

    synchronized JsonArray list() {
        var out = new JsonArray();
        for (var d : defs.values()) out.add(d.json());
        return out;
    }

    /** All definitions (for scope-filtered views; the caller decides full vs redacted JSON). */
    synchronized java.util.List<Def> all() {
        return java.util.List.copyOf(defs.values());
    }

    synchronized Def get(String name) {
        return name == null ? null : defs.get(name);
    }

    /** The directive the bot executes for this stash (heartbeat reply). Null when the stash is unknown. */
    synchronized JsonObject directive(String name) {
        var d = get(name);
        if (d == null) return null;
        var o = new JsonObject();
        o.addProperty("stash", d.name());
        o.addProperty("dimension", d.dimension());
        o.add("pos1", d.pos1().json());
        o.add("pos2", d.pos2().json());
        if (d.delivery() != null) {
            o.add("delivery", d.delivery().json());
            o.addProperty("deliveryDimension", d.deliveryDimension() != null ? d.deliveryDimension() : d.dimension());
        }
        if (d.dropoff() != null) {
            o.add("dropoff", d.dropoff().json());
            o.addProperty("dropoffDimension", d.dropoffDimension() != null ? d.dropoffDimension() : d.dimension());
        }
        if (d.anvil() != null) o.add("anvil", d.anvil().json());
        if (d.table() != null) o.add("table", d.table().json());
        o.addProperty("revision", d.revision());
        return o;
    }

    synchronized Def create(JsonObject req) throws IOException {
        var d = parse(req, 0);
        if (defs.containsKey(d.name())) throw new IllegalArgumentException("stash '" + d.name() + "' exists");
        defs.put(d.name(), d);
        save();
        return d;
    }

    synchronized Def update(JsonObject req, long expectedRevision) throws IOException {
        var d = parse(req, expectedRevision);
        var before = defs.get(d.name());
        if (before == null) throw new IllegalArgumentException("no such stash '" + d.name() + "'");
        if (before.revision() != expectedRevision) throw new Conflict();
        defs.put(d.name(), d);
        save();
        return d;
    }

    synchronized void delete(String name, long expectedRevision) throws IOException {
        var before = defs.get(name);
        if (before == null) throw new IllegalArgumentException("no such stash '" + name + "'");
        if (before.revision() != expectedRevision) throw new Conflict();
        defs.remove(name);
        save();
    }

    private Def parse(JsonObject req, long revision) {
        String name = str(req, "name");
        if (name == null || !NAME.matcher(name).matches())
            throw new IllegalArgumentException("name must be 1-64 chars: letters, digits, _ . -");
        String dimension = str(req, "dimension");
        if (dimension == null || dimension.isEmpty() || dimension.length() > 256)
            throw new IllegalArgumentException("dimension is required");
        var pos1 = vec(req.getAsJsonObject("pos1"), "pos1");
        var pos2 = vec(req.getAsJsonObject("pos2"), "pos2");
        Vec dropoff = req.has("dropoff") && !req.get("dropoff").isJsonNull()
                ? vec(req.getAsJsonObject("dropoff"), "dropoff")
                : null;
        String dropoffDim = str(req, "dropoffDimension");
        Vec delivery = req.has("delivery") && !req.get("delivery").isJsonNull()
                ? vec(req.getAsJsonObject("delivery"), "delivery")
                : null;
        String deliveryDim = str(req, "deliveryDimension");
        Vec anvil =
                req.has("anvil") && !req.get("anvil").isJsonNull() ? vec(req.getAsJsonObject("anvil"), "anvil") : null;
        Vec table =
                req.has("table") && !req.get("table").isJsonNull() ? vec(req.getAsJsonObject("table"), "table") : null;
        long rev = revision > 0 ? revision + 1 : 1;
        return new Def(name, dimension, pos1, pos2, dropoff, dropoffDim, delivery, deliveryDim, anvil, table, rev, clock.getAsLong());
    }

    private static Vec vec(JsonObject o, String what) {
        if (o == null) throw new IllegalArgumentException(what + " must be {x, y, z}");
        Integer x = whole(o.get("x"));
        Integer y = whole(o.get("y"));
        Integer z = whole(o.get("z"));
        if (x == null || y == null || z == null) throw new IllegalArgumentException(what + " needs whole x, y, z");
        if (Math.abs(x) > COORD || Math.abs(z) > COORD || y < -2048 || y > 2048)
            throw new IllegalArgumentException(what + " is out of range");
        return new Vec(x, y, z);
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

    private static Def read(JsonObject o) {
        var pos1 = vec(o.getAsJsonObject("pos1"), "pos1");
        var pos2 = vec(o.getAsJsonObject("pos2"), "pos2");
        Vec dropoff = o.has("dropoff") && !o.get("dropoff").isJsonNull()
                ? vec(o.getAsJsonObject("dropoff"), "dropoff")
                : null;
        Vec delivery = o.has("delivery") && !o.get("delivery").isJsonNull()
                ? vec(o.getAsJsonObject("delivery"), "delivery")
                : null;
        Vec anvil = o.has("anvil") && !o.get("anvil").isJsonNull() ? vec(o.getAsJsonObject("anvil"), "anvil") : null;
        Vec table = o.has("table") && !o.get("table").isJsonNull() ? vec(o.getAsJsonObject("table"), "table") : null;
        return new Def(
                o.get("name").getAsString(),
                o.get("dimension").getAsString(),
                pos1,
                pos2,
                dropoff,
                o.has("dropoffDimension") && !o.get("dropoffDimension").isJsonNull()
                        ? o.get("dropoffDimension").getAsString()
                        : null,
                delivery,
                o.has("deliveryDimension") && !o.get("deliveryDimension").isJsonNull()
                        ? o.get("deliveryDimension").getAsString()
                        : null,
                anvil,
                table,
                o.get("revision").getAsLong(),
                o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0);
    }

    private void save() throws IOException {
        var root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        var arr = new JsonArray();
        for (var d : defs.values()) arr.add(d.json());
        root.add("defs", arr);
        AtomicFiles.write(file, Json.GSON.toJson(root).getBytes(StandardCharsets.UTF_8), true);
    }
}

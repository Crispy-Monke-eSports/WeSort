package com.porter.wesortmanager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * <p>Stored in the guild SQLite database ({@code kit_templates} plus the
 * append-only {@code kit_template_versions}), revision-guarded like the stash
 * definitions: an order pins the template snapshot (revision + fingerprint)
 * at submit, so later edits never change a placed order. The pre-SQLite
 * {@code kit-templates.json} file is a legacy input and is never read
 * (warn-and-ignore, custody.json pattern); there is no migration or backfill.
 *
 * <p>Slot topology is validated against the physical snapshot capacity rules
 * (27 slots, unique 0-26 positions, counts 1-99 preserved as evidence like
 * observed slot counts); variant-identity resolution happens at order time
 * against the live slot projection, where unknown keys fail closed.
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
    /** Manager-local templates live under this server key. */
    static final String LOCAL_SERVER = "";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_KEY = 256;
    /**
     * A slot holds at most one observed stack count. Observed slot counts are
     * preserved as evidence in the 1-99 range (SNBT contract); anything above
     * the real max stack is quarantined at physical handling, never clamped
     * at the definition. The bot verifies the real max stack at pack time.
     */
    private static final int MAX_COUNT = 99;

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

    record Template(String name, long revision, long updatedAt, String fingerprint, List<Slot> slots) {
        JsonObject json() {
            var o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("revision", revision);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("fingerprint", fingerprint);
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

    /** Canonical content digest of the sorted slot list; pinned on orders at submit. */
    static String fingerprint(List<Slot> slots) {
        var canonical = new StringBuilder();
        for (var s : slots)
            canonical.append(s.slot()).append(':').append(s.key()).append(':').append(s.count()).append(';');
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private final StockLedger ledger;
    private final Clock clock;

    KitTemplates(StockLedger ledger, Clock clock) {
        if (ledger == null) throw new IllegalArgumentException("Kit templates require the guild database");
        this.ledger = ledger;
        this.clock = clock == null ? Clock.system() : clock;
    }

    /**
     * Legacy file-store constructor: the {@code kit-templates.json} file is a
     * pre-SQLite input and is never read. Warn loudly so operators preserve or
     * remove it by hand instead of expecting an automatic import.
     */
    KitTemplates(Path dataDir) throws IOException {
        this(dataDir, Clock.system());
    }

    /** Legacy file-store constructor (see {@link #KitTemplates(Path)}). */
    KitTemplates(Path dataDir, Clock clock) throws IOException {
        this(new StockLedger(dataDir, clock), clock);
        Path file = dataDir.resolve("kit-templates.json");
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("Invalid kit templates location");
            if (Files.exists(file))
                System.err.println("WARNING: Data/kit-templates.json"
                        + " is a legacy pre-SQLite file and is ignored — authoritative templates live in guild.sqlite. "
                        + "See INCIDENT-RECOVERY.md.");
        } catch (IOException warn) {
            if (warn.getMessage() != null && warn.getMessage().startsWith("Invalid")) throw warn;
            throw new IOException("Cannot inspect legacy kit templates", warn);
        }
    }

    synchronized JsonArray list() throws IOException {
        var out = new JsonArray();
        for (var t : all()) out.add(t.json());
        return out;
    }

    synchronized List<Template> all() throws IOException {
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT payload FROM kit_templates WHERE server=? ORDER BY name_key")) {
            p.setString(1, LOCAL_SERVER);
            var out = new ArrayList<Template>();
            try (var r = p.executeQuery()) {
                while (r.next()) out.add(read(JsonParser.parseString(r.getString(1)).getAsJsonObject()));
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IOException("Cannot read kit templates", e);
        }
    }

    synchronized Template get(String name) throws IOException {
        if (name == null) return null;
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT payload FROM kit_templates WHERE server=? AND name_key=?")) {
            p.setString(1, LOCAL_SERVER);
            p.setString(2, name);
            try (var r = p.executeQuery()) {
                return r.next() ? read(JsonParser.parseString(r.getString(1)).getAsJsonObject()) : null;
            }
        } catch (SQLException e) {
            throw new IOException("Cannot read kit templates", e);
        }
    }

    synchronized Template create(JsonObject req) throws IOException {
        return create(req, "");
    }

    synchronized Template create(JsonObject req, String actor) throws IOException {
        var parsed = parse(req);
        var slots = parsed.slots();
        long now = clock.millis();
        var t = new Template(parsed.name(), 1, now, fingerprint(slots), slots);
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            try (var p = c.prepareStatement("SELECT 1 FROM kit_templates WHERE server=? AND name_key=?")) {
                p.setString(1, LOCAL_SERVER);
                p.setString(2, t.name());
                try (var r = p.executeQuery()) {
                    if (r.next()) throw new IllegalArgumentException("kit '" + t.name() + "' exists");
                }
            }
            writeVersion(c, t, actor);
            try (var p = c.prepareStatement(
                    "INSERT INTO kit_templates(server,name_key,payload,fingerprint,revision,created_by) VALUES(?,?,?,?,?,?)")) {
                p.setString(1, LOCAL_SERVER);
                p.setString(2, t.name());
                p.setString(3, t.json().toString());
                p.setString(4, t.fingerprint());
                p.setLong(5, t.revision());
                p.setString(6, actor == null ? "" : actor);
                p.executeUpdate();
            }
            tx.commit();
            return t;
        } catch (SQLException e) {
            throw new IOException("Cannot store the kit template", e);
        }
    }

    synchronized Template update(JsonObject req, long expectedRevision) throws IOException {
        return update(req, expectedRevision, "");
    }

    synchronized Template update(JsonObject req, long expectedRevision, String actor) throws IOException {
        var parsed = parse(req);
        long now = clock.millis();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            String stored;
            try (var p = c.prepareStatement("SELECT payload FROM kit_templates WHERE server=? AND name_key=?")) {
                p.setString(1, LOCAL_SERVER);
                p.setString(2, parsed.name());
                try (var r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("no such kit '" + parsed.name() + "'");
                    stored = r.getString(1);
                }
            }
            var before = read(JsonParser.parseString(stored).getAsJsonObject());
            if (before.revision() != expectedRevision) throw new Conflict();
            var t = new Template(parsed.name(), expectedRevision + 1, now, fingerprint(parsed.slots()), parsed.slots());
            writeVersion(c, t, actor);
            try (var p = c.prepareStatement(
                    "UPDATE kit_templates SET payload=?,fingerprint=?,revision=?,created_by=? WHERE server=? AND name_key=?")) {
                p.setString(1, t.json().toString());
                p.setString(2, t.fingerprint());
                p.setLong(3, t.revision());
                p.setString(4, actor == null ? "" : actor);
                p.setString(5, LOCAL_SERVER);
                p.setString(6, t.name());
                if (p.executeUpdate() != 1) throw new Conflict();
            }
            tx.commit();
            return t;
        } catch (SQLException e) {
            throw new IOException("Cannot store the kit template", e);
        }
    }

    synchronized void delete(String name, long expectedRevision) throws IOException {
        delete(name, expectedRevision, "");
    }

    synchronized void delete(String name, long expectedRevision, String actor) throws IOException {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name is required");
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            String stored;
            try (var p = c.prepareStatement("SELECT payload FROM kit_templates WHERE server=? AND name_key=?")) {
                p.setString(1, LOCAL_SERVER);
                p.setString(2, name);
                try (var r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("no such kit '" + name + "'");
                    stored = r.getString(1);
                }
            }
            var before = read(JsonParser.parseString(stored).getAsJsonObject());
            if (before.revision() != expectedRevision) throw new Conflict();
            // Versions stay: deleting the active template never erases history.
            try (var p = c.prepareStatement("DELETE FROM kit_templates WHERE server=? AND name_key=?")) {
                p.setString(1, LOCAL_SERVER);
                p.setString(2, name);
                if (p.executeUpdate() != 1) throw new Conflict();
            }
            tx.commit();
        } catch (SQLException e) {
            throw new IOException("Cannot store the kit template", e);
        }
    }

    private static void writeVersion(java.sql.Connection c, Template t, String actor) throws SQLException {
        try (var p = c.prepareStatement(
                "INSERT INTO kit_template_versions(server,name_key,revision,payload,fingerprint,created_by) VALUES(?,?,?,?,?,?) ON CONFLICT(server,name_key,revision) DO NOTHING")) {
            p.setString(1, LOCAL_SERVER);
            p.setString(2, t.name());
            p.setLong(3, t.revision());
            p.setString(4, t.json().toString());
            p.setString(5, t.fingerprint());
            p.setString(6, actor == null ? "" : actor);
            p.executeUpdate();
        }
    }

    /** Parsed request without revision metadata; the store assigns revision + fingerprint. */
    private record Parsed(String name, List<Slot> slots) {}

    private Parsed parse(JsonObject req) {
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
                throw new IllegalArgumentException("count must be 1-" + MAX_COUNT);
            seen[slot] = true;
            slots.add(new Slot(slot, key, count));
        }
        if (slots.isEmpty()) throw new IllegalArgumentException("a kit needs at least one filled slot");
        slots.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        return new Parsed(name, List.copyOf(slots));
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
        String fingerprint = o.has("fingerprint") && o.get("fingerprint").isJsonPrimitive()
                ? o.get("fingerprint").getAsString()
                : fingerprint(List.copyOf(slots));
        return new Template(
                o.get("name").getAsString(),
                o.get("revision").getAsLong(),
                o.has("updatedAt") ? o.get("updatedAt").getAsLong() : 0,
                fingerprint,
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
}

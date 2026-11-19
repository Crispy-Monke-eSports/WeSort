package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.KitTemplate;
import com.porter.wesort.data.PhysicalSlots;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Manager-owned, revisioned exact definitions shared by the guild. Definition publication never asserts physical stock. */
final class KitCatalog {
    static final class Conflict extends IOException {
        Conflict() {
            super("The kit changed; reload its current version before editing");
        }
    }

    record Published(boolean created, long revision) {}

    private final StockLedger ledger;

    KitCatalog(StockLedger ledger) {
        this.ledger = ledger;
    }

    private static void server(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Invalid Minecraft server");
    }

    synchronized Published publish(String server, String actor, KitTemplate template) throws IOException {
        return save(server, actor, template, 0);
    }

    synchronized Published save(String server, String actor, KitTemplate template, long expectedRevision)
            throws IOException {
        server(server);
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                try (var p = c.prepareStatement(
                        "SELECT fingerprint,revision FROM kit_templates WHERE server=? AND name_key=?")) {
                    p.setString(1, server);
                    p.setString(2, template.key());
                    try (var r = p.executeQuery()) {
                        if (r.next()) {
                            if (r.getLong(2) != expectedRevision
                                    && !(expectedRevision == 0 && r.getString(1).equals(template.fingerprint())))
                                throw new Conflict();
                            if (r.getString(1).equals(template.fingerprint())) {
                                long revision = r.getLong(2);
                                tx.execute("COMMIT");
                                return new Published(false, revision);
                            }
                        } else if (expectedRevision != 0) throw new Conflict();
                    }
                }
                try (var p = c.prepareStatement("SELECT count(*) FROM kit_templates WHERE server=? AND name_key<>?")) {
                    p.setString(1, server);
                    p.setString(2, template.key());
                    try (var r = p.executeQuery()) {
                        r.next();
                        if (r.getInt(1) >= 256) throw new IOException("Guild kit catalog is full");
                    }
                }
                long revision;
                try (var r = tx.executeQuery("SELECT revision FROM kit_meta WHERE id=1")) {
                    r.next();
                    revision = Math.addExact(r.getLong(1), 1);
                }
                try (var p = c.prepareStatement("INSERT OR REPLACE INTO kit_templates VALUES(?,?,?,?,?,?)")) {
                    p.setString(1, server);
                    p.setString(2, template.key());
                    p.setString(3, template.json().toString());
                    p.setString(4, template.fingerprint());
                    p.setLong(5, revision);
                    p.setString(6, actor);
                    p.executeUpdate();
                }
                try (var p = c.prepareStatement("INSERT INTO kit_template_versions VALUES(?,?,?,?,?,?)")) {
                    p.setString(1, server);
                    p.setString(2, template.key());
                    p.setLong(3, revision);
                    p.setString(4, template.json().toString());
                    p.setString(5, template.fingerprint());
                    p.setString(6, actor);
                    p.executeUpdate();
                }
                try (var p = c.prepareStatement("UPDATE kit_meta SET revision=? WHERE id=1")) {
                    p.setLong(1, revision);
                    p.executeUpdate();
                }
                fence(c, server, template.key());
                tx.execute("COMMIT");
                return new Published(true, revision);
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException re) throw re;
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot publish guild template", failure);
        }
    }

    private static void fence(Connection c, String server, String key) throws SQLException {
        try (var p = c.prepareStatement(
                "UPDATE kit_jobs SET state='NEEDS_RECONCILIATION',payload=json_set(payload,'$.message','Template definition changed; reconcile the pinned version') WHERE server=? AND name_key=? AND state IN ('PLANNED','IN_PROGRESS','VERIFYING')")) {
            p.setString(1, server);
            p.setString(2, key);
            p.executeUpdate();
        }
    }

    synchronized boolean delete(String server, String name, String fingerprint, long expectedRevision)
            throws IOException {
        server(server);
        String key = KitTemplate.nameKey(name);
        if (key.isEmpty() || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact template fingerprint required");
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");
            try {
                int removed;
                try (var p = c.prepareStatement(
                        "DELETE FROM kit_templates WHERE server=? AND name_key=? AND fingerprint=? AND revision=?")) {
                    p.setString(1, server);
                    p.setString(2, key);
                    p.setString(3, fingerprint);
                    p.setLong(4, expectedRevision);
                    removed = p.executeUpdate();
                }
                if (removed == 0) throw new Conflict();
                tx.executeUpdate("UPDATE kit_meta SET revision=revision+1 WHERE id=1");
                fence(c, server, key);
                tx.execute("COMMIT");
                return true;
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException re) throw re;
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot remove guild template", failure);
        }
    }

    synchronized KitTemplate version(String server, String name, long revision, String fingerprint) throws IOException {
        server(server);
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT payload FROM kit_template_versions WHERE server=? AND name_key=? AND revision=? AND fingerprint=?")) {
            p.setString(1, server);
            p.setString(2, KitTemplate.nameKey(name));
            p.setLong(3, revision);
            p.setString(4, fingerprint);
            try (var r = p.executeQuery()) {
                if (!r.next()) throw new Conflict();
                return KitTemplate.read(JsonParser.parseString(r.getString(1)).getAsJsonObject());
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read exact kit version", failure);
        }
    }
    /** Definitions may use observed variants; this does not make protected or stale stock available. */
    synchronized Map<String, PhysicalSlots.Item> variants(String server) throws IOException {
        server(server);
        Map<String, PhysicalSlots.Item> out = new TreeMap<>();
        for (var index : ledger.indexes()) {
            if (!index.has("server") || !server.equals(index.get("server").getAsString()) || !index.has("slotVariants"))
                continue;
            var dictionary = index.getAsJsonArray("slotVariants");
            var reader = new PhysicalSlots.Reader(dictionary);
            for (int i = 0; i < dictionary.size(); i++) {
                var row = new JsonArray();
                row.add(0);
                row.add(i);
                row.add(1);
                var slots = new JsonArray();
                slots.add(row);
                var snapshot = reader.snapshot(1, slots);
                for (var located : snapshot.located())
                    if (located.stack().item().kind() != PhysicalSlots.Kind.SHULKER)
                        out.put(
                                located.stack().item().fingerprint(),
                                located.stack().item());
            }
        }
        var entries = list(server).getAsJsonArray("templates");
        for (var entry : entries)
            for (var located : KitTemplate.read(entry.getAsJsonObject().getAsJsonObject("template"))
                    .layout()
                    .located())
                out.put(located.stack().item().fingerprint(), located.stack().item());
        return Map.copyOf(out);
    }

    synchronized JsonObject editor(String server) throws IOException {
        var out = list(server);
        var values = new JsonArray();
        for (var entry : out.getAsJsonArray("templates")) {
            var row = entry.getAsJsonObject();
            var fingerprints = new JsonObject();
            for (var slot :
                    KitTemplate.read(row.getAsJsonObject("template")).layout().slots())
                fingerprints.addProperty(
                        String.valueOf(slot.slot()), slot.item().fingerprint());
            row.add("slotFingerprints", fingerprints);
        }
        for (var item : variants(server).values()) {
            var writer = new PhysicalSlots.Writer();
            var row = new JsonObject();
            row.addProperty("fingerprint", item.fingerprint());
            row.addProperty("id", item.id());
            row.addProperty("maxStack", item.maxStack());
            row.addProperty("components", item.components());
            row.add("slots", writer.slots(List.of(new PhysicalSlots.Slot(0, 1, item))));
            row.add("slotVariants", writer.variants());
            values.add(row);
        }
        out.add("variants", values);
        var destinations = new JsonArray();
        for (var source : ledger.sources())
            if (source.server().equals(server)
                    && source.status().equals("FRESH")
                    && !source.shared()
                    && source.value().has("slotFingerprint")
                    && (!source.value().has("protected")
                            || !source.value().get("protected").getAsBoolean())) {
                var row = new JsonObject();
                row.addProperty("id", source.value().get("id").getAsString());
                row.addProperty("stash", source.stash());
                row.add("dimension", source.value().get("dimension"));
                row.add("parts", source.value().get("parts"));
                destinations.add(row);
            }
        out.add("destinations", destinations);
        return out;
    }

    synchronized KitTemplate compile(String server, JsonObject body) throws IOException {
        var variants = variants(server);
        List<PhysicalSlots.Slot> slots = new ArrayList<>();
        var values = body.getAsJsonArray("layout");
        if (values.size() > 27) throw new IllegalArgumentException("Too many kit slots");
        for (var value : values) {
            var row = value.getAsJsonObject();
            var item = variants.get(row.get("variant").getAsString());
            if (item == null) throw new IllegalArgumentException("Unknown observed variant");
            slots.add(new PhysicalSlots.Slot(
                    row.get("slot").getAsBigDecimal().intValueExact(),
                    row.get("count").getAsBigDecimal().intValueExact(),
                    item));
        }
        return new KitTemplate(body.get("name").getAsString(), new PhysicalSlots.Snapshot(27, slots));
    }

    synchronized JsonObject list(String server) throws IOException {
        server(server);
        var out = new JsonObject();
        var templates = new JsonArray();
        out.addProperty("minecraft", KitTemplate.MINECRAFT);
        out.addProperty("server", server);
        out.addProperty("guild", ledger.epoch());
        try (var c = ledger.open();
                var tx = c.createStatement()) {
            tx.execute("BEGIN");
            try {
                try (var r = tx.executeQuery("SELECT revision FROM kit_meta WHERE id=1")) {
                    r.next();
                    out.addProperty("revision", r.getLong(1));
                }
                try (var p = c.prepareStatement(
                        "SELECT payload,revision FROM kit_templates WHERE server=? ORDER BY name_key")) {
                    p.setString(1, server);
                    try (var r = p.executeQuery()) {
                        while (r.next()) {
                            var row = new JsonObject();
                            row.add("template", JsonParser.parseString(r.getString(1)));
                            row.addProperty("revision", r.getLong(2));
                            templates.add(row);
                        }
                    }
                }
                tx.execute("COMMIT");
            } catch (Exception failure) {
                tx.execute("ROLLBACK");
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot read guild templates", failure);
        }
        out.add("templates", templates);
        return out;
    }
}

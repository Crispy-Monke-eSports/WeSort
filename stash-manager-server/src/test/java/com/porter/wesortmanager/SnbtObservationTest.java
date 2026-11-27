package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnbtObservationTest {
    @TempDir
    Path data;

    private JsonObject observation(StockLedger ledger) throws Exception {
        var frame = new PhysicalSlots.Snapshot(
                27,
                List.of(new PhysicalSlots.Slot(
                        26,
                        7,
                        new PhysicalSlots.Item(
                                "minecraft:diamond",
                                "minecraft:diamond",
                                64,
                                "{\"minecraft:custom_data\":{exact:9007199254740993L}}",
                                PhysicalSlots.Kind.ITEM,
                                0,
                                List.of(),
                                ""))));
        var root = JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":7}],"shulkers":[]}
                """).getAsJsonObject();
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), 1, 0);
        root.addProperty("physicalVersion", 3);
        var source = source(root);
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        return root;
    }

    private static JsonObject source(JsonObject root) {
        return root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }

    @Test
    void acceptsCanonicalSnbtAndRejectsAlternativeAuthority() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        var alternate = root.deepCopy();
        alternate.add("slotVariants", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(alternate));
        var wrong = root.deepCopy();
        source(wrong).addProperty("fingerprint", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(wrong));
        var corrupt = root.deepCopy();
        source(corrupt).addProperty("snbt", "{broken:");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(corrupt));
        assertEquals(0, ledger.revision());
        ledger.accept(root);
        assertEquals(1, ledger.revision());
        var slots = ledger.physicalSlots(source(root).get("id").getAsString());
        assertEquals(1, slots.size());
        assertEquals("26", slots.getFirst().get("path").getAsString());
        assertTrue(slots.getFirst().get("components").getAsString().contains("9007199254740993L"));
    }

    @Test
    void rawSnbtIsTheDatabaseAuthorityAndCorruptProjectionFailsClosed() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        var physical = source(root);
        String id = physical.get("id").getAsString();
        String fingerprint = physical.get("fingerprint").getAsString();
        String snbt = physical.get("snbt").getAsString();
        ledger.accept(root);
        // PR3 (#228 Step 6): the normalized SNBT row is the persisted authority;
        // legacy payload tables stay untouched evidence (no legacy writes).
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "SELECT content_snbt,typeof(content_snbt) FROM snbt_snapshots WHERE sha256=?")) {
            p.setString(1, fingerprint);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals(snbt, r.getString(1));
                assertEquals("text", r.getString(2));
            }
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var q = c.createStatement();
                var r = q.executeQuery("SELECT COUNT(*) FROM physical_slots")) {
            assertTrue(r.next());
            assertEquals(0, r.getLong(1), "legacy slot payloads are read-only evidence, never written");
        }
        var restarted = new StockLedger(data);
        assertEquals(PhysicalSlots.Snapshot.fromSnbt(snbt).json(), restarted.snapshot(id, fingerprint));
        assertEquals(1, restarted.physicalSlots(id).size());
        // Corrupting the projection (the primary reader) fails closed ...
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("UPDATE slot_projection SET count=count+1 WHERE source_id=?")) {
            p.setString(1, id);
            assertEquals(1, p.executeUpdate());
        }
        assertThrows(java.io.IOException.class, () -> restarted.physicalSlots(id));
        assertEquals(PhysicalSlots.Snapshot.fromSnbt(snbt).json(), restarted.snapshot(id, fingerprint));
    }
}

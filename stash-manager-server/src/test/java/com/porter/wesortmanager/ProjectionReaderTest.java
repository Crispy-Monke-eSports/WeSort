package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PR3 (#228 Step 6): slot reads cut over to the SNBT authorities
 * ({@code slot_projection ⨝ variant_register}, {@code order_rows},
 * {@code snbt_snapshots}). SQL id/timestamp/permission/revision/index columns
 * are never content sources, and legacy slot payloads are read-only evidence.
 */
class ProjectionReaderTest {
    @TempDir
    Path data;

    private static PhysicalSlots.Snapshot frame() {
        return new PhysicalSlots.Snapshot(
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
    }

    private static JsonObject observation(StockLedger ledger) throws Exception {
        var frame = frame();
        var root = JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":7}],"shulkers":[]}
                """).getAsJsonObject();
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        root.addProperty("physicalVersion", 3);
        var source = root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        return root;
    }

    private static String sourceId(JsonObject root) {
        return root.getAsJsonArray("physicalSources")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    private static String fingerprint(JsonObject root) {
        return root.getAsJsonArray("physicalSources")
                .get(0)
                .getAsJsonObject()
                .get("fingerprint")
                .getAsString();
    }

    private long count(String sql) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var q = c.createStatement();
                var r = q.executeQuery(sql)) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    @Test
    void projectionMatchesSnbtSnapshotByteForByte() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);
        String fingerprint = fingerprint(root);

        // Wire format freeze: both the JSON view and the raw SNBT stay served.
        var expected = PhysicalSlots.Snapshot.fromSnbt(ledger.snapshotSnbt(id, fingerprint))
                .json();
        assertEquals(expected, ledger.snapshot(id, fingerprint));
        assertEquals(ledger.snapshotSnbt(id, fingerprint), ledger.snapshotSnbtByDigest(fingerprint));

        var positions = expected.getAsJsonArray("positions");
        var slots = ledger.physicalSlots(id);
        assertEquals(positions.size(), slots.size(), "projection must cover every SNBT position");
        for (int i = 0; i < positions.size(); i++) {
            var want = positions.get(i).getAsJsonObject().deepCopy();
            var got = slots.get(i).deepCopy();
            // Revision/presence are projection metadata, not SNBT content.
            assertTrue(got.remove("revision") != null);
            assertTrue(got.remove("present") != null);
            assertEquals(want, got, "projection row must equal SNBT bytes at path " + want.get("path"));
        }
    }

    @Test
    void legacySlotPayloadDivergenceDoesNotLeakIntoReads() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);
        String fingerprint = fingerprint(root);
        var before = ledger.physicalSlots(id);

        // Legacy evidence diverges (forged payload row, wrong counts): readers
        // must ignore it entirely and keep serving SNBT truth.
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "INSERT INTO physical_slots(source,path,parent,payload,fingerprint,item_fingerprint,count,copies,revision,seen_revision,present) VALUES(?,?,?,?,?,?,?,?,?,?,1)")) {
            p.setString(1, id);
            p.setString(2, "0");
            p.setString(3, "");
            p.setString(4, "{\"path\":\"0\",\"forged\":true}");
            p.setString(5, "0".repeat(64));
            p.setString(6, "0".repeat(64));
            p.setLong(7, 999);
            p.setLong(8, 1);
            p.setLong(9, 1);
            p.setLong(10, 1);
            p.executeUpdate();
        }
        var after = ledger.physicalSlots(id);
        assertEquals(before.toString(), after.toString(), "legacy evidence must not leak into reads");
        assertEquals(1, after.size());
        assertEquals(
                fingerprint, ledger.snapshot(id, fingerprint).get("fingerprint").getAsString());
    }

    @Test
    void variantItemLookupUsesProjectionIndex() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);

        long variantId;
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("SELECT variant_id FROM slot_projection WHERE source_id=?")) {
            p.setString(1, id);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                variantId = r.getLong(1);
            }
        }
        var found = ledger.slotsForVariant(variantId);
        assertEquals(List.of(new StockLedger.VariantSlot(id, "26")), found);

        // The variant lookup must resolve through the index, never a scan.
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "EXPLAIN QUERY PLAN SELECT source_id FROM slot_projection WHERE variant_id=?")) {
            p.setLong(1, variantId);
            try (var r = p.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (r.next()) plan.append(r.getString(4)).append(';');
                assertTrue(plan.toString().contains("USING"), "variant lookup must use an index: " + plan);
                assertTrue(
                        plan.toString().contains("slot_projection_variant"),
                        "variant lookup must hit slot_projection_variant: " + plan);
            }
        }
    }

    @Test
    void emptySlotsAreAbsentRows() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);

        // One occupied slot in a size-27 inventory: no air rows anywhere.
        assertEquals(1, count("SELECT COUNT(*) FROM slot_projection WHERE present=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM slot_projection WHERE present=0"));
        assertEquals(0, count("SELECT COUNT(*) FROM slot_projection WHERE count=0"));
        var slots = ledger.physicalSlots(id);
        assertEquals(1, slots.size());
        assertEquals("26", slots.getFirst().get("path").getAsString());
        assertTrue(slots.getFirst().get("present").getAsBoolean());
    }

    @Test
    void projectionRebuildFromSnbtIsIdentical() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);

        var before = dumpProjection(id);
        assertFalse(before.isEmpty());
        var slotsBefore = ledger.physicalSlots(id).toString();

        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("DELETE FROM slot_projection WHERE source_id=?")) {
            p.setString(1, id);
            assertEquals(before.size(), p.executeUpdate());
        }
        // Without the derived rows the gate fails closed: no silent empty stock.
        assertThrows(java.io.IOException.class, () -> ledger.physicalSlots(id));

        ledger.rebuildProjectionFromSnbt(id);
        assertEquals(before, dumpProjection(id), "rebuild from snbt_snapshots must be byte-identical");
        assertEquals(slotsBefore, ledger.physicalSlots(id).toString());
    }

    @Test
    void legacySlotPayloadsStayReadOnlyEvidence() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        ledger.accept(root);
        String id = sourceId(root);
        String fingerprint = fingerprint(root);

        // Fresh observations write only the SNBT authority; the legacy tables
        // stay empty evidence, yet reads keep serving SNBT truth.
        assertEquals(0, count("SELECT COUNT(*) FROM physical_snapshots"));
        assertEquals(0, count("SELECT COUNT(*) FROM physical_slots"));
        assertEquals(1, count("SELECT COUNT(*) FROM snbt_snapshots"));
        assertEquals(1, ledger.physicalSlots(id).size());
        assertEquals(
                fingerprint, ledger.snapshot(id, fingerprint).get("fingerprint").getAsString());
        assertEquals(
                ledger.snapshotSnbt(id, fingerprint),
                ledger.snapshotSnbtByDigest(fingerprint),
                "digest-addressed and source-addressed SNBT reads must agree");
    }

    private List<String> dumpProjection(String source) throws Exception {
        var rows = new java.util.ArrayList<String>();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement(
                        "SELECT source_id,snapshot_ref,logical_path,parent_path,variant_id,count,copies,slot_fingerprint,content_revision,present FROM slot_projection WHERE source_id=? ORDER BY logical_path")) {
            p.setString(1, source);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    var row = new StringBuilder();
                    for (int i = 1; i <= 10; i++) row.append(r.getString(i)).append('|');
                    rows.add(row.toString());
                }
            }
        }
        return List.copyOf(rows);
    }

    @Test
    void orderBooksHaveNoPayloadColumnAndRowsCarryContent() throws Exception {
        var ledger = new StockLedger(data);
        try (var c = ledger.open();
                var q = c.createStatement();
                var r = q.executeQuery("PRAGMA table_info(order_books)")) {
            while (r.next()) assertNotEquals("payload", r.getString(2), "order payload column dropped at v10");
        }

        var repo = new OrderBookRepository(ledger, EventPublisher.noop());
        repo.commit("s", "a", 0, book(order("o-b", 2000, "pending"), order("o-a", 1000, "pending")), null);
        // Rows (not a cached payload) carry the content in stored sequence.
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT order_id,location,seq FROM order_rows WHERE server=? AND stash=? ORDER BY seq")) {
            p.setString(1, "s");
            p.setString(2, "a");
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals("o-b", r.getString(1));
                assertEquals("orders", r.getString(2));
                assertTrue(r.next());
                assertEquals("o-a", r.getString(1));
                assertFalse(r.next(), "one row per order, in book sequence");
            }
        }
        // History is immutable log content, one row per event.
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT payload FROM order_event_log WHERE order_id=? ORDER BY seq")) {
            p.setString(1, "o-a");
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertTrue(r.getString(1).contains("created"));
                assertFalse(r.next(), "append-only history, never rewritten");
            }
        }
    }

    @Test
    void orderLoadSortsCanonicallyByCreatedAtThenId() throws Exception {
        var ledger = new StockLedger(data);
        var repo = new OrderBookRepository(ledger, EventPublisher.noop());
        repo.load(snapshot -> {});
        // Commit array order deliberately differs from creation order.
        repo.commit("s", "a", 0, book(order("o-b", 2000, "pending"), order("o-a", 1000, "pending")), null);
        var index = new IndexStore(data, CreativeTabs.NONE, ledger);
        index.load();
        var orders = new OrderStore(data, index);
        orders.load();
        var stored = orders.book("s", "a").orders;
        assertEquals(List.of("o-a", "o-b"), stored.stream().map(o -> o.id).toList());
    }

    private static JsonObject order(String id, long createdAt, String status) {
        var order = new JsonObject();
        order.addProperty("id", id);
        order.addProperty("server", "s");
        order.addProperty("stash", "a");
        order.addProperty("createdAt", createdAt);
        order.addProperty("updatedAt", createdAt);
        order.addProperty("status", status);
        var lines = new JsonArray();
        var line = new JsonObject();
        line.addProperty("type", "item");
        line.addProperty("key", "minecraft:diamond");
        line.addProperty("count", 1);
        lines.add(line);
        order.add("lines", lines);
        var history = new JsonArray();
        var event = new JsonObject();
        event.addProperty("t", "created");
        history.add(event);
        order.add("history", history);
        return order;
    }

    private static JsonObject book(JsonObject... orders) {
        var root = new JsonObject();
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        var list = new JsonArray();
        for (var o : orders) list.add(o);
        root.add("orders", list);
        return root;
    }
}

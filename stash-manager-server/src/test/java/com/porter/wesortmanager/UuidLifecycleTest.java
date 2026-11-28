package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.ShulkerUuid;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UUID lifecycle on SNBT evidence: v4 mint gate, byte-exact custom_name
 * through persist/projection/read, internal moves, duplicate quarantine,
 * same-transaction order release, the verified export-strip flow, and
 * fail-closed INVALID names.
 */
class UuidLifecycleTest {
    @TempDir
    Path data;

    private final FakeClock time = new FakeClock(1_000_000);

    private StockLedger ledger() throws Exception {
        return new StockLedger(data.resolve("t" + System.nanoTime()), time, 60_000);
    }

    private static String namedComponents(String name) {
        return name == null ? "{}" : "{\"minecraft:custom_name\":\"" + name + "\"}";
    }

    private static JsonArray amounts(Map<String, Long> counts) {
        JsonArray out = new JsonArray();
        counts.forEach((key, count) -> {
            var row = new JsonObject();
            row.addProperty("id", "kit".equals(key) ? "minecraft:shulker_box" : "minecraft:diamond");
            row.addProperty("key", key);
            row.addProperty("count", count);
            out.add(row);
        });
        return out;
    }

    /** One v3 barrel source frame; boxName null (or diamond) means no UUID-carrying box. */
    private JsonObject frame(StockLedger ledger, String boxName, boolean diamond, int x, long seq, int slot)
            throws Exception {
        PhysicalSlots.Slot stack;
        if (diamond) {
            var item = new PhysicalSlots.Item(
                    "minecraft:diamond",
                    "minecraft:diamond",
                    64,
                    "{}",
                    PhysicalSlots.Kind.ITEM,
                    0,
                    List.of(),
                    "");
            stack = new PhysicalSlots.Slot(slot, 1, item);
        } else {
            var box = new PhysicalSlots.Item(
                    "minecraft:shulker_box",
                    "minecraft:shulker_box",
                    1,
                    namedComponents(boxName),
                    PhysicalSlots.Kind.SHULKER,
                    27,
                    List.of(),
                    "kit");
            stack = new PhysicalSlots.Slot(slot, 1, box);
        }
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(stack));
        var source = new JsonObject();
        var identity =
                new ContainerIdentity("s", "minecraft:overworld", List.of(new ContainerIdentity.Position(x, 64, 0)));
        source.addProperty("id", identity.id());
        source.addProperty("dimension", "minecraft:overworld");
        var parts = new JsonArray();
        var pos = new JsonArray();
        pos.add(x);
        pos.add(64);
        pos.add(0);
        parts.add(pos);
        source.add("parts", parts);
        source.addProperty("kind", "minecraft:barrel");
        source.addProperty("size", 27);
        source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001");
        source.addProperty("sequence", seq);
        source.addProperty("epoch", ledger.epoch());
        source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", ledger.clock.millis());
        source.addProperty("fingerprint", snapshot.fingerprint());
        source.addProperty("snbt", snapshot.snbt());
        source.add("items", amounts(snapshot.totals()));
        source.add("shulkers", amounts(snapshot.shulkers()));
        return source;
    }

    private JsonObject batch(StockLedger ledger, JsonObject... sources) {
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        root.add("items", new JsonArray());
        root.add("shulkers", new JsonArray());
        var array = new JsonArray();
        for (var s : sources) array.add(s);
        root.add("physicalSources", array);
        root.addProperty("physicalVersion", 3);
        return root;
    }

    private static String sourceId(JsonObject source) {
        return source.get("id").getAsString();
    }

    private static String identityStatus(StockLedger ledger, String source) throws Exception {
        return ledger.boxIdentities(source)
                .get(0)
                .getAsJsonObject()
                .get("identityStatus")
                .getAsString();
    }

    @Test
    void mintedUuidIsV4AndLabelRoundTrips() throws Exception {
        var ledger = ledger();
        var unnamed = frame(ledger, null, false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, unnamed));
        var jobs = new BoxJobs(ledger);
        var request = new JsonObject();
        request.addProperty("server", "s");
        request.addProperty("stash", "a");
        request.addProperty("source", sourceId(unnamed));
        request.addProperty("path", "0");
        request.addProperty("kind", "REGISTER_BOX");
        var created = jobs.create("owner", request);
        UUID uuid = UUID.fromString(created.get("uuid").getAsString());
        assertEquals(4, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(ShulkerUuid.label(uuid), created.get("name").getAsString());
        assertEquals(uuid, ShulkerUuid.parse(created.get("name").getAsString()));
    }

    @Test
    void customNameSurvivesSnapshotPersistProjectionReadByteExact() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        String name = ShulkerUuid.label(uuid);
        var named = frame(ledger, name, false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, named));
        String id = sourceId(named);

        var slots = ledger.physicalSlots(id);
        assertEquals(1, slots.size());
        String components = slots.get(0).get("components").getAsString();
        assertEquals(name, ShulkerUuid.customName(components));
        assertTrue(components.contains("\"" + name + "\""), components);
        assertEquals(uuid, ShulkerUuid.boxUuid(components));

        // The registered variant bytes carry the identical custom_name.
        try (var c = ledger.open();
                var p = c.prepareStatement("""
                        SELECT v.canonical_variant_snbt FROM slot_projection p
                        JOIN variant_register v ON v.variant_id=p.variant_id
                        WHERE p.source_id=? AND p.logical_path=? AND p.present=1""")) {
            p.setString(1, id);
            p.setString(2, "0");
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                String canonical = r.getString(1);
                assertEquals(name, ShulkerUuid.customName(SnbtStore.variantComponents(canonical)));
                assertTrue(canonical.contains(name), canonical);
            }
        }
        assertEquals("REGISTERED", identityStatus(ledger, id));
    }

    @Test
    void internalMoveFollowsTheRegistry() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        String name = ShulkerUuid.label(uuid);
        long seq = ledger.revision() + 1;
        var boxA = frame(ledger, name, false, 0, seq, 0);
        var plainB = frame(ledger, null, true, 5, seq + 1, 0);
        ledger.accept(batch(ledger, boxA, plainB));
        assertEquals(
                sourceId(boxA),
                ledger.boxUuids().get(0).getAsJsonObject().get("source").getAsString());

        long seq2 = ledger.revision() + 1;
        var plainA = frame(ledger, null, true, 0, seq2, 0);
        var boxB = frame(ledger, name, false, 5, seq2 + 1, 0);
        ledger.accept(batch(ledger, plainA, boxB));
        // The old source was fully observed without the box: the identity follows it.
        var row = ledger.boxUuids().get(0).getAsJsonObject();
        assertEquals(uuid.toString(), row.get("uuid").getAsString());
        assertEquals(sourceId(boxB), row.get("source").getAsString());
        assertTrue(ledger.quarantinedBoxes("s").isEmpty());
        assertEquals("REGISTERED", identityStatus(ledger, sourceId(boxB)));
    }

    @Test
    void duplicateInTwoPlacesQuarantinesBothAndKeepsTheFirst() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        String name = ShulkerUuid.label(uuid);
        long seq = ledger.revision() + 1;
        ledger.accept(batch(ledger, frame(ledger, name, false, 0, seq, 0), frame(ledger, null, true, 5, seq + 1, 0)));
        String idA = ledger.boxUuids().get(0).getAsJsonObject().get("source").getAsString();

        long seq2 = ledger.revision() + 1;
        ledger.accept(
                batch(ledger, frame(ledger, name, false, 0, seq2, 0), frame(ledger, name, false, 5, seq2 + 1, 0)));
        // First sighting wins; both physical roots are unsafe until reconciled by hand.
        assertEquals(idA, ledger.boxUuids().get(0).getAsJsonObject().get("source").getAsString());
        var unsafe = ledger.quarantinedBoxes("s");
        assertEquals(2, unsafe.size());
        assertEquals("REGISTERED", identityStatus(ledger, idA));
    }

    @Test
    void invalidNamesNeverRegister() throws Exception {
        var ledger = ledger();
        UUID v4 = UUID.randomUUID();
        String upper = "WeSort-" + v4.toString().toUpperCase();
        String v1 = "WeSort-a8098c1a-f86e-11da-bd1a-00112444be1e";
        String nonCanonical = "WeSort-550E8400-E29B-41D4-A716-446655440000";
        long seq = ledger.revision() + 1;
        var a = frame(ledger, upper, false, 0, seq, 0);
        var b = frame(ledger, v1, false, 1, seq + 1, 0);
        var c = frame(ledger, nonCanonical, false, 2, seq + 2, 0);
        ledger.accept(batch(ledger, a, b, c));
        assertTrue(ledger.boxUuids().isEmpty());
        assertEquals("INVALID", identityStatus(ledger, sourceId(a)));
        assertEquals("INVALID", identityStatus(ledger, sourceId(b)));
        assertEquals("INVALID", identityStatus(ledger, sourceId(c)));
    }

    @Test
    void orderReleaseDeregistersSameTxWithHistory() throws Exception {
        var dir = data.resolve("t" + System.nanoTime());
        var clock = new FakeClock(2_000_000);
        var ledger = new StockLedger(dir, clock, 60_000);
        var index = new IndexStore(dir, CreativeTabs.NONE, ledger);
        index.load();
        StockFixtures.put(
                index,
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[],\"shulkers\":[{\"key\":\"kit\",\"id\":\"minecraft:shulker_box\",\"count\":1}]}")
                        .getAsJsonObject());
        UUID uuid = UUID.randomUUID();
        ledger.accept(batch(ledger, frame(ledger, ShulkerUuid.label(uuid), false, 0, ledger.revision() + 1, 0)));
        var orders = new OrderStore(dir, index, clock);
        orders.load();
        var placed = orders.submit(
                "s",
                "a",
                List.of(new OrderStore.Line("shulker", "kit", 1)),
                null,
                null,
                null,
                null,
                null,
                OrderIntent.DEFAULT,
                null,
                false,
                ignored -> null);
        var book = OrderBookFixtures.read(index, "s", "a");
        var pending = new JsonArray();
        pending.add(uuid.toString());
        book.getAsJsonArray("orders").get(0).getAsJsonObject().add("pendingUuidDeregistrations", pending);
        OrderBookFixtures.replace(index, book);

        var recovered = new OrderStore(dir, index, clock);
        recovered.load();
        assertTrue(ledger.boxUuids().isEmpty());
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT reason FROM box_uuid_history WHERE uuid=?")) {
            p.setString(1, uuid.toString());
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals("DEREGISTERED_FOR_ORDER", r.getString(1));
            }
        }
        // Replaying again is a harmless no-op.
        var again = new OrderStore(dir, index, clock);
        again.load();
    }

    @Test
    void exportStripNeedsAFreshStrippedObservation() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        String name = ShulkerUuid.label(uuid);
        var named = frame(ledger, name, false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, named));
        String id = sourceId(named);

        // The name is still applied: no export.
        assertThrows(
                java.io.IOException.class,
                () -> ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null));

        // The agent strips the name and the next fresh observation proves it;
        // the accept sweep moves the row to VANISHED history in that same tx.
        var stripped = frame(ledger, null, false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, stripped));
        assertTrue(ledger.boxUuids().isEmpty());

        ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null);
        try (var c = ledger.open();
                var p = c.prepareStatement("SELECT status,export_receipt FROM shulker_identity WHERE uuid=?")) {
            p.setString(1, uuid.toString());
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals("EXPORT_STRIPPED", r.getString(1));
                assertTrue(r.getString(2) != null && r.getString(2).startsWith("export:"), r.getString(2));
            }
        }
        try (var c = ledger.open();
                var r = c.createStatement()
                        .executeQuery(
                                "SELECT count(*) FROM audit_log WHERE action='box.export-stripped'")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
        }
        // A second export of the same box fails: the receipt is already anchored.
        assertThrows(
                StockLedger.BoxReleaseConflict.class,
                () -> ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null));
    }

    @Test
    void exportStripRollsBackReceiptAndAuditTogether() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        var named = frame(ledger, ShulkerUuid.label(uuid), false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, named));
        String id = sourceId(named);
        ledger.accept(batch(ledger, frame(ledger, null, false, 0, ledger.revision() + 1, 0)));
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.execute("CREATE TRIGGER reject_identity BEFORE UPDATE ON shulker_identity"
                    + " BEGIN SELECT RAISE(ABORT,'injected identity failure'); END");
        }
        try {
            assertThrows(
                    java.io.IOException.class,
                    () -> ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null));
            try (var c = ledger.open();
                    var p = c.prepareStatement("SELECT export_receipt FROM shulker_identity WHERE uuid=?")) {
                p.setString(1, uuid.toString());
                try (var r = p.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals(null, r.getString(1));
                }
            }
            try (var c = ledger.open();
                    var r = c.createStatement()
                            .executeQuery("SELECT count(*) FROM audit_log WHERE action='box.export-stripped'")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        } finally {
            try (var c = ledger.open();
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER reject_identity");
            }
        }
        ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null);
    }

    @Test
    void exportStripDeregistersARowThatIsStillRegistered() throws Exception {
        var ledger = ledger();
        UUID uuid = UUID.randomUUID();
        var named = frame(ledger, ShulkerUuid.label(uuid), false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, named));
        String id = sourceId(named);
        // The strip is observed (projection no longer carries the name) while
        // the registry row is re-seeded at the same slot: the export tx must
        // deregister it with EXPORT_STRIPPED itself.
        ledger.accept(batch(ledger, frame(ledger, null, false, 0, ledger.revision() + 1, 0)));
        time.advance(1000);
        long revision = ledger.revision();
        try (var c = ledger.open();
                var tx = new SqliteWriteTransaction(c)) {
            try (var p = c.prepareStatement(
                    "INSERT INTO box_uuids(uuid,server,source,path,status,item_fingerprint,registered_at,verified_at,revision) VALUES(?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, uuid.toString());
                p.setString(2, "s");
                p.setString(3, id);
                p.setString(4, "0");
                p.setString(5, "IN_STOCK");
                p.setString(6, "seed");
                p.setLong(7, time.get());
                p.setLong(8, time.get());
                p.setLong(9, revision);
                p.executeUpdate();
            }
            tx.commit();
        }
        ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null);
        assertTrue(ledger.boxUuids().isEmpty());
        try (var c = ledger.open();
                var p = c.prepareStatement(
                        "SELECT reason FROM box_uuid_history WHERE uuid=? ORDER BY recorded_at DESC LIMIT 1")) {
            p.setString(1, uuid.toString());
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals("EXPORT_STRIPPED", r.getString(1));
            }
        }
    }

    @Test
    void unknownBoxAndStaleObservationFailExportClosed() throws Exception {
        var ledger = ledger();
        var named = frame(ledger, ShulkerUuid.label(UUID.randomUUID()), false, 0, ledger.revision() + 1, 0);
        ledger.accept(batch(ledger, named));
        String id = sourceId(named);
        // Never registered here.
        assertThrows(
                StockLedger.BoxReleaseConflict.class,
                () -> ledger.exportStripped("s", id, "0", UUID.randomUUID().toString(), "owner", "owner", null));
        // Stale: the gate needs a fresh observation, not an aged one.
        time.advance(61_000);
        UUID uuid = UUID.randomUUID();
        assertThrows(
                java.io.IOException.class,
                () -> ledger.exportStripped("s", id, "0", uuid.toString(), "owner", "owner", null));
        assertFalse(ledger.boxUuids().isEmpty());
    }
}

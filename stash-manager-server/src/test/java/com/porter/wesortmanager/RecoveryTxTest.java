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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery as single transactions: loss-acceptance tombstones (payload +
 * projection + membership + box sweep + audit in one tx), the materialized
 * 7-day MISSING escalation, bulk-STALE invalidation, and crash-safe UUID
 * deregistration replay.
 */
class RecoveryTxTest {
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

    /** A v3 fresh barrel observation whose root slot 0 is a shulker with the given anvil name. */
    private JsonObject fresh(StockLedger ledger, String boxName) throws Exception {
        var box = new PhysicalSlots.Item(
                "minecraft:shulker_box",
                "minecraft:shulker_box",
                1,
                namedComponents(boxName),
                PhysicalSlots.Kind.SHULKER,
                27,
                List.of(),
                "kit");
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box)));
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        root.add("items", new JsonArray());
        root.add("shulkers", new JsonArray());
        var identity =
                new ContainerIdentity("s", "minecraft:overworld", List.of(new ContainerIdentity.Position(0, 64, 0)));
        var source = new JsonObject();
        source.addProperty("id", identity.id());
        source.addProperty("dimension", "minecraft:overworld");
        var parts = new JsonArray();
        var pos = new JsonArray();
        pos.add(0);
        pos.add(64);
        pos.add(0);
        parts.add(pos);
        source.add("parts", parts);
        source.addProperty("kind", "minecraft:barrel");
        source.addProperty("size", 27);
        source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001");
        source.addProperty("sequence", ledger.revision() + 1);
        source.addProperty("epoch", ledger.epoch());
        source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", ledger.clock.millis());
        source.addProperty("fingerprint", snapshot.fingerprint());
        source.addProperty("snbt", snapshot.snbt());
        source.add("items", amounts(snapshot.totals()));
        source.add("shulkers", amounts(snapshot.shulkers()));
        var sources = new JsonArray();
        sources.add(source);
        root.add("physicalSources", sources);
        root.addProperty("physicalVersion", 3);
        return root;
    }

    private static String sourceId(JsonObject index) {
        return index.getAsJsonArray("physicalSources")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString();
    }

    /** A v1 MISSING report for the same barrel container. */
    private JsonObject missing(StockLedger ledger) throws Exception {
        var frame = JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[]}").getAsJsonObject();
        var missing = StockFixtures.physical(frame, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var source = missing.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("state", "MISSING");
        return missing;
    }

    private static long count(
            StockLedger ledger, String sql, String param) throws Exception {
        try (var c = ledger.open();
                var p = c.prepareStatement(sql)) {
            p.setString(1, param);
            try (var r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Test
    void acceptLossTombstonesEverythingInOneTransaction() throws Exception {
        var ledger = ledger();
        String uuid = UUID.randomUUID().toString();
        ledger.accept(fresh(ledger, ShulkerUuid.label(UUID.fromString(uuid))));
        String id = ledger.sources().getFirst().value().get("id").getAsString();
        ledger.accept(missing(ledger));
        assertEquals("MISSING", ledger.sources().getFirst().status());
        assertEquals(
                uuid,
                ledger.boxUuids().get(0).getAsJsonObject().get("uuid").getAsString());

        var audit = new AuditLog(ledger, time);
        var batch = audit.batch();
        batch.actor("owner", "owner", null);
        batch.say("stock.loss.accepted", id);
        assertTrue(ledger.acceptLoss(id, "owner", batch.entries()));

        var tombstone = ledger.sources().getFirst();
        assertEquals("WITHDRAWN", tombstone.status());
        assertTrue(tombstone.value().getAsJsonArray("items").isEmpty());
        assertTrue(tombstone.value().getAsJsonArray("shulkers").isEmpty());
        assertEquals(
                "owner",
                tombstone.value().getAsJsonObject("lossAccepted").get("by").getAsString());
        assertEquals(0, count(ledger, "SELECT count(*) FROM slot_projection WHERE source_id=? AND present=1", id));
        assertEquals(0, count(ledger, "SELECT count(*) FROM stock_members WHERE source=? AND present=1", id));
        assertTrue(ledger.boxUuids().isEmpty());
        try (var c = ledger.open();
                var r = c.createStatement()
                        .executeQuery("SELECT reason FROM box_uuid_history WHERE uuid='" + uuid + "'")) {
            assertTrue(r.next());
            assertEquals("WITHDRAWN", r.getString(1));
        }
        try (var c = ledger.open();
                var r = c.createStatement()
                        .executeQuery(
                                "SELECT count(*) FROM audit_log WHERE action='stock.loss.accepted'")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
        }
        // Idempotent replay still succeeds.
        assertTrue(ledger.acceptLoss(id, "owner"));
    }

    @Test
    void acceptLossRollsBackEverythingWhenAuditFails() throws Exception {
        var ledger = ledger();
        String uuid = UUID.randomUUID().toString();
        ledger.accept(fresh(ledger, ShulkerUuid.label(UUID.fromString(uuid))));
        String id = ledger.sources().getFirst().value().get("id").getAsString();
        ledger.accept(missing(ledger));
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.execute("CREATE TRIGGER reject_loss_audit BEFORE INSERT ON audit_log"
                    + " BEGIN SELECT RAISE(ABORT,'injected audit failure'); END");
        }
        var audit = new AuditLog(ledger, time);
        var batch = audit.batch();
        batch.actor("owner", "owner", null);
        batch.say("stock.loss.accepted", id);
        try {
            assertThrows(java.io.IOException.class, () -> ledger.acceptLoss(id, "owner", batch.entries()));
            assertEquals("MISSING", ledger.sources().getFirst().status());
            assertFalse(ledger.sources()
                    .getFirst()
                    .value()
                    .getAsJsonArray("shulkers")
                    .isEmpty());
            assertEquals(1, count(ledger, "SELECT count(*) FROM slot_projection WHERE source_id=? AND present=1", id));
            assertEquals(1, count(ledger, "SELECT count(*) FROM stock_members WHERE source=? AND present=1", id));
            assertEquals(
                    uuid, ledger.boxUuids().get(0).getAsJsonObject().get("uuid").getAsString());
            try (var c = ledger.open();
                    var r = c.createStatement().executeQuery("SELECT count(*) FROM box_uuid_history")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        } finally {
            try (var c = ledger.open();
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER reject_loss_audit");
            }
        }
    }

    @Test
    void escalationSweepIsMaterializedIdempotentAndAudited() throws Exception {
        var ledger = ledger();
        ledger.accept(fresh(ledger, null));
        String id = ledger.sources().getFirst().value().get("id").getAsString();
        ledger.accept(missing(ledger));
        assertEquals("MISSING", ledger.sources().getFirst().status());

        time.advance(StockLedger.MISSING_WITHDRAWN_AFTER_MS + 1);
        assertEquals(1, ledger.sweepWithdrawnEscalations());
        var tombstone = ledger.sources().getFirst();
        assertEquals("WITHDRAWN", tombstone.status());
        assertTrue(tombstone.value().getAsJsonArray("shulkers").isEmpty());
        assertEquals(
                "system",
                tombstone.value().getAsJsonObject("lossAccepted").get("by").getAsString());
        assertEquals(
                "escalated",
                tombstone.value().getAsJsonObject("lossAccepted").get("reason").getAsString());
        // Idempotent: a second run finds nothing and bumps no revision.
        long revision = ledger.revision();
        assertEquals(0, ledger.sweepWithdrawnEscalations());
        assertEquals(revision, ledger.revision());
        try (var c = ledger.open();
                var r = c.createStatement()
                        .executeQuery(
                                "SELECT count(*) FROM audit_log WHERE action='stock.loss.escalated'")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
        }
        // A healing fresh read revives the escalated source instead of sticking as withdrawn.
        ledger.accept(fresh(ledger, null));
        assertEquals("FRESH", ledger.sources().getFirst().status());
        assertEquals(1, count(ledger, "SELECT count(*) FROM slot_projection WHERE source_id=? AND present=1", id));
    }

    @Test
    void sweepRunsInsideTheAcceptTransaction() throws Exception {
        var ledger = ledger();
        ledger.accept(fresh(ledger, null));
        ledger.accept(missing(ledger));
        time.advance(StockLedger.MISSING_WITHDRAWN_AFTER_MS + 1);
        // An unrelated fresh stash observation carries the escalation in the same revision.
        long before = ledger.revision();
        var other = StockFixtures.physical(
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"b\",\"items\":[{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":1}]}")
                        .getAsJsonObject(),
                ledger.epoch(),
                before,
                before + 10,
                7);
        ledger.accept(other);
        assertEquals(before + 1, ledger.revision());
        assertEquals(
                "WITHDRAWN",
                ledger.sources().stream()
                        .filter(s -> s.stash().equals("a"))
                        .findFirst()
                        .orElseThrow()
                        .status());
    }

    @Test
    void invalidatedServerNeverAllocatesButHealsOnFresh() throws Exception {
        var ledger = ledger();
        String uuid = UUID.randomUUID().toString();
        ledger.accept(fresh(ledger, ShulkerUuid.label(UUID.fromString(uuid))));
        String id = ledger.sources().getFirst().value().get("id").getAsString();
        try (var c = ledger.open()) {
            ledger.invalidateServer(c, "s");
        }
        assertEquals("STALE", ledger.sources().getFirst().status());
        var observations = PhysicalReservations.read(ledger, "s", "a");
        assertTrue(observations.available(Set.of()).isEmpty());
        assertThrows(
                OrderStore.OrderException.class,
                () -> observations.allocate(
                        List.of(new OrderStore.Line("shulker", "kit", 1)), Set.of(), Map.of("shulker:kit", 0)));
        // A fresh re-read heals the voided source and its reservations.
        ledger.accept(fresh(ledger, ShulkerUuid.label(UUID.fromString(uuid))));
        assertEquals("FRESH", ledger.sources().getFirst().status());
        var healed = PhysicalReservations.read(ledger, "s", "a");
        assertEquals(
                1,
                healed.allocate(
                                List.of(new OrderStore.Line("shulker", "kit", 1)), Set.of(), Map.of("shulker:kit", 0))
                        .size());
        assertEquals(id, ledger.sources().getFirst().value().get("id").getAsString());
    }

    @Test
    void pendingUuidReplayIsAtomicUnderFailure() throws Exception {
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
        String uuid = UUID.randomUUID().toString();
        ledger.accept(fresh(ledger, ShulkerUuid.label(UUID.fromString(uuid))));
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
        assertTrue(placed.order().allocations != null && !placed.order().allocations.isEmpty());

        var book = OrderBookFixtures.read(index, "s", "a");
        var pending = new JsonArray();
        pending.add(uuid);
        book.getAsJsonArray("orders").get(0).getAsJsonObject().add("pendingUuidDeregistrations", pending);
        OrderBookFixtures.replace(index, book);
        try (var c = ledger.open();
                var s = c.createStatement()) {
            s.execute("CREATE TRIGGER reject_uuid_history BEFORE INSERT ON box_uuid_history"
                    + " BEGIN SELECT RAISE(ABORT,'injected history failure'); END");
        }
        try {
            var restarting = new OrderStore(dir, index, clock);
            assertThrows(java.io.IOException.class, restarting::load);
            // Nothing half-applied: the outbox entry and the registry row both survive.
            var reread = OrderBookFixtures.read(index, "s", "a");
            assertTrue(reread.getAsJsonArray("orders")
                    .get(0)
                    .getAsJsonObject()
                    .has("pendingUuidDeregistrations"));
            assertEquals(1, ledger.boxUuids().size());
            try (var c = ledger.open();
                    var r = c.createStatement().executeQuery("SELECT count(*) FROM box_uuid_history")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        } finally {
            try (var c = ledger.open();
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER reject_uuid_history");
            }
        }
        // Without the fault the replay lands exactly once and is idempotent afterwards.
        var recovered = new OrderStore(dir, index, clock);
        recovered.load();
        var done = OrderBookFixtures.read(index, "s", "a");
        assertFalse(done.getAsJsonArray("orders")
                .get(0)
                .getAsJsonObject()
                .has("pendingUuidDeregistrations"));
        assertTrue(ledger.boxUuids().isEmpty());
        var reloaded = new OrderStore(dir, index, clock);
        reloaded.load();
    }

    @Test
    void staleNeverCountsButQueueableLastVerifiedDoes() throws Exception {
        var ledger = ledger();
        ledger.accept(fresh(ledger, null));
        try (var c = ledger.open()) {
            ledger.invalidateServer(c, "s");
        }
        // Explicitly voided (STALE) stock backs neither verified nor queued reservations.
        assertEquals("STALE", ledger.sources().getFirst().status());
        assertTrue(PhysicalReservations.read(ledger, "s", "a")
                .available(Set.of(), null, true)
                .isEmpty());
    }
}

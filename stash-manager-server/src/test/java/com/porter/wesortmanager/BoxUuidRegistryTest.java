package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoxUuidRegistryTest {
    @TempDir
    Path data;

    private static final String V4 = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OTHER = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private static String namedComponents(String name) {
        return "{\"minecraft:custom_name\":\"" + name
                + "\",\"minecraft:container\":[{slot:26,item:{id:\"minecraft:diamond\",count:32}}]}";
    }

    private static PhysicalSlots.Snapshot frame(String boxName) {
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var box = new PhysicalSlots.Item(
                "minecraft:shulker_box",
                "minecraft:shulker_box",
                1,
                boxName == null ? "{}" : namedComponents(boxName),
                PhysicalSlots.Kind.SHULKER,
                27,
                boxName == null ? List.of() : List.of(new PhysicalSlots.Slot(26, 32, diamond)),
                "kit");
        return new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box)));
    }

    private static JsonArray amounts(Map<String, Long> counts) {
        JsonArray out = new JsonArray();
        counts.forEach((key, count) -> {
            var row = new JsonObject();
            row.addProperty("id", "kit".equals(key) ? "minecraft:shulker_box" : key);
            row.addProperty("key", key);
            row.addProperty("count", count);
            out.add(row);
        });
        return out;
    }

    private JsonObject indexes(StockLedger ledger, String[] names, int[] xs) throws Exception {
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("receivedAt", System.currentTimeMillis());
        root.addProperty("server", "s");
        root.addProperty("stash", "a");
        var sources = new JsonArray();
        for (int i = 0; i < names.length; i++) {
            var frame = frame(names[i]);
            var identity = new ContainerIdentity(
                    "s", "minecraft:overworld", List.of(new ContainerIdentity.Position(xs[i], 64, 0)));
            var source = new JsonObject();
            source.addProperty("id", identity.id());
            source.addProperty("dimension", "minecraft:overworld");
            var parts = new JsonArray();
            var pos = new JsonArray();
            pos.add(xs[i]);
            pos.add(64);
            pos.add(0);
            parts.add(pos);
            source.add("parts", parts);
            source.addProperty("kind", "minecraft:barrel");
            source.addProperty("size", 27);
            source.addProperty("state", "FRESH");
            source.addProperty("session", "00000000-0000-0000-0000-000000000001");
            // A new heartbeat gets a new sequence, or a rescan looks like a conflicting replay.
            source.addProperty("sequence", ledger.revision() + i + 1);
            source.addProperty("epoch", ledger.epoch());
            source.addProperty("baseRevision", ledger.revision());
            source.addProperty("observedAt", System.currentTimeMillis());
            source.addProperty("fingerprint", frame.fingerprint());
            source.addProperty("snbt", frame.snbt());
            source.add("items", amounts(frame.totals()));
            source.add("shulkers", amounts(frame.shulkers()));
            sources.add(source);
        }
        root.add("items", new JsonArray());
        root.add("shulkers", new JsonArray());
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

    @Test
    void registersAnObservedBoxUuidAndReportsIdentity() throws Exception {
        var ledger = new StockLedger(data);
        var index = indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0});
        ledger.accept(index);
        String id = sourceId(index);
        assertEquals(1, ledger.boxUuids().size());
        var row = ledger.boxUuids().get(0).getAsJsonObject();
        assertEquals(V4, row.get("uuid").getAsString());
        assertEquals("IN_STOCK", row.get("status").getAsString());
        assertEquals(id, row.get("source").getAsString());
        assertEquals("0", row.get("path").getAsString());
        var identities = ledger.boxIdentities(id);
        assertEquals(1, identities.size());
        assertEquals(
                "REGISTERED",
                identities.get(0).getAsJsonObject().get("identityStatus").getAsString());
        assertEquals(V4, identities.get(0).getAsJsonObject().get("boxUUID").getAsString());
        assertTrue(identities.get(0).getAsJsonObject().get("hasUUID").getAsBoolean());
    }

    @Test
    void duplicateUuidAtAnotherLocationIsDetectedButNeverReregistered() throws Exception {
        var ledger = new StockLedger(data);
        var both = indexes(ledger, new String[] {"WeSort-" + V4, "WeSort-" + V4}, new int[] {0, 1});
        ledger.accept(both);
        assertEquals(1, ledger.boxUuids().size());
        var ids = new ArrayList<String>();
        for (var s : ledger.sources()) ids.add(s.value().get("id").getAsString());
        var statuses = new ArrayList<String>();
        for (var id : ids)
            ledger.boxIdentities(id)
                    .forEach(b -> statuses.add(
                            b.getAsJsonObject().get("identityStatus").getAsString()));
        assertEquals(
                List.of("DUPLICATE", "REGISTERED"), statuses.stream().sorted().toList());
    }

    @Test
    void ambiguousAndMalformedBoxLabelsFenceWholeBoxesAndTheirContents() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0}));
        var first = PhysicalReservations.read(ledger, "s", "a");
        var allocation = first.allocate(List.of(new OrderStore.Line("shulker", "kit", 1)), Set.of(), Map.of());
        assertTrue(first.matches(allocation));
        ledger.accept(
                indexes(ledger, new String[] {"WeSort-" + V4, "WeSort-" + V4, "WeSort-invalid"}, new int[] {0, 1, 2}));
        var blocked = PhysicalReservations.read(ledger, "s", "a");
        assertFalse(blocked.matches(allocation));
        assertTrue(blocked.available(Set.of()).isEmpty());
        assertTrue(blocked.available(Set.of(), null, true).isEmpty());
        ledger.accept(indexes(ledger, new String[] {"WeSort-" + V4, null, "Personal box"}, new int[] {0, 1, 2}));
        var resolved = PhysicalReservations.read(ledger, "s", "a");
        assertEquals(32L, resolved.available(Set.of()).get("item:minecraft:diamond"));
    }

    @Test
    void deregisteredUuidMovesToHistoryAndIsRegisteredAgainWhenSeen() throws Exception {
        var ledger = new StockLedger(data);
        var index = indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0});
        ledger.accept(index);
        String id = sourceId(index);
        var repo = new OrderBookRepository(ledger);
        var book = JsonParser.parseString(
                        "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[{\"id\":\"o-1\",\"history\":[{\"action\":\"staged\"}]}]}")
                .getAsJsonObject();
        var release = new StockLedger.BoxRelease(Set.of(new PhysicalReservations.Root(id, "0")), Set.of(V4));
        try (var c = ledger.open();
                var sql = c.createStatement()) {
            sql.execute(
                    "CREATE TRIGGER reject_stage BEFORE INSERT ON order_event_log BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(java.io.IOException.class, () -> repo.commit("s", "a", 0, book, release));
            assertEquals(1, ledger.boxUuids().size());
            assertTrue(ledger.boxUuidHistory().isEmpty());
            sql.execute("DROP TRIGGER reject_stage");
        }
        var wrongSlot = new StockLedger.BoxRelease(Set.of(new PhysicalReservations.Root(id, "1")), Set.of(V4));
        assertThrows(StockLedger.BoxReleaseConflict.class, () -> repo.commit("s", "a", 0, book, wrongSlot));
        assertEquals(1, repo.commit("s", "a", 0, book, release));
        assertTrue(ledger.boxUuids().isEmpty());
        assertEquals(1, ledger.boxUuidHistory().size());
        assertEquals(
                "DEREGISTERED_FOR_ORDER",
                ledger.boxUuidHistory().get(0).getAsJsonObject().get("reason").getAsString());
        // The same box found again in a chest keeps its UUID: historical UUIDs are registered again.
        ledger.accept(indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0}));
        assertEquals(1, ledger.boxUuids().size());
        assertEquals(
                id, ledger.boxUuids().get(0).getAsJsonObject().get("source").getAsString());
        assertEquals(
                "REGISTERED",
                ledger.boxIdentities(id)
                        .get(0)
                        .getAsJsonObject()
                        .get("identityStatus")
                        .getAsString());
        assertEquals(1, ledger.boxUuidHistory().size());
    }

    @Test
    void movedBoxFollowsItsUuidToTheNewChest() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0}));
        assertEquals(1, ledger.boxUuids().size());
        // Same box observed at another chest while the old chest, re-read fresh, no longer shows it.
        var moved = indexes(ledger, new String[] {null, "WeSort-" + V4}, new int[] {0, 1});
        ledger.accept(moved);
        assertEquals(1, ledger.boxUuids().size());
        var row = ledger.boxUuids().get(0).getAsJsonObject();
        String movedId = moved.getAsJsonArray("physicalSources")
                .get(1)
                .getAsJsonObject()
                .get("id")
                .getAsString();
        assertEquals(movedId, row.get("source").getAsString());
        assertTrue(ledger.boxUuidHistory().isEmpty());
    }

    @Test
    void boxGoneFromEveryChestIsDeregisteredAsVanished() throws Exception {
        var ledger = new StockLedger(data);
        var first = indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {0});
        ledger.accept(first);
        assertEquals(1, ledger.boxUuids().size());
        String expectedSource =
                ledger.boxUuids().get(0).getAsJsonObject().get("source").getAsString();
        String expectedPath =
                ledger.boxUuids().get(0).getAsJsonObject().get("path").getAsString();
        // The same chest re-read fresh holding only an unnamed box: the UUID box left, deregistered.
        ledger.accept(indexes(ledger, new String[] {null}, new int[] {0}));
        assertTrue(ledger.boxUuids().isEmpty());
        assertEquals(1, ledger.boxUuidHistory().size());
        var history = ledger.boxUuidHistory().get(0).getAsJsonObject();
        assertEquals("VANISHED", history.get("reason").getAsString());
        // #67: the history row must point at the real source/path (verified slot
        // revision), never at the UUID or source-id in the wrong columns.
        assertEquals(V4, history.get("uuid").getAsString());
        assertEquals(expectedSource, history.get("source").getAsString());
        assertEquals(expectedPath, history.get("path").getAsString());
        assertEquals(
                first.getAsJsonArray("physicalSources")
                        .get(0)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString(),
                history.get("source").getAsString());
        // ...and registered again if the same box shows up again with its name intact.
        ledger.accept(indexes(ledger, new String[] {"WeSort-" + V4}, new int[] {1}));
        assertEquals(1, ledger.boxUuids().size());
    }

    @Test
    void foreignAndMissingNamesAreInvalidOrUnknown() throws Exception {
        var ledger = new StockLedger(data);
        // One box named "Beställning" (invalid), one unnamed box (unknown) in the same snapshot.
        var unnamed = new PhysicalSlots.Item(
                "minecraft:shulker_box",
                "minecraft:shulker_box",
                1,
                "{}",
                PhysicalSlots.Kind.SHULKER,
                27,
                List.of(),
                "kit2");
        var snapshot = new PhysicalSlots.Snapshot(
                27,
                List.of(
                        new PhysicalSlots.Slot(
                                0, 1, frame("Beställning").slots().getFirst().item()),
                        new PhysicalSlots.Slot(1, 1, unnamed)));
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
        source.addProperty("sequence", 1);
        source.addProperty("epoch", ledger.epoch());
        source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", System.currentTimeMillis());
        source.addProperty("fingerprint", snapshot.fingerprint());
        source.addProperty("snbt", snapshot.snbt());
        source.add("items", amounts(snapshot.totals()));
        source.add("shulkers", amounts(snapshot.shulkers()));
        var sources = new JsonArray();
        sources.add(source);
        root.add("physicalSources", sources);
        root.addProperty("physicalVersion", 3);
        ledger.accept(root);
        var identities = new TreeMap<>(Map.of("0", "", "1", ""));
        for (var b : ledger.boxIdentities(identity.id()))
            identities.put(
                    b.getAsJsonObject().get("path").getAsString(),
                    b.getAsJsonObject().get("identityStatus").getAsString());
        assertEquals("INVALID", identities.get("0"));
        assertEquals("UNKNOWN", identities.get("1"));
    }
}

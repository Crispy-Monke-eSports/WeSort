package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoxUuidRegistryTest {
    @TempDir Path data;
    private static final String V4 = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OTHER = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private static String namedComponents(String name) {
        return "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"" + name + "\\\"\"}}}";
    }

    private static PhysicalSlots.Snapshot frame(String boxName) {
        var diamond = new PhysicalSlots.Item("minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var box = new PhysicalSlots.Item("minecraft:shulker_box", "minecraft:shulker_box", 1, namedComponents(boxName),
            PhysicalSlots.Kind.SHULKER, 27, List.of(new PhysicalSlots.Slot(26, 32, diamond)), "kit");
        return new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box)));
    }

    private static JsonArray amounts(Map<String, Long> counts) {
        JsonArray out = new JsonArray();
        counts.forEach((key, count) -> {
            var row = new JsonObject(); row.addProperty("id", "kit".equals(key) ? "minecraft:shulker_box" : key);
            row.addProperty("key", key); row.addProperty("count", count); out.add(row);
        });
        return out;
    }

    private JsonObject indexes(StockLedger ledger, String[] names, int[] xs) throws Exception {
        var root = new JsonObject(); root.addProperty("version", 1); root.addProperty("receivedAt", System.currentTimeMillis());
        root.addProperty("server", "s"); root.addProperty("stash", "a");
        var writer = new PhysicalSlots.Writer(); var sources = new JsonArray();
        for (int i = 0; i < names.length; i++) {
            var frame = frame(names[i]);
            var identity = new ContainerIdentity("s", "minecraft:overworld", List.of(new ContainerIdentity.Position(xs[i], 64, 0)));
            var source = new JsonObject();
            source.addProperty("id", identity.id()); source.addProperty("dimension", "minecraft:overworld");
            var parts = new JsonArray(); var pos = new JsonArray(); pos.add(xs[i]); pos.add(64); pos.add(0); parts.add(pos); source.add("parts", parts);
            source.addProperty("kind", "minecraft:barrel"); source.addProperty("size", 27); source.addProperty("state", "FRESH");
            source.addProperty("session", "00000000-0000-0000-0000-000000000001"); source.addProperty("sequence", i + 1);
            source.addProperty("epoch", ledger.epoch()); source.addProperty("baseRevision", ledger.revision());
            source.addProperty("observedAt", System.currentTimeMillis()); source.addProperty("fingerprint", frame.fingerprint());
            source.add("slots", writer.slots(frame.slots()));
            source.add("items", amounts(frame.totals())); source.add("shulkers", amounts(frame.shulkers()));
            sources.add(source);
        }
        root.add("items", new JsonArray()); root.add("shulkers", new JsonArray());
        root.add("physicalSources", sources); root.addProperty("physicalVersion", 2); root.add("slotVariants", writer.variants());
        return root;
    }

    private static String sourceId(JsonObject index) { return index.getAsJsonArray("physicalSources").get(0).getAsJsonObject().get("id").getAsString(); }

    @Test void registersAnObservedBoxUuidAndReportsIdentity() throws Exception {
        var ledger = new StockLedger(data);
        var index = indexes(ledger, new String[]{"WeSort-" + V4}, new int[]{0});
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
        assertEquals("REGISTERED", identities.get(0).getAsJsonObject().get("identityStatus").getAsString());
        assertEquals(V4, identities.get(0).getAsJsonObject().get("boxUUID").getAsString());
        assertTrue(identities.get(0).getAsJsonObject().get("hasUUID").getAsBoolean());
    }

    @Test void duplicateUuidAtAnotherLocationIsDetectedButNeverReregistered() throws Exception {
        var ledger = new StockLedger(data);
        var both = indexes(ledger, new String[]{"WeSort-" + V4, "WeSort-" + V4}, new int[]{0, 1});
        ledger.accept(both);
        assertEquals(1, ledger.boxUuids().size());
        var ids = new ArrayList<String>();
        for (var s : ledger.sources()) ids.add(s.value().get("id").getAsString());
        var statuses = new ArrayList<String>();
        for (var id : ids) ledger.boxIdentities(id).forEach(b -> statuses.add(b.getAsJsonObject().get("identityStatus").getAsString()));
        assertEquals(List.of("DUPLICATE", "REGISTERED"), statuses.stream().sorted().toList());
    }

    @Test void deregisteredUuidMovesToHistoryAndIsNeverReused() throws Exception {
        var ledger = new StockLedger(data);
        var index = indexes(ledger, new String[]{"WeSort-" + V4}, new int[]{0});
        ledger.accept(index);
        String id = sourceId(index);
        ledger.deregisterBoxUuid("s", V4, "DEREGISTERED_FOR_ORDER");
        assertTrue(ledger.boxUuids().isEmpty());
        assertEquals(1, ledger.boxUuidHistory().size());
        assertEquals("DEREGISTERED_FOR_ORDER", ledger.boxUuidHistory().get(0).getAsJsonObject().get("reason").getAsString());
        // The same UUID observed again is a reused historical UUID, never active.
        assertEquals("DUPLICATE", ledger.boxIdentities(id).get(0).getAsJsonObject().get("identityStatus").getAsString());
    }

    @Test void foreignAndMissingNamesAreInvalidOrUnknown() throws Exception {
        var ledger = new StockLedger(data);
        // One box named "Beställning" (invalid), one unnamed box (unknown) in the same snapshot.
        var unnamed = new PhysicalSlots.Item("minecraft:shulker_box", "minecraft:shulker_box", 1, "{}", PhysicalSlots.Kind.SHULKER, 27, List.of(), "kit2");
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(
            new PhysicalSlots.Slot(0, 1, frame("Beställning").slots().getFirst().item()),
            new PhysicalSlots.Slot(1, 1, unnamed)));
        var root = new JsonObject(); root.addProperty("version", 1); root.addProperty("server", "s"); root.addProperty("stash", "a");
        root.add("items", new JsonArray()); root.add("shulkers", new JsonArray());
        var identity = new ContainerIdentity("s", "minecraft:overworld", List.of(new ContainerIdentity.Position(0, 64, 0)));
        var source = new JsonObject(); source.addProperty("id", identity.id()); source.addProperty("dimension", "minecraft:overworld");
        var parts = new JsonArray(); var pos = new JsonArray(); pos.add(0); pos.add(64); pos.add(0); parts.add(pos); source.add("parts", parts);
        source.addProperty("kind", "minecraft:barrel"); source.addProperty("size", 27); source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001"); source.addProperty("sequence", 1);
        source.addProperty("epoch", ledger.epoch()); source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", System.currentTimeMillis()); source.addProperty("fingerprint", snapshot.fingerprint());
        var writer = new PhysicalSlots.Writer(); source.add("slots", writer.slots(snapshot.slots()));
        source.add("items", amounts(snapshot.totals())); source.add("shulkers", amounts(snapshot.shulkers()));
        var sources = new JsonArray(); sources.add(source);
        root.add("physicalSources", sources); root.addProperty("physicalVersion", 2); root.add("slotVariants", writer.variants());
        ledger.accept(root);
        var identities = new TreeMap<>(Map.of("0", "", "1", ""));
        for (var b : ledger.boxIdentities(identity.id())) identities.put(b.getAsJsonObject().get("path").getAsString(), b.getAsJsonObject().get("identityStatus").getAsString());
        assertEquals("INVALID", identities.get("0"));
        assertEquals("UNKNOWN", identities.get("1"));
    }
}

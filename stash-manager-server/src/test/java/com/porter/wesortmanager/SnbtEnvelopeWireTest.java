package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.SnapshotEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire tests for the #227 dual-carry envelope ({@code physicalVersion:4}):
 * a valid v4 observation is accepted with exactly the v3 persistence
 * authority, and every envelope mismatch fails closed with no partial
 * commit.
 */
class SnbtEnvelopeWireTest {
    @TempDir
    Path data;

    static PhysicalSlots.Snapshot frame() {
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

    static JsonObject baseRoot() {
        return JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":7}],"shulkers":[]}
                """).getAsJsonObject();
    }

    /** A fully valid v4 observation, shaped exactly like the 26.3 capture output. */
    static JsonObject validV4(StockLedger ledger) throws Exception {
        return StockFixtures.physicalV4(baseRoot(), ledger.epoch(), ledger.revision(), 1, 0, frame(),
                SnapshotEnvelope.SourceKind.BARREL);
    }

    static JsonObject source(JsonObject root) {
        return root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }

    StockLedger freshLedger() throws Exception {
        return new StockLedger(Files.createTempDirectory(data, "v4-reject"));
    }

    void assertRejected(JsonObject root) throws Exception {
        var ledger = freshLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(root));
        assertEquals(0, ledger.revision());
    }

    @Test
    void v4WireShapeMirrorsCapture() throws Exception {
        var ledger = new StockLedger(data);
        var root = validV4(ledger);
        assertEquals(4, root.get("physicalVersion").getAsInt());
        var src = source(root);
        assertTrue(src.get("envelope").isJsonObject());
        var env = src.getAsJsonObject("envelope");
        assertEquals(1, env.get("wireVersion").getAsInt());
        assertEquals("BARREL", env.get("kind").getAsString());
        assertEquals(27, env.get("capacity").getAsInt());
        assertEquals(64, env.get("snapshotDigest").getAsString().length());
        var prov = env.getAsJsonObject("provenance");
        assertEquals("s", prov.get("server").getAsString());
        assertEquals(src.get("dimension").getAsString(), prov.get("dimension").getAsString());
        assertEquals(src.get("session").getAsString(), prov.get("session").getAsString());
        assertEquals(src.get("sequence").getAsLong(), prov.get("sequence").getAsLong());
        assertEquals(src.get("observedAt").getAsLong(), prov.get("observedAt").getAsLong());
        assertEquals("26.3", prov.get("registryContext").getAsString());
    }

    @Test
    void nonFreshV4CarriesNullEnvelopeMirroringSnbt() throws Exception {
        var ledger = new StockLedger(data);
        var root = validV4(ledger);
        var src = source(root);
        src.addProperty("state", "STALE");
        src.add("snbt", JsonNull.INSTANCE);
        src.add("envelope", JsonNull.INSTANCE);
        assertTrue(src.get("snbt").isJsonNull());
        assertTrue(src.get("envelope").isJsonNull());
    }

    @Test
    void validV4AcceptedWithSameAuthorityAsV3() throws Exception {
        var frame = frame();
        var v3dir = Files.createTempDirectory(data, "v3");
        var v4dir = Files.createTempDirectory(data, "v4");
        var v3ledger = new StockLedger(v3dir);
        var v3root = StockFixtures.physical(baseRoot(), v3ledger.epoch(), v3ledger.revision(), 1, 0);
        v3root.addProperty("physicalVersion", 3);
        source(v3root).addProperty("fingerprint", frame.fingerprint());
        source(v3root).addProperty("snbt", frame.snbt());
        v3ledger.accept(v3root);
        var v4ledger = new StockLedger(v4dir);
        v4ledger.accept(validV4(v4ledger));
        assertEquals(1, v3ledger.revision());
        assertEquals(1, v4ledger.revision());
        String id = source(v3root).get("id").getAsString();
        // The persisted authority is identical down both paths.
        assertEquals(v3ledger.snapshot(id, frame.fingerprint()), v4ledger.snapshot(id, frame.fingerprint()));
        assertEquals(v3ledger.physicalSlots(id).toString(), v4ledger.physicalSlots(id).toString());
        assertEquals(frame.snbt(),
                PhysicalSlots.Snapshot.fromSnbt(frame.snbt()).snbt());
        // The envelope is wire-only: stripped from stored sources and indexes.
        for (var s : v4ledger.sources()) {
            assertFalse(s.value().has("envelope"));
            assertFalse(s.value().has("snbt"));
        }
        for (var index : v4ledger.indexes()) {
            for (var value : index.getAsJsonArray("physicalSources"))
                assertFalse(value.getAsJsonObject().has("envelope"));
        }
    }

    @Test
    void storedIndexBytesCarryNoEnvelope() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        var ledger = store.stock();
        store.putIndex(validV4(ledger));
        var served = JsonParser.parseString(
                new String(store.index("s", "a").bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (var value : served.getAsJsonArray("physicalSources"))
            assertFalse(value.getAsJsonObject().has("envelope"));
    }

    @Test
    void rejectMatrixLeavesRevisionAtZero() throws Exception {
        var ledger = new StockLedger(data);
        var valid = validV4(ledger);
        // Bad versions are rejected on the existing unsupported-version path.
        for (long bad : new long[] {0, 2, 5}) {
            var copy = valid.deepCopy();
            copy.addProperty("physicalVersion", bad);
            assertThrows(IllegalArgumentException.class, () -> ledger.accept(copy));
        }
        // SNBT observations cannot carry a variant dictionary, on v4 either.
        var variants = valid.deepCopy();
        variants.add("slotVariants", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(variants));
        // Legacy slot rows are never valid on the SNBT paths.
        var slots = valid.deepCopy();
        source(slots).add("slots", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(slots));
        // The envelope object is required exactly for FRESH sources.
        var missing = valid.deepCopy();
        source(missing).remove("envelope");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(missing));
        var nullFresh = valid.deepCopy();
        source(nullFresh).add("envelope", JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(nullFresh));
        var staleCarrying = valid.deepCopy();
        source(staleCarrying).addProperty("state", "STALE");
        source(staleCarrying).add("snbt", JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(staleCarrying));
        // Non-canonical item bytes fail closed.
        var nonCanonical = valid.deepCopy();
        wireSlot(nonCanonical).addProperty("itemSnbt", "{\"id\":\"minecraft:diamond\",\"components\":{}}");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(nonCanonical));
        // Empty slots are absent entries, never air stacks.
        var air = valid.deepCopy();
        wireSlot(air).addProperty("itemSnbt", "{\"components\":{},\"id\":\"minecraft:air\"}");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(air));
        // Counts are observed native counts in 1..99.
        var zero = valid.deepCopy();
        wireSlot(zero).addProperty("count", 0);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(zero));
        var hundred = valid.deepCopy();
        wireSlot(hundred).addProperty("count", 100);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(hundred));
        // A barrel observation cannot arrive in a single-chest envelope.
        var kindMismatch = valid.deepCopy();
        var single = SnapshotEnvelope.fromPhysicalSnapshot(
                SnapshotEnvelope.SourceKind.CHEST_SINGLE, frame(), Map.of(), provenanceOf(valid));
        source(kindMismatch).add("envelope", single.toJson());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(kindMismatch));
        // An unknown source kind fails closed.
        var unknownKind = valid.deepCopy();
        source(unknownKind).getAsJsonObject("envelope").addProperty("kind", "BARREL_X");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(unknownKind));
        // The envelope capacity must equal the physical size.
        var capacity = valid.deepCopy();
        var grown = new SnapshotEnvelope(
                SnapshotEnvelope.SourceKind.BARREL, 28, parsedEnvelope(valid).slots(), Map.of(),
                parsedEnvelope(valid).provenance());
        source(capacity).add("envelope", grown.toJson());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(capacity));
        // Provenance must equal the source proof field by field.
        for (String field : List.of("server", "dimension", "session")) {
            var tampered = valid.deepCopy();
            source(tampered).getAsJsonObject("envelope").getAsJsonObject("provenance")
                    .addProperty(field, "elsewhere");
            assertThrows(IllegalArgumentException.class, () -> ledger.accept(tampered), field);
        }
        var seq = valid.deepCopy();
        source(seq).getAsJsonObject("envelope").getAsJsonObject("provenance").addProperty("sequence", 999);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(seq));
        var at = valid.deepCopy();
        source(at).getAsJsonObject("envelope").getAsJsonObject("provenance").addProperty("observedAt", 1);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(at));
        // Only the pinned registry context is understood.
        var ctx = valid.deepCopy();
        var foreign = SnapshotEnvelope.fromPhysicalSnapshot(
                SnapshotEnvelope.SourceKind.BARREL, frame(), Map.of(),
                new SnapshotEnvelope.Provenance(
                        "s", "minecraft:overworld",
                        source(valid).get("session").getAsString(),
                        source(valid).get("sequence").getAsLong(),
                        source(valid).get("observedAt").getAsLong(), "99.9"));
        source(ctx).add("envelope", foreign.toJson());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(ctx));
        // The envelope must agree with the SNBT snapshot it travels with.
        var disagree = valid.deepCopy();
        var other = new PhysicalSlots.Snapshot(
                27,
                List.of(new PhysicalSlots.Slot(
                        0, 3,
                        new PhysicalSlots.Item("minecraft:iron_ingot", "minecraft:iron_ingot", 64, "{}",
                                PhysicalSlots.Kind.ITEM, 0, List.of(), ""))));
        var otherEnvelope = SnapshotEnvelope.fromPhysicalSnapshot(
                SnapshotEnvelope.SourceKind.BARREL, other, Map.of(), provenanceOf(valid));
        source(disagree).add("envelope", otherEnvelope.toJson());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(disagree));
        // Nothing above committed anything.
        assertEquals(0, ledger.revision());
    }

    private static JsonObject wireSlot(JsonObject root) {
        return source(root).getAsJsonObject("envelope").getAsJsonArray("slots").get(0).getAsJsonObject();
    }

    private static SnapshotEnvelope parsedEnvelope(JsonObject root) {
        return SnapshotEnvelope.fromJson(source(root).getAsJsonObject("envelope"));
    }

    private static SnapshotEnvelope.Provenance provenanceOf(JsonObject root) {
        var prov = source(root).getAsJsonObject("envelope").getAsJsonObject("provenance");
        return new SnapshotEnvelope.Provenance(
                prov.get("server").getAsString(), prov.get("dimension").getAsString(),
                prov.get("session").getAsString(), prov.get("sequence").getAsLong(),
                prov.get("observedAt").getAsLong(), prov.get("registryContext").getAsString());
    }

    @Test
    void doubleChestEnvelopeRequiresFullMetaAndMatchingParts() throws Exception {
        var ledger = new StockLedger(data);
        ledger.accept(doubleChestV4(ledger));
        assertEquals(1, ledger.revision());
        // Dropping one meta field fails the wire, not the commit.
        var noMeta = doubleChestV4(freshLedger());
        source(noMeta).getAsJsonObject("envelope").getAsJsonObject("storageMeta").remove("posB");
        assertRejected(noMeta);
        // Positions that do not match the observed parts fail the cross-check.
        var moved = doubleChestV4(freshLedger());
        var meta = source(moved).getAsJsonObject("envelope").getAsJsonObject("storageMeta");
        meta.addProperty("posA", "9,64,9");
        meta.addProperty("posB", "10,64,9");
        assertRejected(moved);
    }

    static JsonObject doubleChestV4(StockLedger ledger) throws Exception {
        var frame = new PhysicalSlots.Snapshot(
                54,
                List.of(new PhysicalSlots.Slot(
                        0, 3,
                        new PhysicalSlots.Item("minecraft:diamond", "minecraft:diamond", 64, "{}",
                                PhysicalSlots.Kind.ITEM, 0, List.of(), ""))));
        var root = JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":3}],"shulkers":[]}
                """).getAsJsonObject();
        var identity = new ContainerIdentity(
                "s", "minecraft:overworld",
                List.of(new ContainerIdentity.Position(5, 64, 0),
                        new ContainerIdentity.Position(6, 64, 0)));
        var source = new JsonObject();
        source.addProperty("id", identity.id());
        source.addProperty("dimension", "minecraft:overworld");
        var parts = new JsonArray();
        for (var part : identity.parts()) {
            var pos = new JsonArray();
            pos.add(part.x());
            pos.add(part.y());
            pos.add(part.z());
            parts.add(pos);
        }
        source.add("parts", parts);
        source.addProperty("kind", "minecraft:chest");
        source.addProperty("size", 54);
        source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001");
        source.addProperty("sequence", ledger.revision() + 1);
        source.addProperty("epoch", ledger.epoch());
        source.addProperty("baseRevision", ledger.revision());
        source.addProperty("observedAt", System.currentTimeMillis());
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        source.add("items", root.getAsJsonArray("items"));
        source.add("shulkers", new JsonArray());
        var meta = Map.of("facing", "north", "type", "left", "posA", "5,64,0", "posB", "6,64,0");
        var provenance = new SnapshotEnvelope.Provenance(
                "s", "minecraft:overworld", "00000000-0000-0000-0000-000000000001",
                StockLedger.number(source, "sequence"), StockLedger.number(source, "observedAt"), "26.3");
        source.add("envelope",
                SnapshotEnvelope.fromPhysicalSnapshot(
                        SnapshotEnvelope.SourceKind.CHEST_DOUBLE, frame, meta, provenance).toJson());
        var sources = new JsonArray();
        sources.add(source);
        root.add("physicalSources", sources);
        root.addProperty("physicalVersion", 4);
        return root;
    }
}

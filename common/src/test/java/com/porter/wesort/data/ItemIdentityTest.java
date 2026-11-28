package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import com.porter.wesort.snbt.SnbtCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemIdentityTest {
    @Test
    void baseRequiresNamespacedId() {
        assertThrows(IllegalArgumentException.class, () -> new ItemIdentity.BaseItemId("stone", "26.3"));
        assertEquals("minecraft:stone", new ItemIdentity.BaseItemId("minecraft:stone", "26.3").id());
    }

    @Test
    void exactVariantIsFullSha256WithNoTruncation() {
        ItemIdentity.BaseItemId base = new ItemIdentity.BaseItemId("minecraft:diamond_sword", "26.3");
        SnbtCodec.Tag components = SnbtCodec.parse("{Damage:7,Enchantments:[{id:\"minecraft:unbreaking\",lvl:2s}]}");
        ItemIdentity.ExactVariantId v = ItemIdentity.ExactVariantId.of(base, components);
        assertEquals(64, v.digest().length());
        // Same id, different components -> different variant.
        SnbtCodec.Tag other = SnbtCodec.parse("{Damage:8}");
        ItemIdentity.ExactVariantId v2 = ItemIdentity.ExactVariantId.of(base, other);
        assertNotEquals(v.digest(), v2.digest());
        assertNotEquals(v, v2);
    }

    @Test
    void exactVariantRejectsNonCanonicalAndMismatchedDigest() {
        ItemIdentity.BaseItemId base = new ItemIdentity.BaseItemId("minecraft:stone", "26.3");
        assertThrows(
                IllegalArgumentException.class, () -> new ItemIdentity.ExactVariantId(base, "{b:1}", "0".repeat(64)));
        SnbtCodec.Tag c = SnbtCodec.parse("{a:1}");
        String canonical = SnbtCodec.write(c);
        String digest = SnbtCodec.contentDigest(base.id() + "\n" + canonical);
        assertDoesNotThrow(() -> new ItemIdentity.ExactVariantId(base, canonical, digest));
        assertThrows(
                IllegalArgumentException.class, () -> new ItemIdentity.ExactVariantId(base, canonical, "0".repeat(64)));
    }

    @Test
    void catalogGroupIsPolicyScoped() {
        ItemIdentity.CatalogGroupId g = new ItemIdentity.CatalogGroupId("tier", "64-stack");
        assertEquals("tier:64-stack", g.toString());
        assertNotEquals(g, new ItemIdentity.CatalogGroupId("bulk", "64-stack"));
    }

    @Test
    void snapshotEnvelopeSeparatesSources() {
        ItemIdentity.BaseItemId base = new ItemIdentity.BaseItemId("minecraft:diamond", "26.3");
        SnbtCodec.Tag empty = new SnbtCodec.TCompound(Map.of());
        String item = SnbtCodec.write(
                new SnbtCodec.TCompound(Map.of("id", new SnbtCodec.TString(base.id()), "components", empty)));
        SnapshotEnvelope.Provenance prov =
                new SnapshotEnvelope.Provenance("6b6t.org", "minecraft:overworld", "s1", 7L, 123L, "26.3");
        SnapshotEnvelope env = new SnapshotEnvelope(
                SnapshotEnvelope.SourceKind.CHEST_SINGLE,
                27,
                List.of(new SnapshotEnvelope.SlotEntry(0, item, 64)),
                Map.of(),
                prov);
        assertEquals(64, env.snapshotDigest().length());
        assertEquals(1, env.derivedTotals().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnapshotEnvelope(
                        SnapshotEnvelope.SourceKind.CHEST_SINGLE,
                        27,
                        List.of(new SnapshotEnvelope.SlotEntry(27, item, 1)),
                        Map.of(),
                        prov));
    }

    @Test
    void snapshotEnvelopeRejectsAirAndRequiresDoubleChestMeta() {
        SnapshotEnvelope.Provenance prov =
                new SnapshotEnvelope.Provenance("6b6t.org", "minecraft:overworld", "s1", 7L, 123L, "26.3");
        String air = SnbtCodec.write(new SnbtCodec.TCompound(
                Map.of("id", new SnbtCodec.TString("minecraft:air"), "components", new SnbtCodec.TCompound(Map.of()))));
        // Empty slots are absent entries, never fake air stacks.
        assertThrows(IllegalArgumentException.class, () -> new SnapshotEnvelope.SlotEntry(0, air, 1));
        String item = SnbtCodec.write(new SnbtCodec.TCompound(Map.of(
                "id", new SnbtCodec.TString("minecraft:diamond"), "components", new SnbtCodec.TCompound(Map.of()))));
        Map<String, String> meta = Map.of("facing", "north", "type", "left", "posA", "1,2,3", "posB", "2,2,3");
        // Double chests are one logical 54-slot envelope with facing/type/both positions.
        assertDoesNotThrow(() -> new SnapshotEnvelope(
                SnapshotEnvelope.SourceKind.CHEST_DOUBLE,
                54,
                List.of(new SnapshotEnvelope.SlotEntry(0, item, 1)),
                meta,
                prov));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnapshotEnvelope(
                        SnapshotEnvelope.SourceKind.CHEST_DOUBLE, 54, List.of(), Map.of("facing", "north"), prov));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SnapshotEnvelope(SnapshotEnvelope.SourceKind.CHEST_DOUBLE, 27, List.of(), meta, prov));
    }

    private static SnapshotEnvelope.Provenance prov() {
        return new SnapshotEnvelope.Provenance("6b6t.org", "minecraft:overworld", "s1", 7L, 123L, "26.3");
    }

    private static SnapshotEnvelope wireEnvelope() {
        PhysicalSlots.Item item = new PhysicalSlots.Item(
                "minecraft:diamond", "diamond", 64, "{Damage:7}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        PhysicalSlots.Snapshot snap = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(4, 5, item)));
        return SnapshotEnvelope.fromPhysicalSnapshot(SnapshotEnvelope.SourceKind.CHEST_SINGLE, snap, Map.of(), prov());
    }

    @Test
    void envelopeWireRoundTripIsCanonicalOnly() {
        SnapshotEnvelope env = wireEnvelope();
        String wire = env.toJson().toString();
        // The wire re-emits canonical bytes: parse(write(x)) is a fixed point.
        assertEquals(wire, SnapshotEnvelope.fromJson(wire).toJson().toString());
        assertEquals(env.snapshotDigest(), SnapshotEnvelope.fromJson(wire).snapshotDigest());
        // Slot order on the wire is insignificant: the digest sorts by slot.
        var shuffled = env.toJson();
        var slots = shuffled.getAsJsonArray("slots").deepCopy();
        var reversed = new com.google.gson.JsonArray();
        for (int i = slots.size() - 1; i >= 0; i--) reversed.add(slots.get(i));
        shuffled.add("slots", reversed);
        assertEquals(env.snapshotDigest(), SnapshotEnvelope.fromJson(shuffled.toString()).snapshotDigest());
    }

    @Test
    void envelopeWireRejectsUnknownFields() {
        var tampered = wireEnvelope().toJson();
        tampered.addProperty("extra", 1);
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(tampered.toString()));
        var slots = wireEnvelope().toJson().getAsJsonArray("slots");
        var row = slots.get(0).getAsJsonObject().deepCopy();
        row.addProperty("extra", "x");
        var only = new com.google.gson.JsonArray();
        only.add(row);
        var bad = wireEnvelope().toJson();
        bad.add("slots", only);
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(bad.toString()));
        var badProv = wireEnvelope().toJson();
        badProv.getAsJsonObject("provenance").addProperty("witness", "x");
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(badProv.toString()));
    }

    @Test
    void envelopeWireRejectsDuplicateKeys() {
        String wire = wireEnvelope().toJson().toString();
        // A duplicated top-level field fails closed instead of last-wins.
        String dupTop = wire.replaceFirst("\\{", "{\"capacity\":27,");
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(dupTop));
        // A duplicated slot key fails the same way, including unicode-evaded spelling.
        String dupSlot = wire.replaceFirst("\"slot\":", "\"slot\":4,\"\\u0073lot\":");
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(dupSlot));
        // Structure inside strings never counts as keys.
        assertDoesNotThrow(() -> SnapshotEnvelope.fromJson(wire));
    }

    @Test
    void envelopeWireRejectsNonCanonicalItems() {
        var bad = wireEnvelope().toJson();
        var slots = new com.google.gson.JsonArray();
        var row = new com.google.gson.JsonObject();
        row.addProperty("slot", 0);
        // Unquoted keys parse but are not canonical bytes.
        row.addProperty("itemSnbt", "{id:\"minecraft:stone\",components:{}}");
        row.addProperty("count", 1);
        slots.add(row);
        bad.add("slots", slots);
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(bad.toString()));
        // Unsorted keys are equally non-canonical.
        var badOrder = wireEnvelope().toJson();
        var slots2 = new com.google.gson.JsonArray();
        var row2 = new com.google.gson.JsonObject();
        row2.addProperty("slot", 0);
        row2.addProperty("itemSnbt", "{\"id\":\"minecraft:stone\",\"components\":{}}");
        row2.addProperty("count", 1);
        slots2.add(row2);
        badOrder.add("slots", slots2);
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(badOrder.toString()));
    }

    @Test
    void envelopeWireEnforcesDoubleChestMetaAndDigest() {
        PhysicalSlots.Item item = new PhysicalSlots.Item(
                "minecraft:diamond", "diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        PhysicalSlots.Snapshot snap = new PhysicalSlots.Snapshot(54, List.of(new PhysicalSlots.Slot(0, 1, item)));
        Map<String, String> meta = Map.of("facing", "north", "type", "left", "posA", "1,2,3", "posB", "2,2,3");
        SnapshotEnvelope env =
                SnapshotEnvelope.fromPhysicalSnapshot(SnapshotEnvelope.SourceKind.CHEST_DOUBLE, snap, meta, prov());
        assertDoesNotThrow(() -> SnapshotEnvelope.fromJson(env.toJson().toString()));
        var missing = env.toJson();
        missing.getAsJsonObject("storageMeta").remove("posB");
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(missing.toString()));
        var forged = env.toJson();
        forged.addProperty("snapshotDigest", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.fromJson(forged.toString()));
    }

    @Test
    void envelopeWireStabilityAndKindMapping() {
        // Same content always hashes the same way.
        assertEquals(wireEnvelope().snapshotDigest(), wireEnvelope().snapshotDigest());
        assertEquals(1, SnapshotEnvelope.WIRE_VERSION);
        assertEquals(SnapshotEnvelope.SourceKind.CHEST_SINGLE, SnapshotEnvelope.kindForBlock("minecraft:chest", 1));
        assertEquals(SnapshotEnvelope.SourceKind.CHEST_DOUBLE, SnapshotEnvelope.kindForBlock("minecraft:chest", 2));
        assertEquals(
                SnapshotEnvelope.SourceKind.CHEST_DOUBLE, SnapshotEnvelope.kindForBlock("minecraft:trapped_chest", 2));
        assertEquals(SnapshotEnvelope.SourceKind.BARREL, SnapshotEnvelope.kindForBlock("minecraft:barrel", 1));
        assertEquals(
                SnapshotEnvelope.SourceKind.SHULKER_PLACED,
                SnapshotEnvelope.kindForBlock("minecraft:blue_shulker_box", 1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.kindForBlock("minecraft:chest", 3));
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.kindForBlock("minecraft:furnace", 1));
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.kindForBlock("minecraft:barrel", 2));
        assertThrows(IllegalArgumentException.class, () -> SnapshotEnvelope.kindForBlock(null, 1));
    }

    @Test
    void slotEntryNormalizesToCanonicalSnbt() {
        SnapshotEnvelope.SlotEntry e =
                new SnapshotEnvelope.SlotEntry(3, "{id:\"minecraft:stone\",components:{a:1}}", 2);
        assertEquals("{\"components\":{\"a\":1},\"id\":\"minecraft:stone\"}", e.itemSnbt());
        // Same variant written differently merges in totals and digest.
        SnapshotEnvelope env = new SnapshotEnvelope(
                SnapshotEnvelope.SourceKind.CHEST_SINGLE,
                27,
                List.of(
                        new SnapshotEnvelope.SlotEntry(
                                1, " { \"id\" : \"minecraft:stone\" , \"components\" : {} } ", 1),
                        new SnapshotEnvelope.SlotEntry(0, "{\"id\":\"minecraft:stone\",\"components\":{}}", 2)),
                Map.of(),
                prov());
        assertEquals(1, env.derivedTotals().size());
        assertEquals(3, env.derivedTotals().values().iterator().next());
        assertEquals(env.canonicalSlotsSnbt(), SnbtCodec.canonicalize(env.canonicalSlotsSnbt()));
        SnapshotEnvelope canonicalOnly = new SnapshotEnvelope(
                SnapshotEnvelope.SourceKind.CHEST_SINGLE,
                27,
                List.of(
                        new SnapshotEnvelope.SlotEntry(0, "{\"components\":{},\"id\":\"minecraft:stone\"}", 2),
                        new SnapshotEnvelope.SlotEntry(1, "{\"components\":{},\"id\":\"minecraft:stone\"}", 1)),
                Map.of(),
                prov());
        assertEquals(canonicalOnly.snapshotDigest(), env.snapshotDigest());
    }

    @Test
    void fromPhysicalSnapshotBuildsCanonicalEnvelope() {
        PhysicalSlots.Item item = new PhysicalSlots.Item(
                "minecraft:diamond", "diamond", 64, "{Damage:7}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        PhysicalSlots.Snapshot snap = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(4, 5, item)));
        SnapshotEnvelope env =
                SnapshotEnvelope.fromPhysicalSnapshot(SnapshotEnvelope.SourceKind.CHEST_SINGLE, snap, Map.of(), prov());
        assertEquals(1, env.slots().size());
        assertEquals(4, env.slots().get(0).slot());
        assertEquals(5, env.slots().get(0).count());
        assertEquals(
                "{\"components\":{\"Damage\":7},\"id\":\"minecraft:diamond\"}",
                env.slots().get(0).itemSnbt());
        assertEquals(64, env.snapshotDigest().length());
        assertEquals(Map.of(env.slots().get(0).itemSnbt(), 5), env.derivedTotals());
    }

    @Test
    void fromPhysicalSnapshotEnforcesDoubleChestMeta() {
        PhysicalSlots.Item item = new PhysicalSlots.Item(
                "minecraft:diamond", "diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        PhysicalSlots.Snapshot snap = new PhysicalSlots.Snapshot(54, List.of(new PhysicalSlots.Slot(0, 1, item)));
        Map<String, String> meta = Map.of("facing", "north", "type", "left", "posA", "1,2,3", "posB", "2,2,3");
        assertDoesNotThrow(() ->
                SnapshotEnvelope.fromPhysicalSnapshot(SnapshotEnvelope.SourceKind.CHEST_DOUBLE, snap, meta, prov()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SnapshotEnvelope.fromPhysicalSnapshot(
                        SnapshotEnvelope.SourceKind.CHEST_DOUBLE, snap, Map.of("facing", "north"), prov()));
        assertThrows(
                IllegalArgumentException.class, () -> SnapshotEnvelope.fromPhysicalSnapshot(null, snap, meta, prov()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SnapshotEnvelope.fromPhysicalSnapshot(
                        SnapshotEnvelope.SourceKind.CHEST_SINGLE, null, Map.of(), prov()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SnapshotEnvelope.fromPhysicalSnapshot(
                        SnapshotEnvelope.SourceKind.CHEST_SINGLE, snap, Map.of(), null));
    }
}

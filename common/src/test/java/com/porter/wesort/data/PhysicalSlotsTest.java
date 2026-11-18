package com.porter.wesort.data;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalSlotsTest {
    private PhysicalSlots.Item diamond() { return new PhysicalSlots.Item("minecraft:diamond", "diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), ""); }
    private PhysicalSlots.Item box(int count) { return new PhysicalSlots.Item("minecraft:shulker_box", "box", 1, "{}", PhysicalSlots.Kind.SHULKER, 27, List.of(new PhysicalSlots.Slot(26, count, diamond())), "kit"); }
    @Test void identicalBoxesHaveSameContentAndDistinctLocationsWithoutDoubleCounting() {
        var box = box(32);
        var frame = new PhysicalSlots.Snapshot(54, List.of(new PhysicalSlots.Slot(0, 1, box), new PhysicalSlots.Slot(53, 1, box)));
        assertEquals(Map.of("box", 2L, "diamond", 64L), frame.totals());
        assertEquals(Map.of("kit", 2L), frame.shulkers());
        assertEquals(List.of("0", "0/26", "53", "53/26"), frame.located().stream().map(PhysicalSlots.Located::path).toList());
        assertEquals(frame.located().get(0).stack().fingerprint(), frame.located().get(2).stack().fingerprint());
        assertNotEquals(frame.located().get(0).resource("source"), frame.located().get(2).resource("source"));
    }
    @Test void dictionaryRoundtripPreservesSparseSlotsComponentsAndDeduplicates() {
        var frame = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(2, 1, box(16)), new PhysicalSlots.Slot(20, 1, box(16))));
        var writer = new PhysicalSlots.Writer(); var rows = writer.slots(frame.slots());
        assertEquals(2, writer.variants().size());
        var read = new PhysicalSlots.Reader(writer.variants()).snapshot(27, rows);
        assertEquals(frame.json(), read.json()); assertEquals(frame.fingerprint(), read.fingerprint());
    }
    @Test void changesToCountMetadataOrSlotInvalidateSnapshot() {
        var first = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box(32))));
        assertNotEquals(first.fingerprint(), new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box(31)))).fingerprint());
        assertNotEquals(first.fingerprint(), new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(1, 1, box(32)))).fingerprint());
        var named = new PhysicalSlots.Item("minecraft:diamond", "diamond", 64, "{\"name\":\"Exact\",\"damage\":{\"type\":\"int\",\"value\":3}}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        assertNotEquals(diamond().fingerprint(), named.fingerprint());
        var reordered = new PhysicalSlots.Item("minecraft:diamond", "diamond", 64, "{\"damage\":{\"value\":3,\"type\":\"int\"},\"name\":\"Exact\"}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        assertEquals(named.fingerprint(), reordered.fingerprint());
    }
    @Test void nestedContainerCopiesMultiplyOnce() {
        var bag = new PhysicalSlots.Item("minecraft:bundle", "bundle", 16, "{}", PhysicalSlots.Kind.BUNDLE, 1, List.of(new PhysicalSlots.Slot(0, 5, diamond())), "");
        var frame = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 3, bag)));
        assertEquals(Map.of("bundle", 3L, "diamond", 15L), frame.totals());
    }
    @Test void invalidSlotQuantityAndCycleFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Slot(0, 65, diamond()));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(27, 1, diamond()))));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, diamond()), new PhysicalSlots.Slot(0, 2, diamond()))));
        var variants = JsonParser.parseString("[{\"id\":\"minecraft:bundle\",\"key\":\"bag\",\"maxStack\":1,\"components\":\"{}\",\"kind\":\"BUNDLE\",\"size\":1,\"slots\":[[0,0,1]]}]").getAsJsonArray();
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Reader(variants).snapshot(27, JsonParser.parseString("[[0,0,1]]").getAsJsonArray()));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Reader(new JsonArray()).snapshot(27, JsonParser.parseString("[[0,4,1]]").getAsJsonArray()));
    }
    @Test void excessiveDepthRejectedEvenAfterDictionaryCacheHit() {
        var item = diamond();
        for (int i = 0; i < PhysicalSlots.MAX_DEPTH + 1; i++) item = new PhysicalSlots.Item("minecraft:bundle", "bag", 1, "{}", PhysicalSlots.Kind.BUNDLE, 1, List.of(new PhysicalSlots.Slot(0, 1, item)), "");
        var deep = item;
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, deep))));
        var writer = new PhysicalSlots.Writer(); var wire = writer.slots(List.of(new PhysicalSlots.Slot(0, 1, diamond()), new PhysicalSlots.Slot(1, 1, deep)));
        assertThrows(IllegalArgumentException.class, () -> new PhysicalSlots.Reader(writer.variants()).snapshot(27, wire));
    }
    private PhysicalSlots.Item namedShulker(String text) {
        var name = new JsonObject();
        name.addProperty("type", 8);
        name.addProperty("value", new JsonPrimitive(text).toString());
        var value = new JsonObject();
        value.add("minecraft:custom_name", name);
        var patch = new JsonObject();
        patch.addProperty("type", 10);
        patch.add("value", value);
        return new PhysicalSlots.Item("minecraft:shulker_box", "shulker", 1,
            patch.toString(), PhysicalSlots.Kind.SHULKER, 27, List.of(), "");
    }

    @Test void uuidIsStrictVisibleV4NameNotRegistrationProof() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        var box = namedShulker("WeSort-" + uuid);
        assertTrue(box.boxIdentity().hasUUID());
        assertEquals(uuid, box.boxIdentity().boxUUID());
        // A forgeable anvil label must NEVER be called registered without Manager provenance.
        assertEquals("UNKNOWN", box.boxIdentity().identityStatus());
        var row = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(1, 1, box)))
            .json().getAsJsonArray("positions").get(0).getAsJsonObject();
        assertTrue(row.get("hasUUID").getAsBoolean());
        assertEquals(uuid, row.get("boxUUID").getAsString());
        assertEquals("UNKNOWN", row.get("identityStatus").getAsString());
        assertEquals(box.fingerprint(), row.get("itemFingerprint").getAsString());
    }

    @Test void malformedLookalikeAndNonV4IdentifiersNeverClaimUUID() {
        String valid = "550e8400-e29b-41d4-a716-446655440000";
        for (String value : List.of(
            "WeSort-" + valid.toUpperCase(Locale.ROOT),
            "WeSort-550e8400-e29b-11d4-a716-446655440000",
            "WeSort-550e8400-e29b-41d4-7716-446655440000",
            "WeSort-" + valid + " ",
            "WeSort-" + valid + "more",
            "WeSort-\\u200e" + valid,
            "WeSort-\\u202e" + valid,
            "WeSort-" + valid.substring(0, 35),
            "WeSort-550e8400-e29b-41d4-a716-44665544000g")) {
            var claim = namedShulker(value).boxIdentity();
            assertFalse(claim.hasUUID(), value);
            assertNull(claim.boxUUID(), value);
        }
        assertEquals("UNREGISTERED", namedShulker("WeSort (1/4) - Byggmaterial").boxIdentity().identityStatus());
        assertEquals("UNREGISTERED", box(1).boxIdentity().identityStatus());
    }

    @Test void typedNameMustBeExactAndOnlyShulkersCanClaimIdentity() {
        String visible = "WeSort-550e8400-e29b-41d4-a716-446655440000";
        var box = namedShulker(visible);
        assertFalse(PhysicalSlots.boxIdentity("minecraft:diamond", PhysicalSlots.Kind.ITEM, box.components()).hasUUID());
        assertFalse(PhysicalSlots.boxIdentity("minecraft:diamond", PhysicalSlots.Kind.SHULKER, box.components()).hasUUID());
        assertFalse(PhysicalSlots.boxIdentity("minecraft:shulker_box", PhysicalSlots.Kind.SHULKER,
            "{\"minecraft:custom_name\":\"WeSort-550e8400-e29b-41d4-a716-446655440000\"}").hasUUID());
        assertEquals("INVALID", PhysicalSlots.boxIdentity("minecraft:shulker_box", PhysicalSlots.Kind.SHULKER,
            "{\"minecraft:custom_name\":{\"type\":8,\"value\":\"not-json\"}}").identityStatus());
        var styled = new JsonObject();
        styled.addProperty("text", visible);
        styled.addProperty("bold", true);
        var name = new JsonObject();
        name.addProperty("type", 8);
        name.addProperty("value", styled.toString());
        var fields = new JsonObject();
        fields.add("minecraft:custom_name", name);
        assertEquals("INVALID", PhysicalSlots.boxIdentity("minecraft:shulker_box", PhysicalSlots.Kind.SHULKER, fields.toString()).identityStatus());
    }

    @Test void nativeStructuredNbtAndPlainLiteralNamesBothSupportCanonicalUuid() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        var textTag = new JsonObject();
        textTag.addProperty("type", 8);
        textTag.addProperty("value", new JsonPrimitive("WeSort-" + uuid).toString());
        var payload = new JsonObject();
        payload.add("text", textTag);
        var component = new JsonObject();
        component.addProperty("type", 10);
        component.add("value", payload);
        var fields = new JsonObject();
        fields.add("minecraft:custom_name", component);
        var patch = new JsonObject();
        patch.addProperty("type", 10);
        patch.add("value", fields);
        assertEquals(uuid, PhysicalSlots.boxIdentity("minecraft:red_shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).boxUUID());
        // Every extra property is potentially visible styling/translation; never
        // infer a canonical UUID by stripping it away.
        var bold = new JsonObject();
        bold.addProperty("type", 1);
        bold.addProperty("value", "1b");
        payload.add("bold", bold);
        assertEquals("INVALID", PhysicalSlots.boxIdentity("minecraft:red_shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).identityStatus());
        payload.remove("bold");
        component.addProperty("type", 8);
        component.addProperty("value", new JsonPrimitive("WeSort-" + uuid).toString());
        assertTrue(PhysicalSlots.boxIdentity("minecraft:shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).hasUUID());
    }

    @Test void canonicalUuidInSingleQuotedVanillaSnbtIsRecognizedButRawClaimsAreNot() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        var component = new JsonObject();
        component.addProperty("text", "WeSort-" + uuid);
        var name = new JsonObject();
        name.addProperty("type", 8);
        name.addProperty("value", "'" + component + "'");
        var values = new JsonObject();
        values.add("minecraft:custom_name", name);
        var patch = new JsonObject();
        patch.addProperty("type", 10);
        patch.add("value", values);
        assertTrue(PhysicalSlots.boxIdentity("minecraft:shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).hasUUID());
        name.addProperty("value", component.toString());
        assertEquals("INVALID", PhysicalSlots.boxIdentity("minecraft:shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).identityStatus());
        name.addProperty("value", "'{" + "text:malformed}" + "'");
        assertFalse(PhysicalSlots.boxIdentity("minecraft:shulker_box",
            PhysicalSlots.Kind.SHULKER, patch.toString()).hasUUID());
    }

    @Test void duplicatedVisibleIdentifiersAreQuarantinedWithoutChangingTotals() {
        var first = namedShulker("WeSort-550e8400-e29b-41d4-a716-446655440000");
        var second = namedShulker("WeSort-550e8400-e29b-41d4-a716-446655440000");
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(
            new PhysicalSlots.Slot(0, 1, first), new PhysicalSlots.Slot(20, 1, second)));
        assertEquals(Map.of("shulker", 2L), snapshot.totals());
        var rows = snapshot.json().getAsJsonArray("positions");
        assertEquals("DUPLICATE", rows.get(0).getAsJsonObject().get("identityStatus").getAsString());
        assertEquals("DUPLICATE", rows.get(1).getAsJsonObject().get("identityStatus").getAsString());
        assertTrue(rows.get(0).getAsJsonObject().get("hasUUID").getAsBoolean());
        assertNotEquals(rows.get(0).getAsJsonObject().get("path").getAsString(),
            rows.get(1).getAsJsonObject().get("path").getAsString());
        // A singleton shulker carries only a syntactically valid claim, not a registry entry.
        var one = new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, first)));
        assertEquals("UNKNOWN", one.json().getAsJsonArray("positions").get(0)
            .getAsJsonObject().get("identityStatus").getAsString());
    }

    @Test void onlyExactVanillaColoredShulkerIdsMayClaimUUID() {
        String name = "WeSort-550e8400-e29b-41d4-a716-446655440000";
        String patch = namedShulker(name).components();
        for (String id : List.of("minecraft:diamond", "minecraft:not_shulker_box",
            "modded:shulker_box", "minecraft:shulker_box_hack", "minecraft:infinite_shulker_box")) {
            assertFalse(PhysicalSlots.boxIdentity(id, PhysicalSlots.Kind.SHULKER, patch).hasUUID(), id);
        }
        assertTrue(PhysicalSlots.boxIdentity("minecraft:blue_shulker_box", PhysicalSlots.Kind.SHULKER, patch).hasUUID());
        assertTrue(PhysicalSlots.boxIdentity("minecraft:shulker_box", PhysicalSlots.Kind.SHULKER, patch).hasUUID());
    }

    @Test void addingDerivedUUIDFieldsDoesNotChangeInventoryFingerprintOrCount() {
        var box = namedShulker("WeSort-550e8400-e29b-41d4-a716-446655440000");
        var snapshot = new PhysicalSlots.Snapshot(27, List.of(
            new PhysicalSlots.Slot(0, 1, box), new PhysicalSlots.Slot(5, 1, box)));
        var writer = new PhysicalSlots.Writer();
        var rows = writer.slots(snapshot.slots());
        var recovered = new PhysicalSlots.Reader(writer.variants()).snapshot(27, rows);
        assertEquals(snapshot.fingerprint(), recovered.fingerprint());
        assertEquals(snapshot.totals(), recovered.totals());
        assertEquals(2L, recovered.totals().get("shulker"));
        assertEquals(snapshot.json(), recovered.json());
        assertNotEquals(recovered.located().get(0).resource("a"), recovered.located().get(1).resource("a"));
    }

}

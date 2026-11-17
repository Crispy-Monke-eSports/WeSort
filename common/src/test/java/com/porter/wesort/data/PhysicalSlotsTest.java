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
}

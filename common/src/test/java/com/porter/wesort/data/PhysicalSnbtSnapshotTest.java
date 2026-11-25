package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicalSnbtSnapshotTest {
    private static PhysicalSlots.Item diamond() {
        return new PhysicalSlots.Item("minecraft:diamond", "diamond", 64,
                "{\"minecraft:custom_data\":{n:9007199254740993L,bytes:[B;1b,-1b]}}",
                PhysicalSlots.Kind.ITEM, 0, List.of(), "");
    }

    private static PhysicalSlots.Snapshot sample() {
        var child = new PhysicalSlots.Slot(26, 32, diamond());
        var box = new PhysicalSlots.Item("minecraft:purple_shulker_box", "box", 1,
                "{\"minecraft:custom_name\":\"WeSort-550e8400-e29b-41d4-a716-446655440000\"}",
                PhysicalSlots.Kind.SHULKER, 27, List.of(child), "group");
        return new PhysicalSlots.Snapshot(54, List.of(new PhysicalSlots.Slot(53, 1, box)));
    }

    @Test
    void canonicalSnapshotReconstructsExactTreeAndProjection() {
        var snapshot = sample();
        assertEquals(snapshot.snbt(), CanonicalSnbt.canonicalize(snapshot.snbt()));
        var read = PhysicalSlots.Snapshot.fromSnbt(snapshot.snbt());
        assertEquals(snapshot.json(), read.json());
        assertEquals(snapshot.fingerprint(), read.fingerprint());
        assertEquals(List.of("53", "53/26"), read.located().stream().map(PhysicalSlots.Located::path).toList());
        assertTrue(read.snbt().contains("9007199254740993L"));
        assertEquals(snapshot.json(), PhysicalSlots.Snapshot.readEnvelope(snapshot.envelope()).json());
    }

    @Test
    void rejectsAmbiguousOrTamperedEnvelope() {
        var original = sample();
        var bad = original.envelope();
        bad.addProperty("fingerprint", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> PhysicalSlots.Snapshot.readEnvelope(bad));
        var alternate = original.envelope();
        alternate.add("slots", new com.google.gson.JsonArray());
        assertThrows(IllegalArgumentException.class, () -> PhysicalSlots.Snapshot.readEnvelope(alternate));
        assertThrows(IllegalArgumentException.class, () -> PhysicalSlots.Snapshot.fromSnbt(" " + original.snbt()));
        assertThrows(IllegalArgumentException.class, () -> PhysicalSlots.Snapshot.fromSnbt(
                original.snbt().replace("\"schema\":2", "\"schema\":2b")));
    }

    @Test
    void knownEmptySnapshotHasCapacityAndNoFabricatedItems() {
        var empty = new PhysicalSlots.Snapshot(27, List.of());
        assertEquals(27, PhysicalSlots.Snapshot.fromSnbt(empty.snbt()).size());
        assertTrue(PhysicalSlots.Snapshot.fromSnbt(empty.snbt()).slots().isEmpty());
    }
}

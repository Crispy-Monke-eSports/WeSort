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
}

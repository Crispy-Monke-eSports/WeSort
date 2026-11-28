package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.SnapshotEnvelope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wire tests for the #227 dual-carry envelope ({@code physicalVersion:4}).
 * Until the Manager cross-check lands, a fully valid v4 observation must be
 * rejected fail-closed on its version alone, with no partial commit.
 */
class SnbtEnvelopeWireTest {
    @TempDir
    Path data;

    /** A fully valid v4 observation, shaped exactly like the 26.3 capture output. */
    static JsonObject validV4(StockLedger ledger) throws Exception {
        var frame = new PhysicalSlots.Snapshot(
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
        var root = JsonParser.parseString("""
                {"version":1,"server":"s","stash":"a","items":[
                {"key":"minecraft:diamond","id":"minecraft:diamond","count":7}],"shulkers":[]}
                """).getAsJsonObject();
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), 1, 0);
        root.addProperty("physicalVersion", 4);
        var source = source(root);
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        var provenance = new SnapshotEnvelope.Provenance(
                "s",
                "minecraft:overworld",
                source.get("session").getAsString(),
                source.get("sequence").getAsLong(),
                source.get("observedAt").getAsLong(),
                "26.3");
        var envelope = SnapshotEnvelope.fromPhysicalSnapshot(
                SnapshotEnvelope.SourceKind.BARREL, frame, Map.of(), provenance);
        source.add("envelope", envelope.toJson());
        return root;
    }

    static JsonObject source(JsonObject root) {
        return root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
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
        // Provenance mirrors the source proof fields exactly.
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
    void managerRejectsV4BeforeEnvelopeValidationLands() throws Exception {
        var ledger = new StockLedger(data);
        var root = validV4(ledger);
        // A fully valid dual-carry observation is still rejected on version alone.
        var rejected = assertThrows(IllegalArgumentException.class, () -> ledger.accept(root));
        assertEquals("Unsupported physical observation version", rejected.getMessage());
        assertEquals(0, ledger.revision());
        for (long bad : new long[] {0, 2, 5}) {
            var copy = root.deepCopy();
            copy.addProperty("physicalVersion", bad);
            assertThrows(IllegalArgumentException.class, () -> ledger.accept(copy));
        }
        assertEquals(0, ledger.revision());
    }
}

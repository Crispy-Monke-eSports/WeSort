package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.ContainerIdentity;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.data.SnapshotEnvelope;
import com.porter.wesort.snbt.SnbtCodec;
import java.util.*;

/** Synthetic protocol witnesses for auth/order unit tests; not evidence of physical Minecraft operations. */
final class StockFixtures {
    static JsonObject physical(JsonObject index, String epoch, long base, long sequence, int x) {
        JsonObject copy = index.deepCopy();
        String server = copy.get("server").getAsString();
        var identity =
                new ContainerIdentity(server, "minecraft:overworld", List.of(new ContainerIdentity.Position(x, 64, 0)));
        JsonObject source = new JsonObject();
        source.addProperty("id", identity.id());
        source.addProperty("dimension", "minecraft:overworld");
        JsonArray parts = new JsonArray(), pos = new JsonArray();
        pos.add(x);
        pos.add(64);
        pos.add(0);
        parts.add(pos);
        source.add("parts", parts);
        source.addProperty("kind", "minecraft:barrel");
        source.addProperty("size", 27);
        source.addProperty("state", "FRESH");
        source.addProperty("session", "00000000-0000-0000-0000-000000000001");
        source.addProperty("sequence", sequence);
        source.addProperty("epoch", epoch);
        source.addProperty("baseRevision", base);
        source.addProperty("observedAt", System.currentTimeMillis());
        source.addProperty(
                "fingerprint", ContainerIdentity.hash(copy.get("items").toString()));
        source.add("items", copy.getAsJsonArray("items"));
        source.add("shulkers", copy.has("shulkers") ? copy.get("shulkers") : new JsonArray());
        JsonArray sources = new JsonArray();
        sources.add(source);
        copy.add("physicalSources", sources);
        copy.addProperty("physicalVersion", 1);
        return copy;
    }

    /**
     * Dual-carry v4 observation (#227): every v1 field plus a canonical
     * {@code snbt} snapshot and its agreeing {@code envelope}. The
     * {@link #physical} default above stays v1 untouched.
     */
    static JsonObject physicalV4(
            JsonObject index,
            String epoch,
            long base,
            long sequence,
            int x,
            PhysicalSlots.Snapshot frame,
            SnapshotEnvelope.SourceKind kind) {
        JsonObject copy = physical(index, epoch, base, sequence, x);
        copy.addProperty("physicalVersion", 4);
        var source = copy.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        var provenance = new SnapshotEnvelope.Provenance(
                copy.get("server").getAsString(),
                source.get("dimension").getAsString(),
                source.get("session").getAsString(),
                StockLedger.number(source, "sequence"),
                StockLedger.number(source, "observedAt"),
                SnbtCodec.MINECRAFT_DATA);
        source.add(
                "envelope",
                SnapshotEnvelope.fromPhysicalSnapshot(kind, frame, Map.of(), provenance).toJson());
        return copy;
    }

    static void put(IndexStore store, JsonObject index) throws Exception {
        var ledger = store.stock();
        long revision = ledger.revision();
        store.putIndex(physical(
                index,
                ledger.epoch(),
                revision,
                revision + 1,
                index.get("stash").getAsString().hashCode()));
    }
}

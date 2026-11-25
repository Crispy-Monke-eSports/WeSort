package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnbtObservationTest {
    @TempDir
    Path data;

    private JsonObject observation(StockLedger ledger) throws Exception {
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
        root.addProperty("physicalVersion", 3);
        var source = source(root);
        source.addProperty("fingerprint", frame.fingerprint());
        source.addProperty("snbt", frame.snbt());
        return root;
    }

    private static JsonObject source(JsonObject root) {
        return root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
    }

    @Test
    void acceptsCanonicalSnbtAndRejectsAlternativeAuthority() throws Exception {
        var ledger = new StockLedger(data);
        var root = observation(ledger);
        var alternate = root.deepCopy();
        alternate.add("slotVariants", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(alternate));
        var wrong = root.deepCopy();
        source(wrong).addProperty("fingerprint", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(wrong));
        var corrupt = root.deepCopy();
        source(corrupt).addProperty("snbt", "{broken:");
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(corrupt));
        assertEquals(0, ledger.revision());
        ledger.accept(root);
        assertEquals(1, ledger.revision());
        var slots = ledger.physicalSlots(source(root).get("id").getAsString());
        assertEquals(1, slots.size());
        assertEquals("26", slots.getFirst().get("path").getAsString());
        assertTrue(slots.getFirst().get("components").getAsString().contains("9007199254740993L"));
    }
}

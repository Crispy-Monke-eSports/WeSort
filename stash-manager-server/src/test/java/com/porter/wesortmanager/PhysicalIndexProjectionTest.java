package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhysicalIndexProjectionTest {
    @TempDir
    Path data;

    private static JsonObject index(long count) {
        return JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":"
                        + count + "}]}")
                .getAsJsonObject();
    }

    @Test
    void committedPhysicalStockSurvivesBrokenOptionalJsonMirrorAndRestart() throws Exception {
        StockLedger ledger = new StockLedger(data);
        IndexStore store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        Path mirror = data.resolve("indexes").resolve(IndexStore.fileName("s", "a"));
        Files.createDirectory(mirror); // A directory cannot be atomically replaced by a JSON file.

        store.putIndex(StockFixtures.physical(index(64), ledger.epoch(), 0, 1, 0));
        assertEquals(1, ledger.revision());
        assertEquals(1, ledger.sources().size());
        assertNotNull(store.index("s", "a"));

        // A restart must reconstruct the real persisted index from guild.sqlite,
        // even when the optional JSON mirror was impossible to write.
        IndexStore restarted = new IndexStore(data, CreativeTabs.NONE, new StockLedger(data));
        restarted.load();
        assertNotNull(restarted.index("s", "a"));
    }

    @Test
    void nextPhysicalObservationCanRebuildMirrorAfterFilesystemRecovers() throws Exception {
        StockLedger ledger = new StockLedger(data);
        IndexStore store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        Path mirror = data.resolve("indexes").resolve(IndexStore.fileName("s", "a"));
        Files.createDirectory(mirror);

        store.putIndex(StockFixtures.physical(index(64), ledger.epoch(), ledger.revision(), 1, 0));
        Files.delete(mirror);
        store.putIndex(StockFixtures.physical(index(32), ledger.epoch(), ledger.revision(), 2, 0));

        assertTrue(Files.isRegularFile(mirror));
        assertEquals(2, ledger.revision());
        assertEquals(32, ledger.sources().getFirst().value().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsLong());
    }

    @Test
    void unverifiedAggregateStillReportsFailedJsonPersistence() throws Exception {
        StockLedger ledger = new StockLedger(data);
        IndexStore store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        Path mirror = data.resolve("indexes").resolve(IndexStore.fileName("s", "a"));
        Files.createDirectory(mirror);

        assertThrows(IOException.class, () -> store.putIndex(index(64)));
        assertEquals(0, ledger.revision(), "an unverified aggregate is never committed as physical stock");
    }
}

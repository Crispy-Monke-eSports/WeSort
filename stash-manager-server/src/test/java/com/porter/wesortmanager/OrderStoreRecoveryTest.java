package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A malformed order book must never leave active reservations silently missing after restart. */
class OrderStoreRecoveryTest {
    @TempDir Path dir;

    @Test
    void corruptOrderBookPreventsStartup() throws Exception {
        Path orders = Files.createDirectories(dir.resolve("orders"));
        Files.writeString(orders.resolve("broken.json"), "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[{\"id\":\"o-broken\"},");
        var store = new OrderStore(dir, new IndexStore(dir));
        assertThrows(IOException.class, store::load);
    }

    @Test
    void partiallyValidOrderBookAlsoPreventsStartup() throws Exception {
        Path orders = Files.createDirectories(dir.resolve("orders"));
        Files.writeString(orders.resolve("partial.json"),
                "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[{\"id\":\"o-1\"}, {\"missing\":true}]}");
        var store = new OrderStore(dir, new IndexStore(dir));
        assertThrows(IOException.class, store::load);
    }
    @Test
    void interruptedUuidDeregistrationIsReplayedWhenOrdersLoad() throws Exception {
        var index = new IndexStore(dir, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(index, JsonParser.parseString(
                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                .getAsJsonObject());
        var first = new OrderStore(dir, index);
        first.load();
        first.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        Path saved;
        try (var files = Files.list(dir.resolve("orders"))) {
            saved = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        var book = JsonParser.parseString(Files.readString(saved)).getAsJsonObject();
        var pending = new JsonArray();
        pending.add(UUID.randomUUID().toString()); // A valid, already-absent UUID: registry replay is idempotent.
        book.getAsJsonArray("orders").get(0).getAsJsonObject().add("pendingUuidDeregistrations", pending);
        Files.writeString(saved, book.toString());
        var restarted = new OrderStore(dir, index);
        restarted.load();
        var recovered = JsonParser.parseString(Files.readString(saved)).getAsJsonObject();
        assertFalse(recovered.getAsJsonArray("orders").get(0).getAsJsonObject().has("pendingUuidDeregistrations"));
    }
}

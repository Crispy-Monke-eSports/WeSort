package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}

package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stray legacy files must never influence startup: SQLite is the only order truth. */
class OrderStoreRecoveryTest {
    @TempDir
    Path dir;

    @Test
    void corruptLegacyJsonIsIgnoredAndStartupSucceeds() throws Exception {
        // No historic migration (#11): Data/orders/*.json are never read, so a
        // corrupt stray file cannot prevent startup or hide reservations.
        var index = stockedIndex();
        Path orders = Files.createDirectories(dir.resolve("orders"));
        Files.writeString(
                orders.resolve("broken.json"), "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[{\"id\":\"o-broken\"},");
        var store = new OrderStore(dir, index);
        store.load();
        assertTrue(store.listJson("s", "a").getAsJsonArray("orders").isEmpty());
        assertTrue(Files.readString(orders.resolve("broken.json")).startsWith("{\"server\""));
    }

    @Test
    void partiallyValidLegacyJsonIsIgnored() throws Exception {
        var index = stockedIndex();
        Path orders = Files.createDirectories(dir.resolve("orders"));
        Files.writeString(
                orders.resolve("partial.json"),
                "{\"server\":\"s\",\"stash\":\"a\",\"orders\":[{\"id\":\"o-1\"}, {\"missing\":true}]}");
        var store = new OrderStore(dir, index);
        store.load();
        assertTrue(store.listJson("s", "a").getAsJsonArray("orders").isEmpty());
    }

    private IndexStore stockedIndex() throws Exception {
        var index = new IndexStore(dir, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(index, JsonParser.parseString("""
                    {"version":1,"server":"s","stash":"a","items":[
                      {"key":"minecraft:diamond","id":"minecraft:diamond","count":64,"maxStack":64}]}
                    """).getAsJsonObject());
        return index;
    }

    @Test
    void orderAndAuditCommitTogetherAndFailedAuditLeavesNoReservation() throws Exception {
        var index = stockedIndex();
        var orders = new OrderStore(dir, index);
        orders.load();
        try (var c = index.stock().open();
                var sql = c.createStatement()) {
            sql.execute("""
                CREATE TRIGGER reject_order_audit BEFORE INSERT ON order_event_log
                BEGIN SELECT RAISE(ABORT,'injected audit failure'); END
                """);
        }
        var lines = List.of(new OrderStore.Line("item", "minecraft:diamond", 1));
        assertThrows(IOException.class, () -> orders.place("s", "a", lines, null));
        assertTrue(orders.listJson("s", "a").getAsJsonArray("orders").isEmpty());
        assertTrue(orders.heldRoots("s").isEmpty());
        try (var c = index.stock().open();
                var sql = c.createStatement()) {
            try (var rows = sql.executeQuery("SELECT count(*) FROM order_books")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            sql.execute("DROP TRIGGER reject_order_audit");
        }
        var created = orders.place("s", "a", lines, null);
        var reloaded = new OrderStore(dir, index);
        reloaded.load();
        assertEquals("pending", reloaded.statusOf(created.id));
        try (var c = index.stock().open();
                var sql = c.createStatement();
                var rows = sql.executeQuery("SELECT count(*) FROM order_event_log")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void legacyJsonCannotOverwriteNewerSQLiteState()
            throws Exception { // The SQLite books are authoritative: a stray legacy file on disk — even
        // one describing the same stash — is never imported over live state.
        var index = stockedIndex();
        var orders = new OrderStore(dir, index);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        var old = OrderBookFixtures.read(index, "s", "a");
        Path legacy = Files.createDirectories(dir.resolve("orders")).resolve(IndexStore.fileName("s", "a"));
        Files.writeString(legacy, old.toString());
        var reloaded = new OrderStore(dir, index);
        reloaded.load();
        assertEquals("pending", reloaded.statusOf(order.id));
        Files.writeString(legacy, "{corrupt");
        var restarted = new OrderStore(dir, index);
        restarted.load();
        assertEquals("pending", restarted.statusOf(order.id));
        assertEquals("{corrupt", Files.readString(legacy));
    }

    @Test
    void missingGuildDatabaseWithStrayJournalsPreventsStartup() throws Exception {
        // SQLite-side startup fencing is independent of the removed JSON import: a
        // missing guild.sqlite with stray WAL/SHM or init markers must refuse to
        // start rather than boot an empty guild over real data.
        var index = stockedIndex();
        var orders = new OrderStore(dir, index);
        orders.load();
        Files.deleteIfExists(dir.resolve("guild.sqlite-wal"));
        Files.deleteIfExists(dir.resolve("guild.sqlite-shm"));
        Files.delete(dir.resolve("guild.sqlite"));
        assertThrows(IOException.class, () -> new StockLedger(dir));
        assertFalse(Files.exists(dir.resolve("guild.sqlite")));
    }

    @Test
    void onlyWholeBoxAllocationsAuthorizeRegisteredBoxRelease() {
        var order = new OrderStore.Order();
        // A reserved nested item belongs to the outer box's root but must not
        // deregister the still-existing container when those items are packed.
        var reported = new JsonArray();
        reported.add(UUID.randomUUID().toString());
        order.allocations = List.of(
                new PhysicalReservations.Allocation("source-A", "8/2", "item:minecraft:diamond", 5, "fingerprint", 1));
        assertFalse(OrderStore.boxRelease(order, reported)
                .reserved()
                .contains(new PhysicalReservations.Root("source-A", "8")));

        order.allocations =
                List.of(new PhysicalReservations.Allocation("source-A", "8", "shulker:kit", 1, "fingerprint", 1));
        assertTrue(OrderStore.boxRelease(order, reported)
                .reserved()
                .contains(new PhysicalReservations.Root("source-A", "8")));
        assertFalse(OrderStore.boxRelease(order, reported)
                .reserved()
                .contains(new PhysicalReservations.Root("source-A", "9")));
        assertFalse(OrderStore.boxRelease(order, reported)
                .reserved()
                .contains(new PhysicalReservations.Root("source-B", "8")));
        order.allocations = null;
        assertFalse(OrderStore.boxRelease(order, reported)
                .reserved()
                .contains(new PhysicalReservations.Root("source-A", "8")));
    }

    @Test
    void interruptedUuidDeregistrationIsReplayedWhenOrdersLoad() throws Exception {
        var index = new IndexStore(dir, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(
                index,
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                        .getAsJsonObject());
        var first = new OrderStore(dir, index);
        first.load();
        first.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        var book = OrderBookFixtures.read(index, "s", "a");
        var pending = new JsonArray();
        pending.add(UUID.randomUUID().toString()); // A valid, already-absent UUID: registry replay is idempotent.
        book.getAsJsonArray("orders").get(0).getAsJsonObject().add("pendingUuidDeregistrations", pending);
        OrderBookFixtures.replace(index, book);
        var restarted = new OrderStore(dir, index);
        restarted.load();
        var recovered = OrderBookFixtures.read(index, "s", "a");
        assertFalse(recovered.getAsJsonArray("orders").get(0).getAsJsonObject().has("pendingUuidDeregistrations"));
    }
}

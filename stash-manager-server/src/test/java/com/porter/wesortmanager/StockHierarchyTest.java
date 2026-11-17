package com.porter.wesortmanager;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockHierarchyTest {
    @TempDir Path data;
    private PhysicalSlots.Snapshot frame(int count) {
        var diamond = new PhysicalSlots.Item("minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var box = new PhysicalSlots.Item("minecraft:shulker_box", "minecraft:shulker_box", 1, "{}", PhysicalSlots.Kind.SHULKER, 27, List.of(new PhysicalSlots.Slot(26, count, diamond)), "kit");
        return new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, box), new PhysicalSlots.Slot(2, 1, box)));
    }
    private JsonArray amounts(Map<String,Long> counts) {
        JsonArray out = new JsonArray(); counts.forEach((key,count) -> { var row = new JsonObject(); row.addProperty("id", key.equals("kit") ? "minecraft:shulker_box" : key); row.addProperty("key", key); row.addProperty("count", count); out.add(row); }); return out;
    }
    private JsonObject index(StockLedger ledger, int count) throws Exception {
        var frame = frame(count); var root = new JsonObject(); root.addProperty("version", 1); root.addProperty("receivedAt", System.currentTimeMillis()); root.addProperty("server", "s"); root.addProperty("stash", "a");
        root.add("items", amounts(frame.totals())); root.add("shulkers", amounts(frame.shulkers()));
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision()+1, 0);
        var writer = new PhysicalSlots.Writer(); source(root).add("slots", writer.slots(frame.slots())); source(root).addProperty("fingerprint", frame.fingerprint());
        root.addProperty("physicalVersion", 2); root.add("slotVariants", writer.variants()); return root;
    }
    private JsonObject source(JsonObject index) { return index.getAsJsonArray("physicalSources").get(0).getAsJsonObject(); }
    @Test void storesHierarchyInSameTransactionAndKeepsStableSlotRevisions() throws Exception {
        var ledger = new StockLedger(data); var first = index(ledger, 32); var id = source(first).get("id").getAsString(); ledger.accept(first);
        var before = ledger.physicalSlots(id); assertEquals(4, before.size()); assertTrue(before.stream().allMatch(r -> r.get("revision").getAsLong()==1));
        assertFalse(ledger.sources().getFirst().value().has("slotSnapshot"));
        var same = index(ledger, 32); ledger.accept(same);
        assertTrue(ledger.physicalSlots(id).stream().allMatch(r -> r.get("revision").getAsLong()==1));
        var changed = index(ledger, 31); ledger.accept(changed);
        assertTrue(ledger.physicalSlots(id).stream().allMatch(r -> r.get("revision").getAsLong()==3));
        assertEquals(frame(32).json(), ledger.snapshot(id, frame(32).fingerprint()));
        assertEquals(frame(31).json(), ledger.snapshot(id, frame(31).fingerprint()));
        assertThrows(StockLedger.Conflict.class, () -> ledger.accept(same));
    }
    @Test void rejectsAggregateMismatchWithoutCommittingAnyRows() throws Exception {
        var ledger = new StockLedger(data); var bad = index(ledger, 32);
        source(bad).getAsJsonArray("items").get(0).getAsJsonObject().addProperty("count", 999);
        assertThrows(IllegalArgumentException.class, () -> ledger.accept(bad)); assertEquals(0, ledger.revision()); assertTrue(ledger.sources().isEmpty());
    }
    @Test void uncertaintyPreservesSnapshotAndRestartRequiresNewRead() throws Exception {
        var ledger = new StockLedger(data); var original = index(ledger, 32); ledger.accept(original);
        var stale = index(ledger, 1); source(stale).addProperty("state", "UNREACHABLE"); ledger.accept(stale);
        var source = ledger.sources().getFirst(); assertEquals("UNREACHABLE", source.status()); assertEquals(frame(32).fingerprint(), source.value().get("slotFingerprint").getAsString());
        assertEquals(4, ledger.physicalSlots(source.value().get("id").getAsString()).size());
        var restarted = new StockLedger(data); assertNotEquals("FRESH", restarted.sources().getFirst().status());
        assertThrows(StockLedger.Conflict.class, () -> restarted.accept(original));
    }
    @Test void detailRequiresVisibleMembershipAndForcedModeCannotFetchComponents() throws Exception {
        var ledger = new StockLedger(data); var original = index(ledger, 32); ledger.accept(original);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger); store.load(); var orders = new OrderStore(data, store); orders.load();
        var routes = new StockRoutes(store, orders); var id = source(original).get("id").getAsString();
        var owner = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);
        assertEquals(frame(32).json(), routes.source(owner, id).getAsJsonObject("snapshot"));
        var hidden = new Users.Principal("hidden", "Hidden", false, Users.Perms.of(true,false,false,false,false,false,false,List.of(new Users.StashRef("s", "other")), List.of()), false);
        assertNull(routes.source(hidden, id));
        var hiddenStock = routes.view(hidden);
        assertTrue(hiddenStock.getAsJsonArray("sources").isEmpty());
        assertTrue(hiddenStock.getAsJsonArray("stashes").isEmpty());
        var visibleBalance = routes.view(owner).getAsJsonArray("items").get(0).getAsJsonObject();
        var sharedBalance = hiddenStock.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(visibleBalance.get("observed"), sharedBalance.get("observed"));
        assertFalse(sharedBalance.has("server"));
        var forced = new Users.Principal("forced", "Forced", false, Users.Perms.ALL.withStreamer(true), false);
        assertNull(routes.source(forced, id));
        assertFalse(routes.view(forced).toString().contains("slotFingerprint"));
        assertFalse(Redact.index(original.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).has("slotVariants"));
    }
    @Test void changedContentsBlockExistingOrderEvenWhenEnoughAggregateStockRemains() throws Exception {
        var ledger = new StockLedger(data); var store = new IndexStore(data, CreativeTabs.NONE, ledger); store.load();
        store.putIndex(index(ledger, 32)); var orders = new OrderStore(data, store); orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNotNull(order.sourceGuards); assertNotNull(orders.claim("s", "a"));
        store.putIndex(index(ledger, 31)); assertNull(orders.claim("s", "a"));
        var restarted = new OrderStore(data, store); restarted.load(); assertNull(restarted.claim("s", "a"));
        assertEquals(order.sourceGuards, OrderStore.Order.fromJson(order.toJson()).sourceGuards);
    }
    @Test void racingWholeBoxesAndContentsAcrossAliasesCannotReserveTheSameRoots() throws Exception {
        var ledger = new StockLedger(data);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var alias = index(ledger, 32);
        alias.addProperty("stash", "b");
        store.putIndex(alias);
        var orders = new OrderStore(data, store);
        orders.load();
        var whole = new OrderStore.Line("shulker", "kit", 2);
        var child = new OrderStore.Line("item", "minecraft:diamond", 64);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<OrderStore.Order> first = () -> tryOrder(orders, start, "a", whole);
        java.util.concurrent.Callable<OrderStore.Order> second = () -> tryOrder(orders, start, "b", child);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(first);
            var b = pool.submit(second);
            start.countDown();
            OrderStore.Order winner = a.get();
            OrderStore.Order other = b.get();
            assertTrue((winner == null) != (other == null));
            if (winner == null) winner = other;
            assertEquals(2, orders.heldRoots("s").size());
            assertNull(orders.acquireSorting("s", java.util.UUID.randomUUID().toString(), null));
            var restarted = new OrderStore(data, store);
            restarted.load();
            assertEquals(orders.heldRoots("s"), restarted.heldRoots("s"));
            restarted.cancel(winner.id);
            assertTrue(restarted.heldRoots("s").isEmpty());
            var after = restarted.place("s", winner.stash.equals("a") ? "b" : "a",
                List.of(winner.stash.equals("a") ? child : whole), null);
            assertEquals(2, after.allocations.size());
        }
    }

    private static OrderStore.Order tryOrder(OrderStore orders, java.util.concurrent.CountDownLatch start,
                                              String stash, OrderStore.Line line) throws Exception {
        start.await();
        try {
            return orders.place("s", stash, List.of(line), null);
        } catch (OrderStore.OrderException conflict) {
            assertEquals(409, conflict.status);
            return null;
        }
    }

    @Test void failedClaimRetainsRootsUntilFreshReadFromThePhysicalSource() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var ledger = new StockLedger(data, now::get, 600_000);
        var store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        store.putIndex(index(ledger, 32));
        var orders = new OrderStore(data, store);
        orders.load();
        var order = orders.place("s", "a", List.of(new OrderStore.Line("shulker", "kit", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        assertNull(orders.claimPayload("s", "a", UUID.randomUUID().toString()));
        assertThrows(OrderStore.OrderException.class,
            () -> orders.status(order.id, "failed", null, null, null, null, "other"));
        var failed = orders.status(order.id, "failed", null, null, null, null, agent);
        orders.releaseFromFreshObservation("s");
        assertEquals(2, orders.heldRoots("s").size());
        assertEquals(0, orders.clearFinished("s", "a"));

        var refreshed = index(ledger, 32);
        now.set(failed.updatedAt + 1_000);
        source(refreshed).addProperty("observedAt", now.get());
        store.putIndex(refreshed);
        orders.releaseFromFreshObservation("s");
        assertTrue(orders.heldRoots("s").isEmpty());
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertTrue(restarted.heldRoots("s").isEmpty());
    }

    @Test void schemaTwoMigratesWithoutLosingHistoricalStock() throws Exception {
        var ledger = new StockLedger(data); ledger.accept(index(ledger, 32));
        try (var c = ledger.open(); var sql = c.createStatement()) {
            sql.execute("DROP TABLE physical_snapshots"); sql.execute("DROP TABLE physical_slots"); sql.execute("PRAGMA user_version=2");
        }
        var upgraded = new StockLedger(data);
        assertEquals("STALE_STARTUP", upgraded.sources().getFirst().status());
        assertEquals(2, upgraded.sources().getFirst().value().getAsJsonArray("shulkers").get(0).getAsJsonObject().get("count").getAsInt());
        // No physical slot payload is invented when upgrading a version that never stored it.
        assertNull(upgraded.snapshot(upgraded.sources().getFirst().value().get("id").getAsString(), frame(32).fingerprint()));
    }
    @Test void legacyReadInvalidatesOldPhysicalSlotsWithoutDiscardingHistory() throws Exception {
        var ledger = new StockLedger(data); var original = index(ledger, 32); ledger.accept(original); var id = source(original).get("id").getAsString();
        var legacy = index(ledger, 31); legacy.addProperty("physicalVersion", 1); ledger.accept(legacy);
        assertTrue(ledger.physicalSlots(id).stream().noneMatch(r -> r.get("present").getAsBoolean()));
        assertNotNull(ledger.snapshot(id, frame(32).fingerprint()));
    }
}

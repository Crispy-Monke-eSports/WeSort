package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomationGateTest {
    @TempDir
    Path data;

    private IndexStore store() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        StockFixtures.put(
                store,
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64}]}")
                        .getAsJsonObject());
        return store;
    }

    @Test
    void schemaOneUpgradesWithoutLosingStockAndNewerSchemasAreRejected() throws Exception {
        var store = store();
        try (var c = store.stock().open();
                var sql = c.createStatement()) {
            sql.execute("DROP TABLE automation_leases");
            sql.execute("PRAGMA user_version=1");
        }
        var upgraded = new StockLedger(data);
        assertEquals(
                64,
                upgraded.sources()
                        .getFirst()
                        .value()
                        .getAsJsonArray("items")
                        .get(0)
                        .getAsJsonObject()
                        .get("count")
                        .getAsInt());
        try (var c = upgraded.open();
                var sql = c.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM automation_leases")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            sql.execute("PRAGMA user_version=8");
        }
        assertThrows(java.io.IOException.class, () -> new StockLedger(data));
    }

    /** The race must test real physical holds, not the legacy aggregate-only stock fixture. */
    private IndexStore storeWithPhysicalSlots() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        var index = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                .getAsJsonObject();
        var ledger = store.stock();
        var physical = StockFixtures.physical(index, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slots = List.of(new PhysicalSlots.Slot(0, 64, diamond));
        var writer = new PhysicalSlots.Writer();
        var slotRows = writer.slots(slots);
        physical.addProperty("physicalVersion", 2);
        physical.add("slotVariants", writer.variants());
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.add("slots", slotRows);
        source.addProperty("fingerprint", new PhysicalSlots.Snapshot(27, slots).fingerprint());
        store.putIndex(physical);
        return store;
    }

    @Test
    void simultaneousSortingAndOrderRequestHaveNoOverlappingReservations() throws Exception {
        var store = storeWithPhysicalSlots();
        assertTrue(PhysicalReservations.read(store.stock(), "s", "a").hasSnapshots());
        var orders = new OrderStore(data, store);
        orders.load();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var sorting = pool.submit(() -> {
                start.await();
                return orders.acquireSorting("s", "agent", null);
            });
            var placing = pool.submit(() -> {
                start.await();
                return orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
            });
            start.countDown();
            var lease = sorting.get();
            var order = placing.get();
            assertNotNull(order); // Never reject a customer's new request.
            if (lease != null) {
                assertTrue(order.reservationDeferred);
                assertNull(order.allocations);
                assertTrue(orders.heldRoots("s").isEmpty());
                assertNotNull(orders.acquireSorting("s", "agent", lease.token()));
                assertNull(orders.acquireSorting("s", "foreign-agent", null));
            } else {
                assertFalse(order.reservationDeferred);
                assertNotNull(order.allocations);
                assertFalse(orders.heldRoots("s").isEmpty());
            }
        }
    }

    @Test
    void onlyCurrentLeaseHolderMayFinishPhysicalSortingAfterAQueueArrives() throws Exception {
        var store = store();
        var orders = new OrderStore(data, store);
        orders.load();
        var lease = orders.acquireSorting("s", "agent", null);
        assertNotNull(lease);
        var queued = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertTrue(queued.reservationDeferred);
        assertNull(queued.allocations);
        assertNull(orders.claim("s", "a")); // No claim until the active sorting lease is released.
        assertNotNull(orders.acquireSorting("s", "agent", lease.token()));
        assertNull(orders.acquireSorting("s", "other-agent", lease.token()));
        assertTrue(store.automation().finish("s", "agent", lease.token(), true));
        assertNull(store.automation().current("s"));
        assertNull(orders.claim("s", "a")); // Release invalidated the old physical observation.
    }

    @Test
    void reservedItemsBlockAllSortingAndSecondAgentCannotJoinALease() throws Exception {
        var store = store();
        var orders = new OrderStore(data, store);
        orders.load();
        orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNull(orders.acquireSorting("s", "agent", null));
        var lease = orders.acquireSorting("another-server", "agent", null);
        assertNotNull(lease);
        assertNull(orders.acquireSorting("another-server", "other-agent", lease.token()));
    }

    @Test
    void expiredOrInterruptedSortingStaysFencedAcrossRestartAndCannotBeLateReleased() throws Exception {
        var store = store();
        var clock = new AtomicLong(1000);
        var gate = new AutomationGate(store.stock(), clock::get);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        clock.set(1000 + AutomationGate.TTL);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertEquals("STALE", store.stock().sources().getFirst().status());
        var restarted = new AutomationGate(store.stock(), clock::get);
        assertNull(restarted.acquire("s", "other", null));
        assertFalse(restarted.finish("s", "agent", lease.token(), true));
    }

    @Test
    void fastManagerRestartFencesEvenAnUnexpiredSortingToken() throws Exception {
        var store = store();
        var lease = store.automation().acquire("s", "agent", null);
        assertNotNull(lease);
        var restarted = new StockLedger(data);
        var gate = new AutomationGate(restarted);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertNull(gate.acquire("s", "agent", lease.token()));
        assertFalse(gate.finish("s", "agent", lease.token(), true));
        assertNotEquals("FRESH", restarted.sources().getFirst().status());
    }

    @Test
    void concurrentGateInstancesCannotBothAcquireThePhysicalSlot() throws Exception {
        var ledger = store().stock();
        var first = new AutomationGate(ledger);
        var second = new AutomationGate(ledger);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return first.acquire("s", "agent-a", null);
            });
            var b = pool.submit(() -> {
                start.await();
                return second.acquire("s", "agent-b", null);
            });
            start.countDown();
            var left = a.get();
            var right = b.get();
            assertTrue((left == null) != (right == null));
            assertEquals(left == null ? right : left, first.current("s"));
        }
    }

    @Test
    void lateRenewalCannotReviveAReleasedLease() throws Exception {
        var gate = store().automation();
        var first = gate.acquire("s", "agent", null);
        assertTrue(gate.finish("s", "agent", first.token(), true));
        assertNull(gate.acquire("s", "agent", first.token()));
        assertNull(gate.current("s"));
        var next = gate.acquire("s", "agent", null);
        assertNotEquals(first.token(), next.token());
        assertFalse(gate.finish("s", "agent", first.token(), true));
        assertEquals(next, gate.current("s"));
    }

    @Test
    void unresolvedNativeJournalCannotBeReleasedByAClaimedSuccess() throws Exception {
        var ledger = store().stock();
        var gate = new AutomationGate(ledger);
        String agent = UUID.randomUUID().toString();
        var lease = gate.acquire("s", agent, null);
        var journal = new com.porter.wesort.inventory.PhysicalJournal(data.resolve("lease-client"));
        var context = new JsonObject();
        context.addProperty("server", "s");
        context.addProperty("dimension", "minecraft:overworld");
        context.addProperty("kind", "DIRECT");
        context.addProperty("agent", agent);
        context.addProperty("stash", "a");
        context.addProperty("leaseFingerprint", com.porter.wesort.data.ContainerIdentity.hash(lease.token()));
        journal.start(context);
        var operations = new PhysicalOperations(ledger);
        operations.accept(agent, journal.current());
        assertFalse(gate.finish("s", agent, lease.token(), true));
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertNull(gate.acquire("s", "other", null));
    }

    @Test
    void verifiedReleaseInvalidatesCachedProofBeforeAllowingNewReservations() throws Exception {
        var store = store();
        var orders = new OrderStore(data, store);
        orders.load();
        var gate = store.automation();
        var lease = orders.acquireSorting("s", "agent", null);
        assertTrue(gate.finish("s", "agent", lease.token(), true));
        assertNull(gate.current("s"));
        assertEquals("STALE", store.stock().sources().getFirst().status());
        assertThrows(
                OrderStore.OrderException.class,
                () -> orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null));
    }
}

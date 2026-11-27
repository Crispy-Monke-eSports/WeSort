package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
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
                assertEquals(11, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM automation_leases")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            sql.execute("PRAGMA user_version=12");
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
        physical.addProperty("physicalVersion", 3);
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("snbt", new PhysicalSlots.Snapshot(27, slots).snbt());
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
    void expiredOrInterruptedSortingIsReleasedByAMatchingTokenWhenNoJournalExists() throws Exception {
        var store = store();
        var clock = new FakeClock(1000);
        var gate = new AutomationGate(store.stock(), clock);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        clock.set(1000 + AutomationGate.TTL);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertEquals("STALE", store.stock().sources().getFirst().status());
        // No journal row exists for the server, so nothing could ever inspect this fence:
        // the holder's own verified release frees the orphan instead of denying forever.
        assertTrue(gate.finish("s", "agent", lease.token(), true));
        assertNull(gate.current("s"));
        var restarted = new AutomationGate(store.stock(), clock);
        // ... but a foreign token still cannot revive or release the freed lease.
        var next = restarted.acquire("s", "other", null);
        assertNotNull(next);
        assertFalse(restarted.finish("s", "agent", lease.token(), true));
        assertFalse(restarted.finish("s", "other", lease.token(), true));
        assertEquals(next, restarted.current("s"));
    }

    @Test
    void orphanedUncertainLeaseWithoutAnyJournalIsDroppedAfterGrace() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        // Simulate a restart: reopening the ledger fences the ACTIVE lease.
        var restartedLedger = new StockLedger(data);
        var restarted = new AutomationGate(restartedLedger, clock);
        assertEquals("NEEDS_RECONCILIATION", restarted.current("s").state());
        // Still fenced inside the grace period: reads never hand the lease over ...
        clock.set(lease.deadline() + AutomationGate.ORPHAN_GRACE_MS - 1);
        assertNotNull(restarted.current("s"));
        // ... but dropped once the grace period passes with no journal posted.
        // No physical_operations row exists, so releaseInspectedLease could never run for it.
        // (A different agent that tries inside the grace period takes over after 2xTTL
        // silence instead — see takeoverAfterTwoTtlSilenceHandsTheLeaseToAWaitingAgent.)
        clock.set(lease.deadline() + AutomationGate.ORPHAN_GRACE_MS);
        assertNull(restarted.current("s"));
        var next = restarted.acquire("s", "other", null);
        assertNotNull(next);
        assertEquals(next, restarted.current("s"));
    }

    @Test
    void uncertainLeaseWithALiveJournalIsNeverAutoDropped() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        var restartedLedger = new StockLedger(data);
        try (var c = restartedLedger.open();
                var tx = new SqliteWriteTransaction(c);
                var p = c.prepareStatement(
                        "INSERT INTO physical_operations(id,server,agent,stash,revision,checksum,payload,state,last_seen,fence_revision) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, UUID.randomUUID().toString());
            p.setString(2, "s");
            p.setString(3, "agent");
            p.setString(4, "a");
            p.setLong(5, 1);
            p.setString(6, "checksum");
            p.setString(7, "{}");
            p.setString(8, "PLANNED");
            p.setLong(9, clock.get());
            p.setLong(10, 0);
            p.executeUpdate();
            tx.commit();
        }
        var restarted = new AutomationGate(restartedLedger, clock);
        // Far past the grace period, but a live journal demands inspection first.
        clock.set(lease.deadline() + AutomationGate.ORPHAN_GRACE_MS + 60_000);
        assertNotNull(restarted.current("s"));
        assertEquals("NEEDS_RECONCILIATION", restarted.current("s").state());
        assertNull(restarted.acquire("s", "other", null));
    }

    @Test
    void fastManagerRestartFencesAnUnexpiredSortingTokenUntilItsHolderReleasesIt() throws Exception {
        var store = store();
        var lease = store.automation().acquire("s", "agent", null);
        assertNotNull(lease);
        var restarted = new StockLedger(data);
        var gate = new AutomationGate(restarted);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertNull(gate.acquire("s", "agent", lease.token()));
        // No journal exists, so the holder's own release frees the orphan ...
        assertTrue(gate.finish("s", "agent", lease.token(), true));
        assertNull(gate.current("s"));
        assertNotEquals("FRESH", restarted.sources().getFirst().status());
        // ... while a stranger's token never does.
        var next = gate.acquire("s", "agent", null);
        assertNotNull(next);
        assertFalse(gate.finish("s", "other", next.token(), true));
        assertFalse(gate.finish("s", "agent", lease.token(), true));
        assertEquals(next, gate.current("s"));
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
    void takeoverAfterTwoTtlSilenceHandsTheLeaseToAWaitingAgent() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        clock.set(lease.deadline() + AutomationGate.TAKEOVER_SILENCE_MS - 1);
        // Still silence too short: the fence holds for the old holder's reconnect.
        assertNull(gate.acquire("s", "other", null));
        clock.set(lease.deadline() + AutomationGate.TAKEOVER_SILENCE_MS);
        var next = gate.acquire("s", "other", null);
        assertNotNull(next);
        assertEquals("other", gate.current("s").agent());
        assertNotEquals(lease.token(), next.token());
    }

    @Test
    void takeoverNeverStealsALeaseWithLiveEvidence() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        try (var c = store.stock().open();
                var tx = new SqliteWriteTransaction(c);
                var p = c.prepareStatement(
                        "INSERT INTO physical_operations(id,server,agent,stash,revision,checksum,payload,state,last_seen,fence_revision) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, UUID.randomUUID().toString());
            p.setString(2, "s");
            p.setString(3, "agent");
            p.setString(4, "a");
            p.setLong(5, 1);
            p.setString(6, "checksum");
            p.setString(7, "{}");
            p.setString(8, "PLANNED");
            p.setLong(9, clock.get());
            p.setLong(10, 0);
            p.executeUpdate();
            tx.commit();
        }
        clock.set(lease.deadline() + AutomationGate.TAKEOVER_SILENCE_MS + AutomationGate.ORPHAN_GRACE_MS);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertNull(gate.acquire("s", "other", null));
    }

    @Test
    void denyReasonsDistinguishStaleTokensFromForeignHolders() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        // No row at all: a presented token is stale, an absent one just has no lease.
        assertEquals(AutomationGate.REASON_UNKNOWN_TOKEN, gate.denyReason("s", "agent", "dead-token"));
        assertEquals(AutomationGate.REASON_NO_LEASE, gate.denyReason("s", "agent", null));
        assertEquals(AutomationGate.REASON_NO_LEASE, gate.releaseDenyReason("s", "agent", "dead-token"));
        var lease = gate.acquire("s", "agent", null);
        assertNotNull(lease);
        assertEquals(AutomationGate.REASON_HELD_BY_OTHER, gate.denyReason("s", "other", lease.token()));
        assertEquals(AutomationGate.REASON_TOKEN_MISMATCH, gate.denyReason("s", "agent", "wrong-token"));
        assertEquals(AutomationGate.REASON_HELD_BY_OTHER, gate.releaseDenyReason("s", "other", lease.token()));
        // Expiry fences the lease: the holder is told to post its journal, not to retry.
        clock.set(lease.deadline());
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertEquals(AutomationGate.REASON_RECONCILIATION_REQUIRED, gate.denyReason("s", "agent", lease.token()));
        assertEquals(
                AutomationGate.REASON_RECONCILIATION_REQUIRED, gate.releaseDenyReason("s", "agent", lease.token()));
    }

    @Test
    void ownerCanFreeAnOrphanedFenceButNeverALiveOne() throws Exception {
        var store = store();
        var clock = new FakeClock(1_000_000);
        var gate = new AutomationGate(store.stock(), clock);
        assertFalse(gate.freeLease("s"));
        var active = gate.acquire("s", "agent", null);
        assertNotNull(active);
        assertFalse(gate.freeLease("s")); // ACTIVE leases are never freed by hand.
        clock.set(active.deadline());
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertTrue(gate.freeLease("s"));
        assertNull(gate.current("s"));
        assertFalse(gate.freeLease("s"));
    }

    @Test
    void ownerFreeLeaseRefusesWhileAJournalWaitsForInspection() throws Exception {
        var ledger = store().stock();
        var gate = new AutomationGate(ledger);
        String holder = UUID.randomUUID().toString();
        var lease = gate.acquire("s", holder, null);
        assertNotNull(lease);
        var journal = new com.porter.wesort.inventory.PhysicalJournal(data.resolve("lease-client"));
        var context = new JsonObject();
        context.addProperty("server", "s");
        context.addProperty("dimension", "minecraft:overworld");
        context.addProperty("kind", "DIRECT");
        context.addProperty("agent", holder);
        context.addProperty("stash", "a");
        journal.start(context);
        new PhysicalOperations(ledger).accept(holder, journal.current());
        assertFalse(gate.finish("s", holder, lease.token(), true));
        assertFalse(gate.freeLease("s"));
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
    }

    @Test
    void recoveringOrdersDoNotBlockLeaseRenewalForEveryone() throws Exception {
        var store = store();
        var orders = new OrderStore(data, store);
        orders.load();
        String agent = UUID.randomUUID().toString();
        var placed = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNotNull(orders.claimPayload("s", "a", agent));
        // A live accepted order owns the server: renewal is denied.
        assertNull(orders.acquireSorting("s", "agent", null));
        // The agent restarts mid-claim: the order is fenced as RECOVERING and only that
        // agent may resume it, while the sorting slot opens for everyone else again.
        assertNotNull(orders.resumeClaim("s", "a", agent, placed.id));
        assertFalse(store.stock().unsettledPhysicalOrder(placed.id));
        var lease = orders.acquireSorting("s", "agent", null);
        assertNotNull(lease);
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

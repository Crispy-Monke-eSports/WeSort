package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Escape hatches for stuck joint orders: owner-force, silent-rollback timeouts, sweep-release
 * membership, live-bot fallbacks, rollback deadlines, unclaimed reservations, terminal-set
 * alignment, rollback completion, and actionable retry errors.
 */
class StuckOrderEscapeTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(System.currentTimeMillis());
    private StockLedger ledger;
    private IndexStore store;
    private OrderStore orders;

    @BeforeEach
    void initialize() throws Exception {
        ledger = new StockLedger(data, time::get, 600_000);
        store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        orders = new OrderStore(data, store);
        orders.load();
    }

    private com.porter.wesort.data.PhysicalSlots.Snapshot frame(int count) {
        var diamond = new com.porter.wesort.data.PhysicalSlots.Item(
                "minecraft:diamond",
                "minecraft:diamond",
                64,
                "{}",
                com.porter.wesort.data.PhysicalSlots.Kind.ITEM,
                0,
                List.of(),
                "");
        return new com.porter.wesort.data.PhysicalSlots.Snapshot(
                27, List.of(new com.porter.wesort.data.PhysicalSlots.Slot(0, count, diamond)));
    }

    private JsonObject index(String stash, int count) throws Exception {
        var frame = frame(count);
        var root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", "s");
        root.addProperty("stash", stash);
        var items = new JsonArray();
        var row = new JsonObject();
        row.addProperty("id", "minecraft:diamond");
        row.addProperty("key", "minecraft:diamond");
        row.addProperty("count", (long) count);
        items.add(row);
        root.add("items", items);
        root.add("shulkers", new JsonArray());
        root = StockFixtures.physical(root, ledger.epoch(), ledger.revision(), ledger.revision() + 1, stash.hashCode());
        var writer = new com.porter.wesort.data.PhysicalSlots.Writer();
        var src = root.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        src.add("slots", writer.slots(frame.slots()));
        src.addProperty("fingerprint", frame.fingerprint());
        root.addProperty("physicalVersion", 2);
        root.add("slotVariants", writer.variants());
        return root;
    }

    private void observe(String stash, int count) throws Exception {
        var payload = index(stash, count);
        payload.getAsJsonArray("physicalSources").get(0).getAsJsonObject().addProperty("observedAt", time.get());
        store.putIndex(payload);
    }

    /** A live order stuck like o-6f1a: claimed, interrupted, parked in NEEDS_RECONCILIATION. */
    private OrderStore.Order stuckOrder(String stash) throws Exception {
        var order = orders.place("s", stash, List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", stash, agent));
        var legacy = orders.status(order.id, "in_progress", null, null, null, null, agent, "r1");
        legacy.stage = OrderStage.NEEDS_RECONCILIATION;
        legacy.status = "needs_reconciliation";
        return legacy;
    }

    private JsonObject listed(String stash, String id) {
        for (var e : orders.listJson("s", stash).getAsJsonArray("orders")) {
            var o = e.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) return o;
        }
        fail("order " + id + " not listed");
        return null;
    }

    @Test
    void ownerForceReleasesStuckOrderDespiteAllocations() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        // cancel is a no-op flag and force-cancel refuses: the stuck shape from the incident.
        var flagged = orders.cancel(stuck.id);
        assertEquals(OrderStage.RECOVERING, flagged.stage());
        var refused = assertThrows(OrderStore.OrderException.class, () -> orders.forceCancel(stuck.id));
        assertEquals(409, refused.status);

        var released = orders.ownerForceCancel(stuck.id, Users.OWNER_ID);
        assertEquals("failed", released.status);
        assertEquals(OrderStage.FAILED, released.stage());
        assertTrue(released.releasedAt > 0);
        // Holds are gone: nothing reserves, nothing gates the sorting lease.
        assertTrue(orders.heldRoots("s").isEmpty());
        assertEquals(
                0L,
                orders.reservedFor("s", "a")
                        .getOrDefault("item:minecraft:diamond", 0L)
                        .longValue());
        var row = listed("a", stuck.id);
        assertEquals("FAILED", row.get("state").getAsString());
        assertTrue(row.has("releasedAt"));
        // Full audit trail survives in the order history.
        var viewer = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);
        var audit = orders.auditOrders(viewer, o -> o.id.equals(stuck.id), 10);
        assertTrue(audit.toString().contains("order.owner-force"));
    }

    @Test
    void ownerForceRefusesTerminalOrders() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        orders.cancel(order.id);
        var done = assertThrows(OrderStore.OrderException.class, () -> orders.ownerForceCancel(order.id, Users.OWNER_ID));
        assertEquals(409, done.status);
        assertThrows(OrderStore.OrderException.class, () -> orders.ownerForceCancel("o-000000000000", Users.OWNER_ID));
    }

    @Test
    void silentRecoveringFailsAndReleasesOnNextObservation() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        orders.selfHealAll();
        assertEquals("RECOVERING", listed("a", stuck.id).get("state").getAsString());

        // A bot progress report keeps the rollback alive but must not reset its deadline:
        // report through the live object, then age only that object past the timeout.
        var live = orders.status(stuck.id, "in_progress", null, null, "still returning", null, stuck.agent, "r2");
        live.updatedAt = System.currentTimeMillis() - (OrderStore.RECOVERING_AFTER_MS + 60_000);
        orders.selfHealAll();

        var failed = listed("a", stuck.id);
        assertEquals("FAILED", failed.get("state").getAsString());
        assertEquals("failed", failed.get("status").getAsString());

        time.set(failed.get("updatedAt").getAsLong() + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertTrue(listed("a", stuck.id).has("releasedAt"));
        assertTrue(orders.heldRoots("s").isEmpty());
    }

    @Test
    void needsReconciliationMigratesButStaysProtectedUntilRollback() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        // Even a fresh observation must not release uncertain holds: the sweep skips
        // NEEDS_RECONCILIATION and autoHeal migrates it to the rollback protocol instead.
        time.set(stuck.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        var row = listed("a", stuck.id);
        assertFalse(row.has("releasedAt"), "uncertain holds must survive a fresh observation");
        assertEquals("RECOVERING", row.get("state").getAsString());
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
    }

    @Test
    void cancelTouchMigratesNeedsReconciliation() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        var touched = orders.cancel(stuck.id);
        assertEquals(OrderStage.RECOVERING, touched.stage());
        assertTrue(touched.cancelRequested);
    }

    @Test
    void progressReportsDoNotDeferRollbackDeadline() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        orders.cancel(order.id);
        // The cancel is 11 minutes old, but the bot keeps reporting progress (fresh updatedAt).
        var live = orders.status(order.id, "in_progress", null, null, "almost done", null, agent, "r1");
        live.cancelRequestedAt = System.currentTimeMillis() - 11 * 60 * 1000;
        assertTrue(System.currentTimeMillis() - live.updatedAt < OrderStore.AUTO_CANCEL_AFTER_MS);
        orders.selfHealAll();
        assertEquals("RECOVERING", listed("a", order.id).get("state").getAsString());
    }

    @Test
    void silentPackedOrderReleasesHoldsButStaysCollectible() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        var live = orders.status(order.id, "in_progress", null, null, null, null, agent, "r1");
        live.stage = OrderStage.PICKUP_READY;
        live.status = "packed";
        live.updatedAt = System.currentTimeMillis() - (OrderStore.pickupReadyReleaseAfterMs() + 60_000);
        orders.selfHealAll();
        var row = listed("a", order.id);
        assertEquals("PICKUP_READY", row.get("state").getAsString());
        assertEquals("packed", row.get("status").getAsString());
        assertTrue(row.has("releasedAt"));
        assertTrue(orders.heldRoots("s").isEmpty());
    }

    @Test
    void neverClaimedReservationAutoCancels() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertEquals(1, orders.heldRoots("s").size());
        order.updatedAt = System.currentTimeMillis() - (OrderStore.AUTO_CANCEL_AFTER_MS + 60_000);
        orders.selfHealAll();
        assertEquals("cancelled", orders.statusOf(order.id));
        assertEquals("CANCELLED", listed("a", order.id).get("state").getAsString());
        assertTrue(orders.heldRoots("s").isEmpty());
    }

    @Test
    void botFinishesRollbackViaCancelledAfterGoodsReturned() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        orders.selfHealAll();
        assertEquals("RECOVERING", listed("a", stuck.id).get("state").getAsString());
        // Delivered-under-rollback stays in RECOVERING, but cancelled/failed always terminate it.
        var done = orders.status(stuck.id, "cancelled", null, null, "Returned to stash", null, stuck.agent, "done");
        assertEquals(OrderStage.CANCELLED, done.stage());
        assertEquals("cancelled", done.status);
        time.set(done.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertTrue(listed("a", stuck.id).has("releasedAt"));
    }

    @Test
    void retryRefusalNamesHoldingOrders() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        // A bot-reported failure keeps the reservation until the verified sweep releases it.
        orders.status(order.id, "failed", null, null, "griefed", null, agent, "r1");
        var refused = assertThrows(OrderStore.OrderException.class, () -> orders.retry(order.id));
        assertEquals(409, refused.status);
        assertTrue(refused.getMessage().contains(order.id), refused.getMessage());
    }
}

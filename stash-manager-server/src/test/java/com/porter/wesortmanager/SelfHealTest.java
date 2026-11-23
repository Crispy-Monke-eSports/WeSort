package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The Manager resolves stuck orders itself: reconcile route, auto-heal sweep and ops signals. */
class SelfHealTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(System.currentTimeMillis());
    private StockLedger ledger;
    private IndexStore store;
    private OrderStore orders;
    private static final Users.Principal VIEWER = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);

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

    private OrderStore.Order stuckOrder(String stash) throws Exception {
        var order = orders.place("s", stash, List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", stash, agent));
        return orders.status(order.id, "needs_reconciliation", null, null, null, null, agent, "r1");
    }

    @Test
    void reconcileNeedsProofForPickupReadyButAbandonIsFree() throws Exception {
        observe("a", 10);
        var order = stuckOrder("a");
        // No fresh observation since the order was placed: pickup cannot be proven.
        var denied = assertThrows(
                OrderStore.OrderException.class, () -> orders.reconcile(order.id, "pickup_ready", "owner"));
        assertEquals(409, denied.status);
        // Abandoning never risks stock.
        var cancelled = orders.reconcile(order.id, "cancelled", "owner");
        assertEquals("cancelled", cancelled.status);
        // A cancelled order releases its hold on the next fresh observation.
        time.set(cancelled.updatedAt + 1000);
        observe("a", 10);
        orders.releaseFromFreshObservation("s");

        var second = stuckOrder("a");
        time.set(second.updatedAt + 1000);
        observe("a", 10);
        var ready = orders.reconcile(second.id, "pickup_ready", "owner");
        assertEquals("packed", ready.status);
        assertEquals(OrderStage.PICKUP_READY, ready.stage());
    }

    @Test
    void reconcileRejectsAnythingUnprovable() throws Exception {
        observe("a", 10);
        var open = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertEquals(
                409,
                assertThrows(OrderStore.OrderException.class, () -> orders.reconcile(open.id, "cancelled", "owner"))
                        .status);
        assertEquals(
                404,
                assertThrows(
                                OrderStore.OrderException.class,
                                () -> orders.reconcile("o-000000000000", "cancelled", "owner"))
                        .status);
        observe("b", 10);
        var stuck = stuckOrder("b");
        assertEquals(
                400,
                assertThrows(OrderStore.OrderException.class, () -> orders.reconcile(stuck.id, "deliver", "owner"))
                        .status);
    }

    @Test
    void freshObservationFailsUnprovenPackingInsteadOfInventingPickup() throws Exception {
        observe("a", 10);
        var order = stuckOrder("a");
        time.set(order.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals("failed", orders.statusOf(order.id));
        var stored = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertTrue(stored.get("releasedAt").getAsLong() > 0);
        orders.selfHealAll(); // Idempotent across periodic sweeps.
        assertEquals("failed", orders.statusOf(order.id));
    }

    @Test
    void timedOutPhysicalCancelSettlesAfterFreshObservationWithoutOwner() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        orders.claim("s", "a");
        var cancelled = orders.cancel(order.id);
        assertNotNull(cancelled.allocations);
        long old = System.currentTimeMillis() - 11 * 60 * 1000;
        cancelled.cancelRequestedAt = old;
        cancelled.updatedAt = old;

        orders.selfHealAll(); // Cancelling physical work fences rather than freeing stock.
        assertEquals("needs_reconciliation", orders.statusOf(order.id));
        assertEquals(2, orders.reservedFor("s", "a").get("item:minecraft:diamond"));

        var fenced = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        time.set(fenced.get("updatedAt").getAsLong() + 1000);
        observe("a", 10);
        orders.selfHealAll();

        assertEquals("cancelled", orders.statusOf(order.id));
        assertEquals(0, orders.reservedFor("s", "a").getOrDefault("item:minecraft:diamond", 0L));
        var finished = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertTrue(finished.get("releasedAt").getAsLong() > 0);
    }

    @Test
    void unresolvedPhysicalOrderStaysProtectedUntilVerified() throws Exception {
        observe("a", 10);
        var order = stuckOrder("a");
        orders.selfHealAll();
        assertEquals("needs_reconciliation", orders.statusOf(order.id));
        assertEquals(2, orders.reservedFor("s", "a").get("item:minecraft:diamond"));
    }

    @Test
    void stashBlockersAreListedPerStash() throws Exception {
        observe("a", 10);
        var status = AddonRoutes.botStatus(JsonParser.parseString(
                        "{\"orderBlocker\":\"sorting paused\",\"deliveryStorage\":{\"autoExpand\":true,\"chests\":2,\"full\":2,\"canExpand\":false},\"rescanBlocked\":\"waiting\"}")
                .getAsJsonObject());
        store.heartbeat("s", "a", "agent", null, status);
        var blockers = store.stashBlockers();
        assertEquals(List.of("ORDER_BLOCKED", "DELIVERY_FULL", "RESCAN_BLOCKED"), blockers.get("a"));
    }

    @Test
    void approvalAndCancelQueuesAreCounted() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertEquals(0, orders.pendingApprovals(VIEWER));
        order.approval = OrderStore.Approval.PENDING;
        assertEquals(1, orders.pendingApprovals(VIEWER));
        order.approval = OrderStore.Approval.DIRECT;
        assertEquals(0, orders.cancelRequestedOpen(VIEWER));
        orders.claim("s", "a");
        orders.cancel(order.id);
        assertEquals(1, orders.cancelRequestedOpen(VIEWER));
    }

    @Test
    void reconcileAbandonReleasesHoldOnFreshObservation() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        var failed = orders.reconcile(stuck.id, "failed", "owner");
        assertEquals("failed", failed.status);
        assertEquals(OrderStage.FAILED, failed.stage);
        time.set(failed.updatedAt + 1000);
        observe("a", 10);
        orders.releaseFromFreshObservation("s");
        assertTrue(failed.releasedAt > 0, "hold released by the regular sweep");
    }

    @Test
    void fencedPhysicalWorkAutoFlagsOpenOrders() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        orders.claimPayload("s", "a", agent);
        var journal = new com.porter.wesort.inventory.PhysicalJournal(data.resolve("autoflag-client"));
        var context = JsonParser.parseString(
                        "{\"server\":\"s\",\"stash\":\"a\",\"dimension\":\"minecraft:overworld\",\"agent\":\"" + agent
                                + "\",\"kind\":\"DIRECT\",\"orderId\":\"" + order.id + "\"}")
                .getAsJsonObject();
        journal.start(context);
        var intent = new JsonObject();
        intent.add("origin", journal.current().getAsJsonObject("context"));
        intent.add(
                "before",
                JsonParser.parseString("{\"menu\":1,\"slots\":[\"\",\"\"],\"cursor\":\"\"}")
                        .getAsJsonObject());
        intent.add(
                "expected",
                JsonParser.parseString("{\"menu\":1,\"slots\":[\"\",\"\"],\"cursor\":\"\"}")
                        .getAsJsonObject());
        journal.intent(intent);
        var operations = new PhysicalOperations(ledger);
        operations.accept(agent, journal.current());
        orders.releaseFromFreshObservation("s");
        assertEquals("accepted", orders.statusOf(order.id));
        journal.block("Physical verification failed");
        operations.accept(agent, journal.current());
        orders.releaseFromFreshObservation("s");
        assertEquals("needs_reconciliation", orders.statusOf(order.id));
    }

    @Test
    void staleSilentCancelIsFulfilledAutomatically() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        orders.claim("s", "a");
        var cancelled = orders.cancel(order.id);
        assertEquals("accepted", cancelled.status);
        // Nothing held and the bot silent past the timeout: the human request executes itself.
        cancelled.allocations = null;
        long old = System.currentTimeMillis() - 11 * 60 * 1000;
        cancelled.cancelRequestedAt = old;
        cancelled.updatedAt = old;
        orders.releaseFromFreshObservation("s");
        assertEquals("cancelled", orders.statusOf(order.id));
    }
}

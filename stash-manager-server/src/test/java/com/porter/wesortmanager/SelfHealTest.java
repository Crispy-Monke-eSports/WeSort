package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The Manager resolves stuck orders itself: automatic rollback, protected holds and ops signals. */
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
        var legacy = orders.status(order.id, "in_progress", null, null, null, null, agent, "r1");
        legacy.stage = OrderStage.NEEDS_RECONCILIATION;
        legacy.status = "needs_reconciliation";
        return legacy;
    }

    @Test
    void healthTracksGeneratedTimeAndStuckOrdersWithoutExposingIdentities() throws Exception {
        var health = new RuntimeHealth(true);
        long now = time.get();
        var frame = index("private-stash", 10);
        frame.addProperty("generatedAt", now);
        store.putIndex(frame);
        health.sample("s", store, orders, now, 300_000);
        assertEquals("ready", health.detail(now).get("status").getAsString());
        health.sample("s", store, orders, now + 300_000, 300_000);
        assertTrue(health.detail(now + 300_000).get("staleIndex").getAsBoolean());
        assertEquals("degraded", health.detail(now + 300_000).get("status").getAsString());
        stuckOrder("private-stash");
        health.sample("s", store, orders, now, 300_000);
        var detail = health.detail(now);
        assertFalse(detail.get("staleIndex").getAsBoolean());
        assertEquals(1, detail.get("stuckOrders").getAsInt());
        assertFalse(detail.toString().contains("private-stash"));
        assertEquals("unknown", health.detail(now + 60_000).get("status").getAsString());
    }

    @Test
    void legacyRecoveryMigratesWithoutOwner() throws Exception {
        observe("a", 10);
        var old = stuckOrder("a");
        orders.selfHealAll();
        var row = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("RECOVERING", row.get("state").getAsString());
        assertEquals("in_progress", orders.statusOf(old.id));
        assertTrue(row.get("cancelRequested").getAsBoolean());
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
    }

    @Test
    void agentRollbackAcknowledgementWaitsForFreshRescan() throws Exception {
        observe("a", 10);
        var old = stuckOrder("a");
        orders.selfHealAll();
        var finished = orders.status(old.id, "cancelled", null, null, "Returned to stash", null, old.agent, "r2");
        assertEquals("cancelled", finished.status);
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
        time.set(finished.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals(
                0L,
                orders.reservedFor("s", "a")
                        .getOrDefault("item:minecraft:diamond", 0L)
                        .longValue());
    }

    @Test
    void freshObservationCannotFalselyCompleteRollback() throws Exception {
        observe("a", 10);
        var order = stuckOrder("a");
        time.set(order.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals("in_progress", orders.statusOf(order.id));
        var stored = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("RECOVERING", stored.get("state").getAsString());
        assertFalse(stored.has("releasedAt"));
        orders.selfHealAll();
        assertEquals("in_progress", orders.statusOf(order.id));
    }

    @Test
    void timedOutCancelRequestsRollbackAndBlocksNextOrder() throws Exception {
        observe("a", 10);
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        orders.claimPayload("s", "a", agent);
        var cancelled = orders.cancel(order.id);
        long old = System.currentTimeMillis() - 11 * 60 * 1000;
        cancelled.cancelRequestedAt = old;
        cancelled.updatedAt = old;
        orders.selfHealAll();
        var recovery = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("RECOVERING", recovery.get("state").getAsString());
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
        time.set(recovery.get("updatedAt").getAsLong() + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals("in_progress", orders.statusOf(order.id));

        var acknowledged = orders.status(order.id, "cancelled", null, null, "Returned to stash", null, agent, "done");
        time.set(acknowledged.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals("cancelled", orders.statusOf(order.id));
        assertEquals(
                0L,
                orders.reservedFor("s", "a")
                        .getOrDefault("item:minecraft:diamond", 0L)
                        .longValue());
    }

    @Test
    void unresolvedPhysicalOrderStaysProtectedUntilVerified() throws Exception {
        observe("a", 10);
        var order = stuckOrder("a");
        orders.selfHealAll();
        assertEquals("in_progress", orders.statusOf(order.id));
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
    }

    @Test
    void exactRestartResumeNeverClaimsAnotherOrderAndReturnsTerminalDisposition() throws Exception {
        observe("a", 10);
        var first = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        String agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
        var next = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertNull(orders.resumeClaim("s", "a", UUID.randomUUID().toString(), first.id));
        assertNull(orders.resumeClaim("s", "a", agent, "o-000000000000"));
        assertNull(orders.resumeClaim("s", "a", agent, next.id));
        var recovering = orders.resumeClaim("s", "a", agent, first.id);
        assertEquals(first.id, recovering.get("id").getAsString());
        assertEquals("RECOVERING", recovering.get("state").getAsString());
        assertTrue(recovering.get("cancelRequested").getAsBoolean());
        assertEquals("pending", orders.statusOf(next.id));

        var restarted = new OrderStore(data, store);
        restarted.load();
        assertEquals(
                "RECOVERING",
                restarted.resumeClaim("s", "a", agent, first.id).get("state").getAsString());
        restarted.status(first.id, "cancelled", null, null, "Returned", null, agent, "rollback-receipt");
        var terminal = restarted.resumeClaim("s", "a", agent, first.id);
        assertTrue(terminal.get("resumeComplete").getAsBoolean());
        assertEquals("CANCELLED", terminal.get("state").getAsString());
        assertEquals("pending", restarted.statusOf(next.id));
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
    void legacyRollbackRetainsHoldUntilAgentCompletesCleanup() throws Exception {
        observe("a", 10);
        var stuck = stuckOrder("a");
        orders.selfHealAll();
        time.set(System.currentTimeMillis() + 1000);
        observe("a", 10);
        orders.selfHealAll();
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
        var finished = orders.status(stuck.id, "failed", null, null, "Rolled back", null, stuck.agent, "r3");
        time.set(finished.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();
        var current = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertTrue(current.get("releasedAt").getAsLong() > 0);
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
        assertEquals("in_progress", orders.statusOf(order.id));
        assertEquals(
                "RECOVERING",
                orders.listJson("s", "a")
                        .getAsJsonArray("orders")
                        .get(0)
                        .getAsJsonObject()
                        .get("state")
                        .getAsString());
    }

    @Test
    void nextDeferredOrderClaimsAfterVerifiedRollbackInsteadOfWaitingForOwner() throws Exception {
        observe("a", 10);
        var first = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        var active = orders.claim("s", "a");
        assertEquals(first.id, active.id);
        var next = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertTrue(next.reservationDeferred);
        assertEquals(first.id, orders.claim("s", "a").id);

        var cancelled = orders.status(first.id, "cancelled", null, null, "Returned items", null);
        time.set(cancelled.updatedAt + 1000);
        observe("a", 10);
        orders.selfHealAll();

        var claimed = orders.claim("s", "a");
        assertNotNull(claimed);
        assertEquals(next.id, claimed.id);
        assertNotNull(claimed.allocations);
    }

    @Test
    void unavailableDeferredOrderDoesNotBlockLaterFulfillableOrder() throws Exception {
        observe("a", 10);
        var first = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        orders.claim("s", "a");
        var shortage = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 9)), null);
        var next = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 2)), null);
        assertTrue(shortage.reservationDeferred);
        assertTrue(next.reservationDeferred);

        var cancelled = orders.status(first.id, "cancelled", null, null, "Returned", null);
        time.set(cancelled.updatedAt + 1000);
        observe("a", 5);
        orders.selfHealAll();

        assertEquals(next.id, orders.claim("s", "a").id);
        assertEquals("pending", orders.statusOf(shortage.id));
        assertEquals(
                2L, orders.reservedFor("s", "a").get("item:minecraft:diamond").longValue());
        var finished = orders.status(next.id, "cancelled", null, null, "Returned", null);
        time.set(finished.updatedAt + 1000);
        observe("a", 5);
        orders.selfHealAll();
        assertNull(orders.claim("s", "a"));
        assertTrue(orders.heldRoots("s").isEmpty());

        // Simulate the retry deadline across a Manager restart without sleeping.
        JsonObject book = OrderBookFixtures.read(store, "s", "a");
        for (var entry : book.getAsJsonArray("orders")) {
            var row = entry.getAsJsonObject();
            if (shortage.id.equals(row.get("id").getAsString()))
                row.addProperty(
                        "deferredBlockedAt", System.currentTimeMillis() - OrderStore.DEFERRED_RETRY_AFTER_MS - 1000);
        }
        OrderBookFixtures.replace(store, book);
        var restarted = new OrderStore(data, store);
        restarted.load();
        assertNull(restarted.claim("s", "a"));
        assertEquals("failed", restarted.statusOf(shortage.id));
        assertTrue(restarted.heldRoots("s").isEmpty());
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

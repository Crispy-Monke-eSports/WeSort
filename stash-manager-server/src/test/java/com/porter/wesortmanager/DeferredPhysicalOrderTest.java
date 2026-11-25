package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import com.porter.wesort.inventory.PhysicalJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredPhysicalOrderTest {
    @TempDir
    Path data;

    IndexStore index;
    OrderStore orders;

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        var indexJson = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                .getAsJsonObject();
        var physical = StockFixtures.physical(
                indexJson,
                index.stock().epoch(),
                index.stock().revision(),
                index.stock().revision() + 1,
                0);
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slot = new PhysicalSlots.Slot(0, 64, diamond);
        physical.addProperty("physicalVersion", 3);
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.addProperty("snbt", new PhysicalSlots.Snapshot(27, List.of(slot)).snbt());
        source.addProperty("fingerprint", new PhysicalSlots.Snapshot(27, List.of(slot)).fingerprint());
        index.putIndex(physical);
        orders = new OrderStore(data, index);
        orders.load();
    }

    private List<OrderStore.Line> diamonds(int count) {
        return List.of(new OrderStore.Line("item", "minecraft:diamond", count));
    }

    @Test
    void pendingApprovalExpiresAutomaticallyWithoutAnyHuman() throws Exception {
        // #59: no human can ever approve. A PENDING approval must default-deny after
        // a bounded wait instead of gating shared stock and the queue forever.
        var clock = new AtomicLong(System.currentTimeMillis());
        var timed = new OrderStore(data, index, clock::get);
        timed.load();
        var placed = timed.submit(
                        "s",
                        "a",
                        diamonds(20),
                        null,
                        null,
                        null,
                        null,
                        OrderIntent.DEFAULT,
                        null,
                        true,
                        ignored -> null)
                .order();
        assertEquals(OrderStore.Approval.PENDING, placed.approval);
        assertEquals("pending", placed.status);
        assertNull(timed.claim("s", "a"));
        // Still pending inside the deadline: no human, no decision, no expiry yet.
        var waiting = timed.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("pending", waiting.get("status").getAsString());
        clock.addAndGet(OrderStore.AUTO_CANCEL_AFTER_MS + 1);
        assertNull(timed.claim("s", "a"));
        var cancelled = timed.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("cancelled", cancelled.get("status").getAsString());
        assertEquals("REJECTED", cancelled.get("approvalState").getAsString());
        assertTrue(cancelled.get("message").getAsString().contains("automatically"));
    }

    @Test
    void anotherOrderIsQueuedWithoutAddingPhysicalReservations() throws Exception {
        var first = orders.place("s", "a", diamonds(20), null);
        var active = orders.claim("s", "a");
        assertEquals(first.id, active.id);
        assertNotNull(active.allocations);
        int holdsBefore = orders.heldRoots("s").size();

        // Previously this threw 409: "Wait for the active physical order..."
        var second = orders.place("s", "a", diamonds(5), null);
        assertEquals("pending", second.status);
        assertEquals(OrderStage.CREATED, second.stage());
        assertTrue(second.reservationDeferred);
        assertNull(second.allocations);
        assertTrue(second.toJson().get("reservationDeferred").getAsBoolean());
        assertEquals(holdsBefore, orders.heldRoots("s").size());
        assertEquals(active.id, orders.claim("s", "a").id);

        // No duplicate hold is charged against stock until the next actual claim.
        var balances = orders.listJson("s", "a").getAsJsonObject("available");
        // A physical allocation protects its complete root slot even when the
        // order uses only 20 out of that 64-stack; no misleading availability.
        var availableItems = balances.getAsJsonObject("items");
        assertTrue(!availableItems.has("minecraft:diamond")
                || availableItems.get("minecraft:diamond").getAsLong() == 0);

        var restarted = new OrderStore(data, index);
        restarted.load();
        var snapshots = restarted.listJson("s", "a").getAsJsonArray("orders");
        var pending = snapshots.get(0).getAsJsonObject();
        assertEquals(second.id, pending.get("id").getAsString());
        assertTrue(pending.get("reservationDeferred").getAsBoolean());
        assertEquals("CREATED", pending.get("state").getAsString());

        var cancelled = restarted.cancel(second.id);
        assertEquals("cancelled", cancelled.status);
        assertEquals(holdsBefore, restarted.heldRoots("s").size());
    }

    @Test
    void activePhysicalJournalOnlyRequestsAutomaticRollbackWhenFenced() throws Exception {
        var placed = orders.place("s", "a", diamonds(20), null);
        var claimed = orders.claim("s", "a");
        assertEquals(placed.id, claimed.id);
        assertEquals("accepted", claimed.status);

        String agent = "00000000-0000-0000-0000-000000000001";
        var journal = new PhysicalJournal(data.resolve("active-order-journal"));
        journal.start(JsonParser.parseString(
                        "{\"server\":\"s\",\"stash\":\"a\",\"dimension\":\"minecraft:overworld\",\"agent\":\"" + agent
                                + "\",\"kind\":\"DIRECT\",\"orderId\":\"" + placed.id + "\"}")
                .getAsJsonObject());
        var operations = new PhysicalOperations(index.stock());
        var planned = operations.accept(agent, journal.current());
        assertEquals("PLANNED", planned.get("state").getAsString());
        var queued = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("PACKING", queued.get("state").getAsString());

        var intent = new JsonObject();
        intent.add("origin", journal.context());
        intent.add("before", JsonParser.parseString("{\"menu\":1,\"slots\":[\"before\",\"\"],\"cursor\":\"\"}"));
        intent.add("expected", JsonParser.parseString("{\"menu\":1,\"slots\":[\"after\",\"\"],\"cursor\":\"\"}"));
        journal.intent(intent);
        assertEquals(
                "IN_PROGRESS",
                operations.accept(agent, journal.current()).get("state").getAsString());
        assertTrue(index.stock().unsettledPhysicalOrder(placed.id));

        // The dashboard polls while the agent physically stages the package.
        // An unfinished but healthy operation must never invalidate the order.
        var running = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("PACKING", running.get("state").getAsString());
        assertEquals("accepted", running.get("status").getAsString());
        assertFalse(index.stock().reconciliationRequiredPhysicalOrder(placed.id));

        // A genuine Manager fence still blocks pickup and keeps reservations held.
        journal.block("The physical postcondition could not be verified");
        assertEquals(
                "NEEDS_RECONCILIATION",
                operations.accept(agent, journal.current()).get("state").getAsString());
        var fenced = orders.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("RECOVERING", fenced.get("state").getAsString());
        assertEquals("in_progress", fenced.get("status").getAsString());
        assertFalse(orders.heldRoots("s").isEmpty());
    }

    @Test
    void longStuckPhysicalCancelRequestsProtectedAutomaticRollbackAfterRestart() throws Exception {
        var first = orders.place("s", "a", diamonds(20), null);
        assertNotNull(orders.claim("s", "a").allocations);
        var cancellation = orders.cancel(first.id);
        assertTrue(cancellation.cancelRequested);

        JsonObject book = OrderBookFixtures.read(index, "s", "a");
        JsonObject order = book.getAsJsonArray("orders").get(0).getAsJsonObject();
        long stale = System.currentTimeMillis() - OrderStore.AUTO_CANCEL_AFTER_MS - 60_000;
        order.addProperty("cancelRequestedAt", stale);
        order.addProperty("updatedAt", stale);
        OrderBookFixtures.replace(index, book);

        var restarted = new OrderStore(data, index);
        restarted.load();
        JsonObject current =
                restarted.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("in_progress", current.get("status").getAsString());
        assertEquals("RECOVERING", current.get("state").getAsString());
        assertTrue(current.get("cancelRequested").getAsBoolean());
        assertTrue(current.get("allocations").isJsonArray());
        // Do not silently release a physical hold when the bot was unreachable.
        assertFalse(restarted.heldRoots("s").isEmpty());
    }
}

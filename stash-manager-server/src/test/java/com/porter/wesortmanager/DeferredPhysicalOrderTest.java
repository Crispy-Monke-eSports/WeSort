package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        var data = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                .getAsJsonObject();
        var physical = StockFixtures.physical(
                data,
                index.stock().epoch(),
                index.stock().revision(),
                index.stock().revision() + 1,
                0);
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slot = new PhysicalSlots.Slot(0, 64, diamond);
        var writer = new PhysicalSlots.Writer();
        var slotRows = writer.slots(List.of(slot));
        physical.addProperty("physicalVersion", 2);
        physical.add("slotVariants", writer.variants());
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.add("slots", slotRows);
        source.addProperty("fingerprint", new PhysicalSlots.Snapshot(27, List.of(slot)).fingerprint());
        index.putIndex(physical);
        orders = new OrderStore(data, index);
        orders.load();
    }

    private List<OrderStore.Line> diamonds(int count) {
        return List.of(new OrderStore.Line("item", "minecraft:diamond", count));
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
        assertEquals(
                0, balances.getAsJsonObject("items").get("minecraft:diamond").getAsLong());

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
    void longStuckPhysicalCancelBecomesProtectedReconciliationAfterRestart() throws Exception {
        var first = orders.place("s", "a", diamonds(20), null);
        assertNotNull(orders.claim("s", "a").allocations);
        var cancellation = orders.cancel(first.id);
        assertTrue(cancellation.cancelRequested);

        Path saved;
        try (var files = Files.list(data.resolve("orders"))) {
            saved = files.filter(p -> p.toString().endsWith(".json"))
                    .findFirst()
                    .orElseThrow();
        }
        JsonObject book = JsonParser.parseString(Files.readString(saved)).getAsJsonObject();
        JsonObject order = book.getAsJsonArray("orders").get(0).getAsJsonObject();
        long stale = System.currentTimeMillis() - OrderStore.AUTO_CANCEL_AFTER_MS - 60_000;
        order.addProperty("cancelRequestedAt", stale);
        order.addProperty("updatedAt", stale);
        Files.writeString(saved, book.toString());

        var restarted = new OrderStore(data, index);
        restarted.load();
        JsonObject current =
                restarted.listJson("s", "a").getAsJsonArray("orders").get(0).getAsJsonObject();
        assertEquals("needs_reconciliation", current.get("status").getAsString());
        assertEquals("NEEDS_RECONCILIATION", current.get("state").getAsString());
        assertFalse(current.get("cancelRequested").getAsBoolean());
        assertTrue(current.get("allocations").isJsonArray());
        // Do not silently release a physical hold when the bot was unreachable.
        assertFalse(restarted.heldRoots("s").isEmpty());
    }
}

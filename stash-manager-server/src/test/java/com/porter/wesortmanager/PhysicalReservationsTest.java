package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A reservation must survive its stack growing, and die loudly otherwise. */
class PhysicalReservationsTest {
    @TempDir
    Path data;

    IndexStore index;
    OrderStore orders;

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        putStock(10);
        orders = new OrderStore(data, index);
        orders.load();
    }

    private void putStock(int count) throws Exception {
        var indexJson = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":" + count + ",\"maxStack\":64}]}")
                .getAsJsonObject();
        var ledger = index.stock();
        var physical = StockFixtures.physical(
                indexJson, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var diamond = new PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slot = new PhysicalSlots.Slot(0, count, diamond);
        var writer = new PhysicalSlots.Writer();
        var slotRows = writer.slots(List.of(slot));
        physical.addProperty("physicalVersion", 2);
        physical.add("slotVariants", writer.variants());
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.add("slots", slotRows);
        source.addProperty("fingerprint", new PhysicalSlots.Snapshot(27, List.of(slot)).fingerprint());
        index.putIndex(physical);
    }

    private OrderStore.Order placed(int count) throws Exception {
        var order = orders.place(
                "s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", count)), null);
        assertNotNull(order.allocations, "test setup: place must allocate against fresh stock");
        return order;
    }

    private PhysicalReservations reservations() throws Exception {
        return PhysicalReservations.read(index.stock(), "s", "a");
    }

    @Test
    void grownStackKeepsItsReservation() throws Exception {
        var order = placed(2);
        assertTrue(reservations().matches(order.allocations));
        putStock(11);
        // Same item, more units, newer revision: the claim must still verify.
        assertTrue(reservations().matches(order.allocations));
        var agent = UUID.randomUUID().toString();
        assertNotNull(orders.claimPayload("s", "a", agent));
    }

    @Test
    void shrunkStackLosesItsReservation() throws Exception {
        var order = placed(2);
        putStock(1);
        assertFalse(reservations().matches(order.allocations));
        var agent = UUID.randomUUID().toString();
        assertNull(orders.claimPayload("s", "a", agent));
    }

    @Test
    void swappedItemLosesItsReservation() throws Exception {
        var order = placed(2);
        // Same slot, same count, different item.
        var indexJson = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:emerald\",\"id\":\"minecraft:emerald\",\"count\":10,\"maxStack\":64}]}")
                .getAsJsonObject();
        var ledger = index.stock();
        var physical = StockFixtures.physical(
                indexJson, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var emerald = new PhysicalSlots.Item(
                "minecraft:emerald", "minecraft:emerald", 10, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slot = new PhysicalSlots.Slot(0, 10, emerald);
        var writer = new PhysicalSlots.Writer();
        var slotRows = writer.slots(List.of(slot));
        physical.addProperty("physicalVersion", 2);
        physical.add("slotVariants", writer.variants());
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.add("slots", slotRows);
        source.addProperty("fingerprint", new PhysicalSlots.Snapshot(27, List.of(slot)).fingerprint());
        index.putIndex(physical);
        assertFalse(reservations().matches(order.allocations));
    }

    @Test
    void siblingHoldsAreExcludedBeforeComparing() throws Exception {
        var order = placed(2);
        putStock(11);
        var alloc = order.allocations.get(0);
        var sibling = new PhysicalReservations.Allocation(
                alloc.source(), alloc.path(), alloc.ref(), 10, alloc.fingerprint(), alloc.revision(),
                alloc.itemFingerprint());
        // 11 units, 10 held elsewhere: 1 left is not enough for 2.
        assertFalse(reservations().matches(order.allocations, List.of(sibling)));
        // Without the sibling the grown stack covers the reservation.
        assertTrue(reservations().matches(order.allocations, List.of()));
    }

    @Test
    void doomedReservationCountsAsStuck() throws Exception {
        var order = placed(2);
        putStock(11);
        // Break the fingerprint a different way: shrink below the reservation.
        putStock(1);
        long now = order.updatedAt + OrderStore.AUTO_CANCEL_AFTER_MS + 1;
        assertEquals(1, orders.stuckOrders("s", now));
    }

    @Test
    void freshReservationDoesNotCountAsStuck() throws Exception {
        placed(2);
        assertEquals(0, orders.stuckOrders("s", System.currentTimeMillis()));
    }
}

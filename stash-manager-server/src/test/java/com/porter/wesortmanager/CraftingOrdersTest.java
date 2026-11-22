package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract checks for manufacturing from fresh, root-reserved 26.3 stock. */
class CraftingOrdersTest {
    @TempDir
    Path data;

    private static JsonObject index(String stash, int nuggets) {
        return JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"" + stash
                        + "\",\"items\":[{\"key\":\"minecraft:iron_nugget\",\"id\":\"minecraft:iron_nugget\",\"count\":"
                        + nuggets + ",\"maxStack\":64}]}")
                .getAsJsonObject();
    }

    private void observe(IndexStore store, String stash, int nuggets) throws Exception {
        var item = new PhysicalSlots.Item(
                "minecraft:iron_nugget", "minecraft:iron_nugget", 64, "{}", PhysicalSlots.Kind.ITEM, 0, List.of(), "");
        var slots = new java.util.ArrayList<PhysicalSlots.Slot>();
        for (int left = nuggets, slot = 0; left > 0; slot++) {
            int n = Math.min(64, left);
            slots.add(new PhysicalSlots.Slot(slot, n, item));
            left -= n;
        }
        var frame = new PhysicalSlots.Snapshot(27, slots);
        var ledger = store.stock();
        var ix = StockFixtures.physical(
                index(stash, nuggets), ledger.epoch(), ledger.revision(), ledger.revision() + 1, stash.hashCode());
        var source = ix.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        var writer = new PhysicalSlots.Writer();
        source.add("slots", writer.slots(frame.slots()));
        source.addProperty("fingerprint", frame.fingerprint());
        ix.add("slotVariants", writer.variants());
        ix.addProperty("physicalVersion", 2);
        store.putIndex(ix);
        store.heartbeat(
                "s",
                stash,
                "bot",
                null,
                new IndexStore.BotStatus(
                        false,
                        null,
                        false,
                        null,
                        -1,
                        false,
                        null,
                        false,
                        null,
                        true,
                        new IndexStore.Crafting(true, true, "manager")));
    }

    private OrderStore.Order place(OrderStore orders, String stash, String type, long count) throws Exception {
        return orders.submit(
                        "s",
                        stash,
                        List.of(new OrderStore.Line(type, "minecraft:iron_block", count)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        OrderIntent.DEFAULT,
                        null,
                        false,
                        ignored -> null)
                .order();
    }

    @Test
    void reservesNuggetsInsteadOfNonexistentIronBlocks() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        observe(store, "a", 200);
        var orders = new OrderStore(data, store);
        orders.load();
        assertEquals(
                2,
                orders.listJson("s", "a")
                        .getAsJsonObject("available")
                        .getAsJsonObject("craftables")
                        .get("minecraft:iron_block")
                        .getAsInt());

        var order = place(orders, "a", "craft", 2);
        assertEquals("craft", order.lines.getFirst().type());
        assertEquals(162L, orders.reservedFor("s", "a").get("item:minecraft:iron_nugget"));
        assertNotNull(order.craftPlans);
        assertEquals(2, order.craftPlans.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(
                162,
                order.craftPlans
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("materials")
                        .get("minecraft:iron_nugget")
                        .getAsInt());
        assertEquals(1, OrderStore.explodeOrderLines(order).size());
        assertThrows(OrderStore.OrderException.class, () -> place(orders, "a", "craft", 1));
    }

    @Test
    void cannotOrderCraftingWhenTableNotReadyOrOnlyAggregateStockExists() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        StockFixtures.put(store, index("a", 200));
        var orders = new OrderStore(data, store);
        orders.load();
        assertThrows(OrderStore.OrderException.class, () -> place(orders, "a", "craft", 1));
    }
}

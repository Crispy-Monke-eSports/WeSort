package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bot that was assigned an order and then restarted before persisting the order id sees an empty
 * pending count (the order is no longer pending) and would never ask again. The heartbeat therefore
 * names the agent's own unfinished assignments so it can resume exactly its own order.
 */
class OrderHeartbeatTest {
    @TempDir
    Path data;

    private IndexStore storeWithPhysicalSlots() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE);
        store.load();
        var index = JsonParser.parseString(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                .getAsJsonObject();
        var ledger = store.stock();
        var physical = StockFixtures.physical(index, ledger.epoch(), ledger.revision(), ledger.revision() + 1, 0);
        var diamond = new com.porter.wesort.data.PhysicalSlots.Item(
                "minecraft:diamond", "minecraft:diamond", 64, "{}", com.porter.wesort.data.PhysicalSlots.Kind.ITEM, 0,
                List.of(), "");
        var slots = List.of(new com.porter.wesort.data.PhysicalSlots.Slot(0, 64, diamond));
        var writer = new com.porter.wesort.data.PhysicalSlots.Writer();
        var slotRows = writer.slots(slots);
        physical.addProperty("physicalVersion", 2);
        physical.add("slotVariants", writer.variants());
        var source = physical.getAsJsonArray("physicalSources").get(0).getAsJsonObject();
        source.add("slots", slotRows);
        source.addProperty(
                "fingerprint", new com.porter.wesort.data.PhysicalSlots.Snapshot(27, slots).fingerprint());
        store.putIndex(physical);
        return store;
    }

    @Test
    void heartbeatNamesAgentsOwnUnfinishedOrders() throws Exception {
        var store = storeWithPhysicalSlots();
        var orders = new OrderStore(data, store);
        orders.load();
        var placed = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNotNull(placed);
        String agentA = UUID.randomUUID().toString();
        String agentB = UUID.randomUUID().toString();
        var claimed = orders.claimPayload("s", "a", agentA);
        assertNotNull(claimed, "claim needs the physical stock above");
        String id = claimed.get("id").getAsString();

        // Legacy view is unchanged: claimed orders are not pending, and there is no mine key.
        var legacy = orders.heartbeatJson("s", "a");
        assertEquals(0, legacy.get("pending").getAsInt());
        assertFalse(legacy.has("mine"));

        // The assigned agent sees its own order and can resume it after losing the id.
        var mine = orders.heartbeatJson("s", "a", agentA);
        assertEquals(0, mine.get("pending").getAsInt());
        assertTrue(mine.has("mine"));
        assertEquals(1, mine.getAsJsonArray("mine").size());
        assertEquals(id, mine.getAsJsonArray("mine").get(0).getAsString());
        var resume = orders.resumeClaim("s", "a", agentA, id);
        assertNotNull(resume);

        // Another agent sees an empty list, and other stashes see nothing either.
        var foreign = orders.heartbeatJson("s", "a", agentB);
        assertTrue(foreign.has("mine"));
        assertEquals(0, foreign.getAsJsonArray("mine").size());
        var elsewhere = orders.heartbeatJson("s", "b", agentA);
        assertTrue(elsewhere.has("mine"));
        assertEquals(0, elsewhere.getAsJsonArray("mine").size());
    }
}

package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.porter.wesort.data.PhysicalSlots;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A silent agent must be noticed without anyone opening the operations view: the background
 * sweep expires quiet operations so the next self-heal migrates the order to RECOVERING.
 */
class PhysicalOperationsSweepTest {
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

    private OrderStore.Order acceptedOrder() throws Exception {
        var order = orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        var claimed = orders.claim("s", "a");
        assertNotNull(claimed);
        assertEquals(order.id, claimed.id);
        return claimed;
    }

    private void plantOperation(String orderId, long lastSeen) throws Exception {
        var payload = JsonParser.parseString("{\"id\":\"" + UUID.randomUUID() + "\",\"revision\":1,\"checksum\":\"c\","
                        + "\"state\":\"IN_PROGRESS\",\"context\":{\"orderId\":\"" + orderId + "\"}}")
                .getAsJsonObject();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("INSERT INTO physical_operations VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, UUID.randomUUID().toString());
            p.setString(2, "s");
            p.setString(3, "agent");
            p.setString(4, "a");
            p.setLong(5, 1);
            p.setString(6, "c");
            p.setString(7, payload.toString());
            p.setString(8, "IN_PROGRESS");
            p.setLong(9, lastSeen);
            p.setLong(10, 0);
            p.executeUpdate();
        }
    }

    private com.google.gson.JsonObject reload(String id) throws Exception {
        var restarted = new OrderStore(data, index);
        restarted.load();
        for (var e : restarted.listJson("s", "a").getAsJsonArray("orders")) {
            var o = e.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) return o;
        }
        return null;
    }

    @Test
    void silentOperationMigratesWithoutOpeningTheOperationsView() throws Exception {
        var order = acceptedOrder();
        plantOperation(order.id, System.currentTimeMillis() - 60_000);
        // No dashboard list(), no heartbeat: just the periodic sweep.
        orders.selfHealAll();
        var healed = reload(order.id);
        assertNotNull(healed);
        assertEquals("RECOVERING", healed.get("state").getAsString());
        assertTrue(healed.get("cancelRequested").getAsBoolean());
    }

    @Test
    void freshKeepaliveCannotProtectOperationWithFencedLease() throws Exception {
        var order = acceptedOrder();
        long now = System.currentTimeMillis();
        plantOperation(order.id, now);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var p = c.prepareStatement("INSERT INTO automation_leases VALUES(?,?,?,?,?)")) {
            p.setString(1, "s");
            p.setString(2, "agent");
            p.setString(3, "token");
            p.setLong(4, now + 60_000);
            p.setString(5, "NEEDS_RECONCILIATION");
            p.executeUpdate();
        }
        new PhysicalOperations(index.stock(), () -> now).sweepStale();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("guild.sqlite"));
                var q = c.createStatement();
                var rows = q.executeQuery("SELECT state,fence_revision FROM physical_operations")) {
            assertTrue(rows.next());
            assertEquals("NEEDS_RECONCILIATION", rows.getString(1));
            assertEquals(1, rows.getLong(2));
        }
    }

    @Test
    void freshOperationIsLeftAlone() throws Exception {
        var order = acceptedOrder();
        plantOperation(order.id, System.currentTimeMillis());
        orders.selfHealAll();
        var untouched = reload(order.id);
        assertNotNull(untouched);
        assertEquals("PACKING", untouched.get("state").getAsString());
    }
}

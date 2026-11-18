package com.porter.wesortmanager;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class AutomationGateTest {
    @TempDir Path data;
    private IndexStore store() throws Exception {
        var store = new IndexStore(data, CreativeTabs.NONE); store.load();
        StockFixtures.put(store, JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64}]}").getAsJsonObject());
        return store;
    }
    @Test void schemaOneUpgradesWithoutLosingStockAndNewerSchemasAreRejected() throws Exception {
        var store = store();
        try (var c = store.stock().open(); var sql = c.createStatement()) {
            sql.execute("DROP TABLE automation_leases"); sql.execute("PRAGMA user_version=1");
        }
        var upgraded = new StockLedger(data);
        assertEquals(64, upgraded.sources().getFirst().value().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        try (var c = upgraded.open(); var sql = c.createStatement()) {
            try (var rows = sql.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(7, rows.getInt(1)); }
            try (var rows = sql.executeQuery("SELECT count(*) FROM automation_leases")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            sql.execute("PRAGMA user_version=8");
        }
        assertThrows(java.io.IOException.class, () -> new StockLedger(data));
    }
    @Test void simultaneousSortingAndReservationHaveOneWinner() throws Exception {
        var store = store(); var orders = new OrderStore(data, store); orders.load();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.of(
                () -> orders.acquireSorting("s", "agent", null) != null,
                () -> { try { orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null); return true; }
                    catch (OrderStore.OrderException e) { return false; } }));
            assertEquals(1, results.stream().filter(result -> { try { return Boolean.TRUE.equals(result.get()); } catch (Exception e) { throw new RuntimeException(e); } }).count());
        }
    }
    @Test void reservedItemsBlockAllSortingAndSecondAgentCannotJoinALease() throws Exception {
        var store = store(); var orders = new OrderStore(data, store); orders.load();
        orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null);
        assertNull(orders.acquireSorting("s", "agent", null));
        var lease = orders.acquireSorting("another-server", "agent", null); assertNotNull(lease);
        assertNull(orders.acquireSorting("another-server", "other-agent", lease.token()));
    }
    @Test void expiredOrInterruptedSortingStaysFencedAcrossRestartAndCannotBeLateReleased() throws Exception {
        var store = store(); var clock = new AtomicLong(1000); var gate = new AutomationGate(store.stock(), clock::get);
        var lease = gate.acquire("s", "agent", null); assertNotNull(lease);
        clock.set(1000 + AutomationGate.TTL);
        assertEquals("NEEDS_RECONCILIATION", gate.current("s").state());
        assertEquals("STALE", store.stock().sources().getFirst().status());
        var restarted = new AutomationGate(store.stock(), clock::get);
        assertNull(restarted.acquire("s", "other", null));
        assertFalse(restarted.finish("s", "agent", lease.token(), true));
    }
    @Test void fastManagerRestartFencesEvenAnUnexpiredSortingToken()throws Exception {
        var store=store();var lease=store.automation().acquire("s","agent",null);assertNotNull(lease);
        var restarted=new StockLedger(data);var gate=new AutomationGate(restarted);
        assertEquals("NEEDS_RECONCILIATION",gate.current("s").state());
        assertNull(gate.acquire("s","agent",lease.token()));assertFalse(gate.finish("s","agent",lease.token(),true));
        assertNotEquals("FRESH",restarted.sources().getFirst().status());
    }
    @Test void verifiedReleaseInvalidatesCachedProofBeforeAllowingNewReservations() throws Exception {
        var store = store(); var orders = new OrderStore(data, store); orders.load();
        var gate = store.automation(); var lease = orders.acquireSorting("s", "agent", null);
        assertTrue(gate.finish("s", "agent", lease.token(), true));
        assertNull(gate.current("s")); assertEquals("STALE", store.stock().sources().getFirst().status());
        assertThrows(OrderStore.OrderException.class, () -> orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", 1)), null));
    }
}

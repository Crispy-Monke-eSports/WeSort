package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StockAvailabilityTest {
    @TempDir
    Path data;

    private final AtomicLong time = new AtomicLong(1000);
    private StockLedger ledger;
    private IndexStore store;
    private OrderStore orders;
    private final Users.Principal owner = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);

    @BeforeEach
    void initialize() throws Exception {
        ledger = new StockLedger(data, time::get, 100);
        store = new IndexStore(data, CreativeTabs.NONE, ledger);
        store.load();
        orders = new OrderStore(data, store);
        orders.load();
    }

    private JsonObject index(String stash, long count) {
        return JsonParser.parseString("{\"version\":1,\"server\":\"s\",\"stash\":\"" + stash
                        + "\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":" + count
                        + "}]}")
                .getAsJsonObject();
    }

    private void observe(String stash, long count, int x) throws Exception {
        store.putIndex(StockFixtures.physical(
                index(stash, count), ledger.epoch(), ledger.revision(), ledger.revision() + 1, x));
    }

    private long available() {
        return store.orderCounts("s", "a").total("item", "minecraft:diamond");
    }

    private JsonObject balance(Users.Principal principal) throws Exception {
        return new StockRoutes(store, orders)
                .view(principal)
                .getAsJsonArray("items")
                .get(0)
                .getAsJsonObject();
    }

    private void order(long count) throws Exception {
        orders.place("s", "a", List.of(new OrderStore.Line("item", "minecraft:diamond", count)), null);
    }

    @Test
    void emptyPhysicalStashRequestsOneInitialScanAndCanAcknowledgeIt() throws Exception {
        var empty = index("empty", 0);
        empty.addProperty("physicalVersion", 1);
        empty.add("physicalSources", new JsonArray());
        store.putIndex(empty);
        var first = store.heartbeat("s", "empty", "agent", null, IndexStore.BotStatus.NONE);
        assertNotNull(first.rescan());
        assertEquals(
                first.rescan(),
                store.heartbeat("s", "empty", "agent", null, IndexStore.BotStatus.NONE)
                        .rescan());
        var ack = new IndexStore.BotStatus(
                false, null, false, null, first.rescan(), false, null, false, null, false, null);
        assertNull(store.heartbeat("s", "empty", "agent", null, ack).rescan());
    }

    @Test
    void protectedPolicyCannotBeIgnoredOrClearedByAReplayedWitness() throws Exception {
        var original = StockFixtures.physical(index("a", 64), ledger.epoch(), 0, 1, 0);
        store.putIndex(original);
        var protectedCopy = original.deepCopy();
        protectedCopy.getAsJsonArray("physicalSources").get(0).getAsJsonObject().addProperty("protected", true);
        store.putIndex(protectedCopy);
        assertEquals("PROTECTED", ledger.sources().getFirst().status());
        assertEquals(0, available());
        assertThrows(OrderStore.OrderException.class, () -> order(1));
        assertThrows(StockLedger.Conflict.class, () -> store.putIndex(original));
    }

    @Test
    void aggregateWithoutPhysicalWitnessIsReadableButNotOrderable() throws Exception {
        store.putIndex(index("a", 64));
        assertNotNull(store.index("s", "a"));
        assertEquals(0, available());
        assertThrows(OrderStore.OrderException.class, () -> order(1));
    }

    @Test
    void expiredSourceIsUnknownAndRejectsReservationAndClaim() throws Exception {
        observe("a", 64, 0);
        order(10);
        assertNotNull(orders.claim("s", "a"));
        time.set(1100);
        assertEquals(0, available());
        assertNull(orders.claim("s", "a"));
        assertThrows(OrderStore.OrderException.class, () -> order(1));
        var row = balance(owner);
        assertEquals(64, row.get("observed").getAsLong());
        assertEquals(64, row.get("unknown").getAsLong());
        assertEquals(0, row.get("available").getAsLong());
        observe("a", 64, 0);
        assertNotNull(orders.claim("s", "a"));
    }

    @Test
    void freshBalanceSeparatesObservedReservedAndAvailable() throws Exception {
        observe("a", 64, 0);
        order(10);
        var row = balance(owner);
        var view = new StockRoutes(store, orders).view(owner);
        var perStash = view.getAsJsonArray("stashes")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("items")
                .get(0)
                .getAsJsonObject();
        assertEquals(row, perStash);
        assertTrue(view.getAsJsonArray("sources")
                .get(0)
                .getAsJsonObject()
                .get("reserved")
                .isJsonNull());
        assertEquals(64, row.get("observed").getAsLong());
        assertEquals(10, row.get("reserved").getAsLong());
        assertEquals(54, row.get("available").getAsLong());
        assertEquals(0, row.get("unknown").getAsLong());
    }

    @Test
    void concurrentClaimsCannotAssignTwoStashesToTheSameAgent() throws Exception {
        observe("a", 64, 0);
        observe("b", 64, 2);
        var lines = List.of(new OrderStore.Line("item", "minecraft:diamond", 1));
        orders.place("s", "a", lines, null);
        orders.place("s", "b", lines, null);
        String agent = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> orders.claimPayload("s", "a", agent));
            var second = pool.submit(() -> orders.claimPayload("s", "b", agent));
            var a = first.get();
            var b = second.get();
            assertTrue((a == null) != (b == null));
            String assigned = a == null ? "b" : "a";
            String waiting = a == null ? "a" : "b";
            var claimed = a == null ? b : a;
            assertEquals(agent, claimed.get("agent").getAsString());
            assertEquals(
                    claimed.get("id"), orders.claimPayload("s", assigned, agent).get("id"));
            assertNull(orders.claimPayload("s", assigned, other));
            assertNotNull(orders.claimPayload("s", waiting, other));
        }
    }

    @Test
    void sharedBalancesCountEachSourceOnceAndScopeLimitsLocationDetails() throws Exception {
        observe("a", 64, 0);
        observe("b", 64, 0);
        var second =
                StockFixtures.physical(index("b", 32), ledger.epoch(), ledger.revision(), ledger.revision() + 1, 2);
        second.getAsJsonArray("physicalSources")
                .add(StockFixtures.physical(index("b", 64), ledger.epoch(), ledger.revision(), ledger.revision() + 2, 0)
                        .getAsJsonArray("physicalSources")
                        .get(0));
        store.putIndex(second);
        assertEquals(96, balance(owner).get("observed").getAsLong());
        var restricted = new Users.Principal(
                "u",
                "Reader",
                false,
                Users.Perms.of(
                                true,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                List.of(new Users.StashRef("s", "a")),
                                List.of())
                        .withPositions(true),
                false);
        assertEquals(96, balance(restricted).get("observed").getAsLong());
        assertEquals(0, available());
        assertEquals(
                1,
                new StockRoutes(store, orders)
                        .view(restricted)
                        .getAsJsonArray("sources")
                        .size());
    }

    @Test
    void sqlSnapshotSurvivesMissingJsonMirrorButRequiresRescanAfterRestart() throws Exception {
        observe("a", 64, 0);
        try (var files = Files.list(data.resolve("indexes"))) {
            for (var path : files.toList()) Files.delete(path);
        }
        var restarted = new IndexStore(data, CreativeTabs.NONE);
        restarted.load();
        assertNotNull(restarted.index("s", "a"));
        assertEquals(0, restarted.orderCounts("s", "a").total("item", "minecraft:diamond"));
    }
}

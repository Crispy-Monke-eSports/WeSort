package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Orders stay placeable while no bot is online: with only stale observations the request queues as
 * pending against last-verified counts, and the bot can only claim it after a fresh observation.
 */
class OfflineOrderQueueTest {
    @TempDir
    Path data;

    IndexStore index;
    OrderStore orders;

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(
                index,
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                        .getAsJsonObject());
        orders = new OrderStore(data, index);
        orders.load();
    }

    /** Age every source past the observation TTL, like a stash whose bot went offline. */
    private void goStale() throws Exception {
        try (var c = index.stock().open();
                var sql = c.createStatement()) {
            assertTrue(sql.executeUpdate("UPDATE stock_sources SET verified_at = 1") > 0);
        }
        assertEquals("STALE_AGE", index.stock().sources().getFirst().status());
    }

    private List<OrderStore.Line> diamonds(long n) {
        return List.of(new OrderStore.Line("item", "minecraft:diamond", n));
    }

    @Test
    void staleCountsStayZeroButLastVerifiedCountsCoverTheStash() throws Exception {
        goStale();
        assertEquals(0, index.orderCounts("s", "a").total("item", "minecraft:diamond"));
        assertEquals(64, index.orderCountsIncludingStale("s", "a").total("item", "minecraft:diamond"));
    }

    @Test
    void orderListReportsLastVerifiedAvailabilitySoTheShopAgreesWithSubmit() throws Exception {
        goStale();
        var available = orders.listJson("s", "a").getAsJsonObject("available");
        assertEquals(
                64, available.getAsJsonObject("items").get("minecraft:diamond").getAsLong());
    }

    @Test
    void orderQueuesWhileStaleAndIsClaimedOnlyWhenFresh() throws Exception {
        goStale();
        OrderStore.Order queued = orders.place("s", "a", diamonds(10), null, true);
        assertEquals("pending", queued.status);
        assertTrue(queued.queuedOffline);

        // Nothing verified: the bot has nothing it may run yet.
        assertNull(orders.claimPayload("s", "a", "agent-1"));

        // The bot reconnects and re-observes: the queued order is claimable unchanged.
        StockFixtures.put(
                index,
                JsonParser.parseString(
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":[{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":64,\"maxStack\":64}]}")
                        .getAsJsonObject());
        var payload = orders.claimPayload("s", "a", "agent-1");
        assertEquals(queued.id, payload.get("id").getAsString());
        assertEquals("accepted", payload.get("status").getAsString());
    }

    @Test
    void staleOrderIsDeniedByDefaultAndCreatesNoOrder() throws Exception {
        goStale();
        // The default path never queues silently: an expired source keeps its shortage and no
        // order row is created, so expired stock cannot be reserved behind the requester's back.
        OrderStore.OrderException denied =
                assertThrows(OrderStore.OrderException.class, () -> orders.place("s", "a", diamonds(1), null));
        assertTrue(denied.status == 400 || denied.status == 409, "stale order denied, got " + denied.status);
        assertEquals(
                0,
                orders.listJson("s", "a").getAsJsonArray("orders").size());
    }

    @Test
    void realShortagesStillFailAndReportVerifiedStock() throws Exception {
        // Fresh and short: no fallback, plain 400.
        OrderStore.OrderException fresh =
                assertThrows(OrderStore.OrderException.class, () -> orders.place("s", "a", diamonds(100), null));
        assertEquals(400, fresh.status);

        // Stale and short of even the last-verified stock: the verified (zero) message stands.
        goStale();
        OrderStore.OrderException stale =
                assertThrows(OrderStore.OrderException.class, () -> orders.place("s", "a", diamonds(100), null));
        assertEquals(400, stale.status);
        assertTrue(stale.getMessage().contains("available"));
    }
}

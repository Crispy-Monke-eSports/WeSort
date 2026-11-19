package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step 4 (W-026): verified delivery walks an order along the canonical chain, recording every step. */
class OrderDeliveryWalkTest {
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
                                "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
                                        + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}")
                        .getAsJsonObject());
        orders = new OrderStore(data, index);
        orders.load();
    }

    private OrderStore.Order place() throws Exception {
        return orders.place(
                "s",
                "a",
                List.of(new OrderStore.Line("item", "minecraft:diamond", 10)),
                null,
                "u-1",
                "U One",
                null,
                OrderIntent.DEFAULT);
    }

    @Test
    void deliveryWalksEveryStageAndIsIdempotent() throws Exception {
        OrderStore.Order o = place();
        assertEquals(OrderStage.RESERVED, o.stage());
        OrderStore.Order done = orders.advanceToward(o.id, OrderStage.DELIVERED, "owner");
        assertEquals(OrderStage.DELIVERED, done.stage());
        assertEquals("delivered", done.status);
        var history = done.snapshot().getAsJsonArray("history");
        // RESERVED -> PACKING -> PICKUP_READY -> PICKED_UP -> IN_TRANSIT -> AWAITING_CUSTOMER -> DELIVERED.
        assertEquals(7, history.size());
        OrderStore.Order again = orders.advanceToward(o.id, OrderStage.DELIVERED, "owner");
        assertEquals(OrderStage.DELIVERED, again.stage());
        assertEquals(7, again.snapshot().getAsJsonArray("history").size());
    }

    @Test
    void fencingAndIllegalMovesAreRejected() throws Exception {
        OrderStore.Order o = place();
        OrderStore.Order fenced = orders.advanceToward(o.id, OrderStage.NEEDS_RECONCILIATION, "owner");
        assertEquals(OrderStage.NEEDS_RECONCILIATION, fenced.stage());
        assertEquals("needs_reconciliation", fenced.status);
        // An operator can requeue the fenced order and then deliver it.
        OrderStore.Order requeued = orders.reconcile(o.id, "requeue", "owner");
        assertEquals(OrderStage.PICKUP_READY, requeued.stage());
        assertEquals("packed", requeued.status);
        // Terminal DELIVERED orders cannot move again.
        OrderStore.Order done = orders.advanceToward(o.id, OrderStage.DELIVERED, "owner");
        assertEquals(OrderStage.DELIVERED, done.stage());
        OrderStore.OrderException illegal = assertThrows(
                OrderStore.OrderException.class, () -> orders.advanceToward(o.id, OrderStage.PACKING, "owner"));
        assertEquals(409, illegal.status);
        OrderStore.OrderException missing = assertThrows(
                OrderStore.OrderException.class,
                () -> orders.advanceToward("o-deadbeefcafe", OrderStage.DELIVERED, "owner"));
        assertEquals(404, missing.status);
        assertTrue(done.id.startsWith("o-"));
    }

    @Test
    void reconcileFailClosesAndBadOutcomeIsRejected() throws Exception {
        OrderStore.Order o = place();
        orders.advanceToward(o.id, OrderStage.NEEDS_RECONCILIATION, "owner");
        OrderStore.Order failed = orders.reconcile(o.id, "fail", "owner");
        assertEquals(OrderStage.FAILED, failed.stage());
        assertEquals("failed", failed.status);
        OrderStore.OrderException bad =
                assertThrows(OrderStore.OrderException.class, () -> orders.reconcile(o.id, "requeue", "owner"));
        assertEquals(409, bad.status);
        assertThrows(OrderStore.OrderException.class, () -> orders.reconcile(o.id, "bogus", "owner"));
    }
}

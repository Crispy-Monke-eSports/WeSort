package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** On-demand kits: one kit is one box, reserving its exact components from loose stock. */
class KitOrdersTest {
    @TempDir
    Path data;

    IndexStore index;
    OrderStore orders;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(
                index,
                json("{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
                        + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":100,\"maxStack\":64},"
                        + "{\"key\":\"minecraft:dirt\",\"id\":\"minecraft:dirt\",\"count\":200,\"maxStack\":64}]}"));
        orders = new OrderStore(data, index);
        orders.load();
        orders.kits()
                .create(json("{\"name\":\"starter\",\"slots\":["
                        + "{\"slot\":0,\"key\":\"minecraft:diamond\",\"count\":16},"
                        + "{\"slot\":13,\"key\":\"minecraft:dirt\",\"count\":32}]}"));
    }

    private OrderStore.Order kitOrder(long n) throws Exception {
        return orders.submit(
                        "s",
                        "a",
                        List.of(new OrderStore.Line("kit", "starter", n)),
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
    void kitReservesComponentsAndOneBoxPerKit() throws Exception {
        var o = kitOrder(2);
        // 2 kits: 32 diamonds + 64 dirt reserved, packed as exactly 2 boxes.
        assertEquals(2, o.toJson().get("packageTotal").getAsInt());
        var reserved = orders.reservedFor("s", "a");
        assertEquals(32, reserved.get("item:minecraft:diamond"));
        assertEquals(64, reserved.get("item:minecraft:dirt"));
        assertTrue(!reserved.containsKey("kit:starter"));
        // The exact layout travels with the order, pinned at submit.
        var kits = o.toJson().getAsJsonObject("kits");
        assertNotNull(kits);
        assertEquals(1, kits.getAsJsonObject("starter").get("revision").getAsInt());
        assertEquals(2, kits.getAsJsonObject("starter").getAsJsonArray("slots").size());
    }

    @Test
    void kitAvailabilityIsScarcestComponent() throws Exception {
        // 100 diamonds / 16 = 6, 200 dirt / 32 = 6.
        assertEquals(
                6,
                orders.listJson("s", "a")
                        .getAsJsonObject("available")
                        .getAsJsonObject("kits")
                        .get("starter")
                        .getAsInt());
        kitOrder(5);
        // 20 diamonds left (1 kit), 40 dirt left (1 kit).
        assertEquals(
                1,
                orders.listJson("s", "a")
                        .getAsJsonObject("available")
                        .getAsJsonObject("kits")
                        .get("starter")
                        .getAsInt());
    }

    @Test
    void unknownKitIs404() {
        var e = assertThrows(
                OrderStore.OrderException.class,
                () -> orders.submit(
                        "s",
                        "a",
                        List.of(new OrderStore.Line("kit", "missing", 1)),
                        null,
                        null,
                        null,
                        null,
                        null,
                        OrderIntent.DEFAULT,
                        null,
                        false,
                        ignored -> null));
        assertEquals(404, e.status);
        assertEquals(0, e.line);
    }

    @Test
    void shortageNamesTheComponent() {
        // 7 kits need 112 diamonds, only 100 verified.
        var e = assertThrows(OrderStore.OrderException.class, () -> kitOrder(7));
        assertEquals(400, e.status);
        assertTrue(e.getMessage().contains("minecraft:diamond"), e.getMessage());
    }

    @Test
    void templateEditDoesNotMovePlacedOrders() throws Exception {
        var o = kitOrder(1);
        var changed = json("{\"name\":\"starter\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:diamond\",\"count\":1}]}");
        orders.kits().update(changed, 1);
        // The placed order still pins revision 1 with the original two slots.
        var pinned = o.toJson().getAsJsonObject("kits").getAsJsonObject("starter");
        assertEquals(1, pinned.get("revision").getAsInt());
        assertEquals(2, pinned.getAsJsonArray("slots").size());
        // …while new orders see revision 2: 84 diamonds left, 1 per kit.
        assertEquals(
                84,
                orders.listJson("s", "a")
                        .getAsJsonObject("available")
                        .getAsJsonObject("kits")
                        .get("starter")
                        .getAsInt());
    }

    @Test
    void retryKeepsKitLines() throws Exception {
        var o = kitOrder(1);
        var cancelled = orders.cancel(o.id, Users.OWNER_ID);
        assertEquals("cancelled", cancelled.status);
        var retry = orders.retry(o.id, Users.OWNER_ID, "Owner");
        assertEquals(1, retry.lines.size());
        assertEquals("kit", retry.lines.get(0).type());
        assertEquals("starter", retry.lines.get(0).key());
        assertNotNull(retry.kits);
    }
}

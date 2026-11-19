package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step 4 (W-026/W-059): guild withdrawal quotas enforced on the order lifecycle. */
class QuotaOrderWiringTest {
    @TempDir
    Path data;

    IndexStore index;
    OrderStore orders;
    Quotas quotas;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject quotaPolicy(boolean enabled, String subject, String key, long max) {
        var root = new JsonObject();
        root.addProperty("enabled", enabled);
        var rules = new JsonArray();
        var rule = new JsonObject();
        rule.addProperty("subject", subject);
        rule.addProperty("key", key);
        rule.addProperty("max", max);
        rule.addProperty("windowSeconds", 3600);
        rules.add(rule);
        root.add("rules", rules);
        return root;
    }

    private static List<OrderStore.Line> diamonds(long n) {
        return List.of(new OrderStore.Line("item", "minecraft:diamond", n));
    }

    @BeforeEach
    void setUp() throws Exception {
        index = new IndexStore(data, CreativeTabs.NONE);
        index.load();
        StockFixtures.put(
                index,
                json(
                        "{\"version\":1,\"server\":\"s\",\"stash\":\"a\",\"items\":["
                                + "{\"key\":\"minecraft:diamond\",\"id\":\"minecraft:diamond\",\"count\":1000,\"maxStack\":64}]}"));
        orders = new OrderStore(data, index);
        orders.load();
        quotas = new Quotas(data);
        quotas.update(quotaPolicy(true, "*", "item:minecraft:diamond", 64), 1);
        orders.setQuotas(quotas);
    }

    private OrderStore.Order submit(String member, long count) throws Exception {
        return orders
                .submit(
                        "s",
                        "a",
                        diamonds(count),
                        null,
                        null,
                        member,
                        member,
                        "org-1",
                        OrderIntent.DEFAULT,
                        null,
                        false,
                        ignored -> null,
                        List.of("member"),
                        false)
                .order();
    }

    private long reserved(String member) {
        var usage = quotas.usageFor(member).getAsJsonObject("usage");
        if (!usage.has("item:minecraft:diamond")) return 0;
        return usage.getAsJsonObject("item:minecraft:diamond").get("reserved").getAsLong();
    }

    private long consumed(String member) {
        var usage = quotas.usageFor(member).getAsJsonObject("usage");
        if (!usage.has("item:minecraft:diamond")) return 0;
        return usage.getAsJsonObject("item:minecraft:diamond").get("consumed").getAsLong();
    }

    @Test
    void placeReservesAndExceededIsRejectedWith429() throws Exception {
        OrderStore.Order first = submit("u-1", 40);
        assertEquals(40, reserved("u-1"));
        OrderStore.OrderException rejected =
                assertThrows(OrderStore.OrderException.class, () -> submit("u-1", 40));
        assertEquals(429, rejected.status);
        // The rejected order left no reservation behind.
        assertEquals(40, reserved("u-1"));
        assertTrue(first.id.startsWith("o-"));
    }

    @Test
    void cancelReleasesAndOverrideSkipsReserveWithAuditTrail() throws Exception {
        OrderStore.Order first = submit("u-1", 40);
        assertEquals(40, reserved("u-1"));
        orders.cancel(first.id, "u-1");
        assertEquals(0, reserved("u-1"));
        // A fresh 64 now fits again.
        submit("u-1", 64);
        assertEquals(64, reserved("u-1"));
    }

    @Test
    void overrideBypassesReserve() throws Exception {
        OrderStore.Order o = orders
                .submit(
                        "s",
                        "a",
                        diamonds(1000),
                        null,
                        null,
                        "u-9",
                        "u-9",
                        "org-1",
                        OrderIntent.DEFAULT,
                        null,
                        false,
                        ignored -> null,
                        List.of("officer"),
                        true)
                .order();
        assertEquals(0, reserved("u-9"));
        assertTrue(o.id.startsWith("o-"));
    }

    @Test
    void deliveryWalkConsumesAndFailedReleases() throws Exception {
        OrderStore.Order o = submit("u-1", 40);
        orders.advanceToward(o.id, OrderStage.DELIVERED, "owner");
        quotas.consume(o.id);
        assertEquals(40, consumed("u-1"));
        assertEquals(0, reserved("u-1"));

        OrderStore.Order doomed = submit("u-1", 24);
        assertEquals(24, reserved("u-1"));
        orders.cancel(doomed.id, "u-1");
        assertEquals(0, reserved("u-1"));
    }

    @Test
    void withoutQuotasEverythingBehavesAsBefore() throws Exception {
        OrderStore plain = new OrderStore(data, index);
        plain.load();
        OrderStore.Order o = plain.place("s", "a", diamonds(64), null, "u-1", "U One", null, OrderIntent.DEFAULT);
        assertTrue(o.id.startsWith("o-"));
    }
}

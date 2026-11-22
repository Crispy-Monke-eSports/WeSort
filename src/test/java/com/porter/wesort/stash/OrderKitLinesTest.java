package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** On-demand kits: the claim payload carries kit lines plus pinned template snapshots. */
class OrderKitLinesTest {
    private static Order order(String json) {
        Order o = Order.parse(JsonParser.parseString(json).getAsJsonObject());
        if (o == null) throw new AssertionError("order did not parse");
        return o;
    }

    private static String base(String lines, String kits) {
        return "{\"id\":\"o-1\",\"lines\":[" + lines + "]" + (kits.isEmpty() ? "" : ",\"kits\":" + kits) + "}";
    }

    private static String template() {
        return "{\"starter\":{\"name\":\"starter\",\"revision\":2,\"updatedAt\":1,\"slots\":["
                + "{\"slot\":0,\"key\":\"minecraft:diamond\",\"count\":16},"
                + "{\"slot\":13,\"key\":\"minecraft:dirt\",\"count\":32}]}}";
    }

    @Test
    void kitLinesParseWithPinnedTemplate() {
        Order o = order(base(
                "{\"type\":\"kit\",\"key\":\"starter\",\"count\":2}"
                        + ",{\"type\":\"item\",\"key\":\"minecraft:torch\",\"count\":4}",
                template()));
        assertEquals(2, o.lines.size());
        assertEquals(Order.KIT, o.lines.get(0).type());
        assertEquals("starter", o.lines.get(0).key());
        assertEquals(2, o.lines.get(0).count());
        var slots = o.kits.get("starter");
        assertNotNull(slots);
        assertEquals(2, slots.size());
        assertEquals(new Order.TemplateSlot(0, "minecraft:diamond", 16), slots.get(0));
        assertEquals(new Order.TemplateSlot(13, "minecraft:dirt", 32), slots.get(1));
    }

    @Test
    void kitLineWithoutTemplateSurvives() {
        // The server always pins the snapshot, but a missing one must not drop the line.
        Order o = order(base("{\"type\":\"kit\",\"key\":\"starter\",\"count\":1}", ""));
        assertEquals(1, o.lines.size());
        assertEquals(Order.KIT, o.lines.get(0).type());
        assertNull(o.kits.get("starter"));
    }

    @Test
    void badTemplateSlotsAreDropped() {
        // A broken template snapshot must not sink the order: the kit line
        // stays and reports its missing template at plan time.
        var bad = order(base(
                "{\"type\":\"kit\",\"key\":\"starter\",\"count\":1}",
                "{\"starter\":{\"name\":\"starter\",\"revision\":1,\"updatedAt\":1,\"slots\":[]}}"));
        assertEquals(1, bad.lines.size());
        assertNull(bad.kits.get("starter"));
        var badSlot = order(
                base(
                        "{\"type\":\"kit\",\"key\":\"starter\",\"count\":1}",
                        "{\"starter\":{\"name\":\"starter\",\"revision\":1,\"updatedAt\":1,\"slots\":[{\"slot\":27,\"key\":\"minecraft:dirt\",\"count\":1}]}}"));
        assertEquals(1, badSlot.lines.size());
        assertNull(badSlot.kits.get("starter"));
    }
}

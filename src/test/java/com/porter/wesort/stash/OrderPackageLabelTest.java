package com.porter.wesort.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** W-064: the bot names boxes from the server's frozen packageLabel + packageTotal. */
class OrderPackageLabelTest {
    private static Order order(String json) {
        Order o = Order.parse(JsonParser.parseString(json).getAsJsonObject());
        if (o == null) throw new AssertionError("order did not parse");
        return o;
    }

    private static String base(String extra) {
        return "{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":64}]"
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @Test
    void allModeGeneratesNumberedNames() {
        Order o = order(base("\"packageLabel\":{\"mode\":\"all\",\"text\":\"Byggmaterial\"},\"packageTotal\":4"));
        assertEquals("WeSort (1/4) - Byggmaterial", o.names.nameFor(0));
        assertEquals("WeSort (4/4) - Byggmaterial", o.names.nameFor(3));
        assertNull(o.names.nameFor(4));
    }

    @Test
    void eachModePadsWithDefault() {
        Order o = order(base("\"packageLabel\":{\"mode\":\"each\",\"texts\":[\"A\"]},\"packageTotal\":2"));
        assertEquals("WeSort (1/2) - A", o.names.nameFor(0));
        assertEquals("WeSort (2/2) - Beställning", o.names.nameFor(1));
    }

    @Test
    void legacyNamesWinWithoutPackageTotal() {
        Order o = order(base("\"names\":{\"mode\":\"all\",\"name\":\"Free\"}"));
        assertEquals("Free", o.names.nameFor(0));
    }

    @Test
    void packageLabelBeatsLegacyNames() {
        Order o = order(base("\"names\":{\"mode\":\"all\",\"name\":\"Free\"},"
                + "\"packageLabel\":{\"mode\":\"all\",\"text\":\"W\"},\"packageTotal\":1"));
        assertEquals("WeSort (1/1) - W", o.names.nameFor(0));
    }

    @Test
    void invalidTotalFallsBackToLegacy() {
        Order o = order(base("\"packageLabel\":{\"mode\":\"all\",\"text\":\"W\"},\"packageTotal\":99"));
        assertNull(o.names);
        JsonObject legacy = new JsonObject();
        legacy.addProperty("mode", "all");
        legacy.addProperty("name", "Free");
        Order o2 = order(base("\"names\":{\"mode\":\"all\",\"name\":\"Free\"},"
                + "\"packageLabel\":{\"mode\":\"all\",\"text\":\"W\"},\"packageTotal\":99"));
        assertEquals("Free", o2.names.nameFor(0));
    }

    @Test
    void noNamesAtAll() {
        assertNull(order(base("")).names);
    }
}

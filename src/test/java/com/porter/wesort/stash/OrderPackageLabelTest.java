package com.porter.wesort.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Agent-side fail-closed contract for Manager-owned shulker labels (W-064). */
class OrderPackageLabelTest {
    private static JsonObject order(String labels) {
        return JsonParser.parseString("""
            {"id":"o-001122334455",
             "lines":[{"type":"item","key":"minecraft:diamond","count":1}],
             "names":%s}
            """.formatted(labels)).getAsJsonObject();
    }

    @Test void requiredLabelsAreNotLegacyOptionalNames() {
        var value = order("""
            {"mode":"each","names":["WeSort (1/2) - A","WeSort (2/2) - B"],
             "packageLabel":{"version":1,"text":"A","texts":["A","B"],"total":2}}
            """);
        var parsed = Order.parse(value);
        assertNotNull(parsed);
        assertTrue(parsed.requiredPackageLabels);
        assertEquals("WeSort (1/2) - A", parsed.names.nameFor(0));
        assertEquals("WeSort (2/2) - B", parsed.names.nameFor(1));
        var historical = Order.parse(order("{\"mode\":\"all\",\"name\":\"Legacy\"}"));
        assertFalse(historical.requiredPackageLabels);
        assertEquals("Legacy", historical.names.nameFor(0));
    }

    @Test void missingOrSpoofedNumberCannotSilentlyBecomeDeliverable() {
        for (String invalid : new String[]{
            """
              {"mode":"each","names":["WeSort (2/2) - A","WeSort (1/2) - B"],
               "packageLabel":{"version":1,"total":2}}
            """,
            """
              {"mode":"each","names":["WeSort (1/2) - A"],
               "packageLabel":{"version":1,"total":2}}
            """,
            """
              {"mode":"each","names":["WeSort (1/2) - A",""],
               "packageLabel":{"version":1,"total":2}}
            """,
            """
              {"mode":"all","name":"A","packageLabel":{"version":1,"total":1}}
            """,
            """
              {"mode":"each","names":["WeSort (1/55) - A"],
               "packageLabel":{"version":1,"total":55}}
            """,
            """
              {"mode":"each","names":["WeSort (1/1) - A"],
               "packageLabel":{"version":2,"total":1}}
            """
        }) assertThrows(IllegalArgumentException.class, () -> Order.parse(order(invalid)), invalid);
    }

    @Test void metadataCannotBeUsedToBypassRequiredNameOnAgent() {
        var empty = order("""
            {"mode":"each","names":[],
             "packageLabel":{"version":1,"total":1}}
            """);
        assertThrows(IllegalArgumentException.class, () -> Order.parse(empty));
    }
}

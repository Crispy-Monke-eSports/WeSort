package com.porter.wesort.data;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransportCommandsTest {
    private static JsonObject op(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test void typedOperationsAreValidatedAndNormalised() {
        assertEquals("Steve", TransportCommands.parse(op("{\"kind\":\"TPA_REQUEST\",\"target\":\"  Steve  \"}")).get("target").getAsString());
        assertEquals("base", TransportCommands.parse(op("{\"kind\":\"HOME_GO\",\"home\":\"base\"}")).get("home").getAsString());
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertEquals(uuid, TransportCommands.parse(op("{\"kind\":\"TPA_ACCEPT\",\"requestId\":\"" + uuid + "\"}")).get("requestId").getAsString());
    }

    @Test void rejectsArbitraryCommandsAndUnsanitizedFields() {
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"/op me\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"TPA_REQUEST\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"TPA_REQUEST\",\"target\":\"a b\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"TPA_REQUEST\",\"target\":\"a§b\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"HOME_GO\",\"home\":\"a b\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"TPA_ACCEPT\",\"requestId\":\"not-a-uuid\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"HOME_GO\",\"home\":\"\"}")));
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.parse(op("{\"kind\":\"TPA_REQUEST\",\"target\":\"a\\u202eb\"}")));
    }

    @Test void lengthLimitsAreEnforced() {
        assertEquals(16, TransportCommands.player("a".repeat(16), "target").length());
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.player("a".repeat(17), "target"));
        assertEquals(32, TransportCommands.home("h".repeat(32), "home").length());
        assertThrows(IllegalArgumentException.class, () -> TransportCommands.home("h".repeat(33), "home"));
    }
}

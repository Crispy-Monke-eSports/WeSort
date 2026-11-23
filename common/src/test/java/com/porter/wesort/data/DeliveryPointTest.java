package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class DeliveryPointTest {
    private static JsonObject body(Object... kv) {
        var o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object v = kv[i + 1];
            if (v == null) o.add(key, JsonNull.INSTANCE);
            else if (v instanceof Integer n) o.addProperty(key, n);
            else if (v instanceof Boolean b) o.addProperty(key, b);
            else o.addProperty(key, (String) v);
        }
        return o;
    }

    @Test
    void createMakesAFreshPendingPoint() {
        var o = body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:overworld",
                "x",
                10,
                "y",
                64,
                "z",
                -20,
                "type",
                "CHEST",
                "access",
                "MEMBER",
                "member",
                "u-111111111111");
        var p = DeliveryPoint.create(o);
        assertEquals(DeliveryPoint.Status.PENDING, p.status());
        assertEquals(1, p.revision());
        assertNotNull(p.id());
        assertEquals("minecraft:overworld", p.dimension());
        assertEquals("u-111111111111", p.member());
    }

    @Test
    void validationRejectsMalformedPoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPoint.create(body(
                        "dimension",
                        "minecraft:overworld",
                        "x",
                        0,
                        "y",
                        64,
                        "z",
                        0,
                        "type",
                        "CHEST",
                        "access",
                        "PROJECT")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPoint.create(body(
                        "server",
                        "2b2t.org",
                        "dimension",
                        "bad",
                        "x",
                        0,
                        "y",
                        64,
                        "z",
                        0,
                        "type",
                        "CHEST",
                        "access",
                        "PROJECT")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPoint.create(body(
                        "server",
                        "2b2t.org",
                        "dimension",
                        "minecraft:overworld",
                        "x",
                        30000001,
                        "y",
                        64,
                        "z",
                        0,
                        "type",
                        "CHEST",
                        "access",
                        "PROJECT")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPoint.create(body(
                        "server",
                        "2b2t.org",
                        "dimension",
                        "minecraft:overworld",
                        "x",
                        0,
                        "y",
                        64,
                        "z",
                        0,
                        "type",
                        "CHEST",
                        "access",
                        "MEMBER")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPoint.create(body(
                        "server",
                        "2b2t.org",
                        "dimension",
                        "minecraft:overworld",
                        "x",
                        0,
                        "y",
                        64,
                        "z",
                        0,
                        "type",
                        "CHEST",
                        "access",
                        "INTERNAL",
                        "member",
                        "u-111111111111")));
    }

    @Test
    void updateMergesAndIncrementsRevision() {
        var p = DeliveryPoint.create(body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:overworld",
                "x",
                10,
                "y",
                64,
                "z",
                -20,
                "type",
                "CHEST",
                "access",
                "PROJECT"));
        var changed =
                p.updated(JsonParser.parseString("{\"status\":\"VERIFIED\"}").getAsJsonObject());
        assertEquals(DeliveryPoint.Status.VERIFIED, changed.status());
        assertEquals(p.id(), changed.id());
        assertEquals(p.server(), changed.server());
        assertEquals(2, changed.revision());
    }

    @Test
    void physicalOrRecipientChangesInvalidatePreviouslyVerifiedDestination() {
        var p = DeliveryPoint.create(body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:overworld",
                "x",
                10,
                "y",
                64,
                "z",
                20,
                "type",
                "CHEST",
                "access",
                "MEMBER",
                "member",
                "u-111111111111",
                "status",
                "VERIFIED"));
        for (var change : java.util.List.of(
                body("x", 11),
                body("y", 65),
                body("z", 21),
                body("server", "other.example"),
                body("dimension", "minecraft:the_nether"),
                body("type", "PLAYER_MEETING"),
                body("member", "u-222222222222"))) {
            change.addProperty("status", "VERIFIED");
            assertEquals(DeliveryPoint.Status.PENDING, p.updated(change).status(), change.toString());
        }
        assertEquals(DeliveryPoint.Status.VERIFIED, p.updated(body("x", 10)).status());
        assertEquals(
                DeliveryPoint.Status.PAUSED,
                p.updated(body("x", 11, "status", "PAUSED")).status());
    }

    @Test
    void coordinateParsingCannotTruncateOrWrapAndNamesCannotGrantAccess() {
        var p = DeliveryPoint.create(body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:overworld",
                "x",
                10,
                "y",
                64,
                "z",
                20,
                "type",
                "CHEST",
                "access",
                "PROJECT"));
        for (String invalid : java.util.List.of("1.5", "4294967296", "\"1\"", "null")) {
            var update = JsonParser.parseString("{\"x\":" + invalid + "}").getAsJsonObject();
            assertThrows(IllegalArgumentException.class, () -> p.updated(update), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> p.updated(body("access", "MEMBER", "member", "alice")));
    }

    @Test
    void redactionDropsCoordinatesAndDimensionButKeepsSelectionFields() {
        var p = DeliveryPoint.create(body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:the_nether",
                "x",
                1234,
                "y",
                64,
                "z",
                -5678,
                "type",
                "CHEST",
                "access",
                "MEMBER",
                "member",
                "u-111111111111"));
        var r = p.jsonRedacted();
        assertFalse(r.has("x") || r.has("y") || r.has("z") || r.has("dimension"), r.toString());
        assertTrue(r.has("id") && r.has("server") && r.has("type") && r.has("access") && r.has("member"));
        assertTrue(p.json().has("x") && p.json().has("dimension"));
    }

    @Test
    void readRoundTripsAStoredPoint() {
        var p = DeliveryPoint.create(body(
                "server",
                "2b2t.org",
                "dimension",
                "minecraft:overworld",
                "x",
                10,
                "y",
                64,
                "z",
                -20,
                "type",
                "PLAYER_MEETING",
                "access",
                "PROJECT"));
        var back = DeliveryPoint.read(p.json());
        assertEquals(p, back);
    }
}

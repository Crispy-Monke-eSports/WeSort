package com.porter.wesortmanager;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.wesort.data.DeliveryPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPointsTest {
    @TempDir Path data;

    private final Users.Principal owner = new Users.Principal("owner", "Owner", true, Users.Perms.ALL, false);
    private final Users.Principal member = new Users.Principal("u-1", "alice", false, Users.PRESETS.get("member"), false);
    private final Users.Principal other = new Users.Principal("u-2", "bob", false, Users.PRESETS.get("member"), false);

    private static JsonObject body(Object... kv) {
        var o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object v = kv[i + 1];
            if (v == null) o.add(key, JsonNull.INSTANCE);
            else if (v instanceof Integer n) o.addProperty(key, n);
            else o.addProperty(key, (String) v);
        }
        return o;
    }

    private static JsonObject verified(String server, String access, String member) {
        return body("server", server, "dimension", "minecraft:overworld", "x", 10, "y", 64, "z", 20,
            "type", "CHEST", "access", access, "member", member, "status", "VERIFIED");
    }

    @Test void createAndListWithHistory() throws Exception {
        var points = new DeliveryPoints(data);
        var p = points.create(verified("2b2t.org", "PROJECT", null), "owner");
        assertEquals(DeliveryPoint.Status.VERIFIED, p.status());
        assertEquals(1, points.list().size());
        var listed = points.list().get(0).getAsJsonObject();
        assertEquals(p.id(), listed.get("id").getAsString());
        assertEquals(1, listed.getAsJsonArray("history").size());
        assertEquals("created", listed.getAsJsonArray("history").get(0).getAsJsonObject().get("event").getAsString());
    }

    @Test void updateIsOptimisticAndPauseBlocksSelection() throws Exception {
        var points = new DeliveryPoints(data);
        var p = points.create(verified("2b2t.org", "PROJECT", null), "owner");
        assertThrows(DeliveryPoints.Conflict.class, () -> points.update(p.id(), JsonParser.parseString("{\"status\":\"VERIFIED\"}").getAsJsonObject(), 9, "owner"));
        var changed = points.update(p.id(), JsonParser.parseString("{\"member\":null}").getAsJsonObject(), p.revision(), "owner");
        assertEquals(2, changed.revision());

        assertEquals(1, points.selectable(owner).size());
        points.pause(p.id(), changed.revision(), "owner");
        assertEquals(0, points.selectable(owner).size());
        assertThrows(DeliveryPoints.Conflict.class, () -> points.pause(p.id(), 1, "owner"));
    }

    @Test void selectableHidesInternalAndOtherMembersPoints() throws Exception {
        var points = new DeliveryPoints(data);
        points.create(verified("2b2t.org", "MEMBER", "u-1"), "owner"); // alice's own
        points.create(verified("2b2t.org", "MEMBER", "u-2"), "owner"); // bob's
        points.create(verified("2b2t.org", "PROJECT", null), "owner");
        points.create(verified("2b2t.org", "INTERNAL", null), "owner"); // secret stash
        points.create(body("server", "2b2t.org", "dimension", "minecraft:overworld", "x", 0, "y", 64, "z", 0,
            "type", "CHEST", "access", "PROJECT", "status", "PENDING"), "owner"); // not yet verified

        assertEquals(5, points.list().size(), "owner sees everything");
        assertEquals(4, points.selectable(owner).size(), "owner selects every verified point");

        var forAlice = points.selectable(member);
        assertEquals(2, forAlice.size(), "own MEMBER + the PROJECT point");
        for (var e : forAlice) {
            var o = e.getAsJsonObject();
            assertNotEquals("INTERNAL", o.get("access").getAsString());
            assertFalse(o.has("x") || o.has("z") || o.has("dimension"), o.toString());
        }
        assertFalse(forAlice.toString().contains("bob") || forAlice.toString().contains("u-2"), forAlice.toString());
    }

    @Test void pointsPersistAcrossRestart() throws Exception {
        var points = new DeliveryPoints(data);
        var p = points.create(verified("2b2t.org", "PROJECT", null), "owner");
        points.pause(p.id(), p.revision(), "owner");

        var reloaded = new DeliveryPoints(data);
        assertEquals(1, reloaded.list().size());
        assertEquals(DeliveryPoint.Status.PAUSED, reloaded.get(p.id()).status());
        assertEquals(2, reloaded.list().get(0).getAsJsonObject().getAsJsonArray("history").size());
        assertEquals(0, reloaded.selectable(owner).size());
    }
}

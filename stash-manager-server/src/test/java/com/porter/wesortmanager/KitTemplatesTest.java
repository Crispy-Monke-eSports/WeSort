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

/** Kit templates: validation, revision-guarded writes, and persistence. */
class KitTemplatesTest {
    @TempDir
    Path data;

    KitTemplates kits;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject template(String name) {
        return json("{\"name\":\"" + name
                + "\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:diamond\",\"count\":16},{\"slot\":13,\"key\":\"minecraft:dirt\",\"count\":32}]}");
    }

    @BeforeEach
    void setUp() throws Exception {
        kits = new KitTemplates(data);
    }

    @Test
    void createListsAndPersists() throws Exception {
        var t = kits.create(template("starter"));
        assertEquals(1, t.revision());
        assertEquals(2, t.slots().size());
        assertEquals(16, t.components().get("minecraft:diamond"));
        assertEquals(32, t.components().get("minecraft:dirt"));

        var reloaded = new KitTemplates(data);
        var same = reloaded.get("starter");
        assertNotNull(same);
        assertEquals(1, same.revision());
        assertEquals(t.json(), same.json());
    }

    @Test
    void updateNeedsCurrentRevision() throws Exception {
        kits.create(template("starter"));
        var changed = json(template("starter").toString());
        changed.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 8);
        var t = kits.update(changed, 1);
        assertEquals(2, t.revision());
        assertThrows(KitTemplates.Conflict.class, () -> kits.update(changed, 1));
        assertThrows(KitTemplates.Conflict.class, () -> kits.delete("starter", 1));
        kits.delete("starter", 2);
        assertEquals(0, kits.all().size());
    }

    @Test
    void rejectsBadTemplates() {
        assertThrows(IllegalArgumentException.class, () -> kits.create(json("{\"name\":\"x\",\"slots\":[]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json(
                        "{\"name\":\"bad name!\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json(
                        "{\"name\":\"x\",\"slots\":[{\"slot\":27,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json(
                        "{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1},{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":0}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":65}]}")));
        assertThrows(IllegalArgumentException.class, () -> kits.create(json("{\"name\":\"x\"}")));
        assertThrows(IllegalArgumentException.class, () -> kits.update(template("missing"), 1));
    }
}

package com.porter.wesortmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kit templates: validation, revision-guarded writes, and SQLite persistence. */
class KitTemplatesTest {
    @TempDir
    Path data;

    KitTemplates kits;

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject template(String name) {
        return json(
                "{\"name\":\"" + name
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
    void rejectsBadTemplates() {        assertThrows(IllegalArgumentException.class, () -> kits.create(json("{\"name\":\"x\",\"slots\":[]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json(
                        "{\"name\":\"bad name!\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(
                        json("{\"name\":\"x\",\"slots\":[{\"slot\":27,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(
                        json(
                                "{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1},{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"\",\"count\":1}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(
                        json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":0}]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> kits.create(
                        json("{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:dirt\",\"count\":100}]}")));
        assertThrows(IllegalArgumentException.class, () -> kits.create(json("{\"name\":\"x\"}")));
        assertThrows(IllegalArgumentException.class, () -> kits.update(template("missing"), 1));
    }

    @Test
    void slotKeysAreValidatedThroughTheIdentityMachinery() throws Exception {
        // Bare registry ids, client display variant keys and catalog group keys pass.
        kits.create(json(
                "{\"name\":\"ok\",\"slots\":[{\"slot\":0,\"key\":\"minecraft:diamond\",\"count\":1},{\"slot\":1,\"key\":\"minecraft:diamond_sword#1a2b3c4d\",\"count\":1},{\"slot\":2,\"key\":\"s:0123456789\",\"count\":1}]}"));
        // No colon at all, empty variant base, bad hex, uppercase hex, bad group: all fail closed.
        for (String key : new String[] {
            "diamond",
            "#1a2b3c4d",
            "minecraft:diamond#xyz",
            "minecraft:diamond#ABCDEF12",
            "minecraft:diamond#1a2b3c",
            "minecraft:diamond#1a2b3c4d5e",
            "s:xyz",
            "not an id!!"
        })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> kits.create(json(
                            "{\"name\":\"x\",\"slots\":[{\"slot\":0,\"key\":\"" + key + "\",\"count\":1}]}")),
                    "key must fail closed: " + key);
    }

    @Test
    void revisionsCarryContentFingerprints() throws Exception {
        var first = kits.create(template("starter"));
        assertEquals(64, first.fingerprint().length());
        var changed = json(template("starter").toString());
        changed.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("count", 8);
        var second = kits.update(changed, 1);
        assertEquals(2, second.revision());
        assertEquals(false, second.fingerprint().equals(first.fingerprint()));
        assertEquals(second.fingerprint(), kits.get("starter").fingerprint());
    }

    @Test
    void versionsSurviveDeleteOfTheActiveTemplate() throws Exception {
        kits.create(template("starter"));
        kits.delete("starter", 1);
        assertEquals(0, kits.all().size());
        try (var c = new StockLedger(data).open();
                var p = c.prepareStatement(
                        "SELECT revision,fingerprint,created_by FROM kit_template_versions WHERE server=? AND name_key=? ORDER BY revision")) {
            p.setString(1, KitTemplates.LOCAL_SERVER);
            p.setString(2, "starter");
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                assertEquals(1, r.getLong(1));
                assertEquals(64, r.getString(2).length());
                assertTrue(r.next() == false);
            }
        }
    }

    @Test
    void legacyJsonFileIsWarnedAboutAndIgnored() throws Exception {
        java.nio.file.Files.writeString(
                data.resolve("kit-templates.json"), "{\"schema\":1,\"templates\":[]}");
        var reloaded = new KitTemplates(data);
        assertEquals(0, reloaded.all().size());
        var t = reloaded.create(template("starter"), "owner");
        assertEquals("owner", createdBy(data, "starter", 1));
        assertEquals(1, t.revision());
    }

    private static String createdBy(Path dir, String name, long revision) throws Exception {
        try (var c = new StockLedger(dir).open();
                var p = c.prepareStatement(
                        "SELECT created_by FROM kit_template_versions WHERE server=? AND name_key=? AND revision=?")) {
            p.setString(1, KitTemplates.LOCAL_SERVER);
            p.setString(2, name);
            p.setLong(3, revision);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }
}

package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class KitTemplateTest {
    private KitTemplate template(String name, int count) {
        var item = new PhysicalSlots.Item(
                "minecraft:diamond",
                "minecraft:diamond",
                64,
                "{}",
                PhysicalSlots.Kind.ITEM,
                0,
                List.of(),
                "");
        return new KitTemplate(name, new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(26, count, item))));
    }

    @Test
    void ownFormatPreservesSparseSlotsCountsAndExactFingerprint() {
        var template = template("Raid alpha", 32);
        var payload = template.json();
        assertTrue(payload.has("snbt"));
        assertFalse(payload.has("slots"));
        assertFalse(payload.has("slotVariants"));
        var read = KitTemplate.read(payload);
        assertEquals(template.fingerprint(), read.fingerprint());
        assertEquals(26, read.layout().slots().getFirst().slot());
        assertEquals(32, read.layout().slots().getFirst().count());
        assertNotEquals(template.fingerprint(), template("Raid alpha", 31).fingerprint());
    }

    @Test
    void rejectsWrongMinecraftOldFormatsInvalidCountAndTamperedFingerprint() {
        var json = template("Raid", 32).json();
        json.addProperty("minecraft", "1.21.11");
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.read(json));
        json.addProperty("minecraft", "26.3");
        json.addProperty("format", "qwerte-kit:1");
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.read(json));
        json.addProperty("format", KitTemplate.FORMAT);
        json.addProperty("snbt", "{}");
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.read(json));
        var tampered = template("Raid", 32).json();
        tampered.addProperty("fingerprint", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> KitTemplate.read(tampered));
    }

    @Test
    void physicalKitCannotNestShulkers() {
        var shulker = new PhysicalSlots.Item(
                "minecraft:shulker_box", "box", 1, "{}", PhysicalSlots.Kind.SHULKER, 27, List.of(), "");
        assertThrows(
                IllegalArgumentException.class,
                () -> new KitTemplate(
                        "Nested", new PhysicalSlots.Snapshot(27, List.of(new PhysicalSlots.Slot(0, 1, shulker)))));
        assertEquals("raid a", KitTemplate.nameKey("RÁID_Å"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KitTemplate("*&", template("Raid", 1).layout()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KitTemplate("Raid\n", template("Raid", 1).layout()));
    }
}

package com.porter.wesort.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShulkerUuidTest {
    private static final String V4 = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void canonicalLabelRoundTrips() {
        UUID uuid = UUID.fromString(V4);
        assertEquals("WeSort-" + V4, ShulkerUuid.label(uuid));
        assertEquals(uuid, ShulkerUuid.parse(ShulkerUuid.label(uuid)));
        assertTrue(ShulkerUuid.hasUuid("WeSort-" + V4));
    }

    @Test
    void automaticRepackingRequiresValidIdentity() {
        assertFalse(ShulkerUuid.repackableName(null));
        assertTrue(ShulkerUuid.repackableName("WeSort-" + V4));
        assertFalse(ShulkerUuid.repackableName("My private stash"));
        assertFalse(ShulkerUuid.repackableName("WeSort-not-a-valid-uuid"));
        assertFalse(ShulkerUuid.repackableName("WeSort-" + V4.toUpperCase()));
    }

    @Test
    void automaticNamingCoversAllUnregisteredNamesAndDuplicates() {
        var duplicates = java.util.Set.of(V4);
        assertTrue(ShulkerUuid.needsAutomaticName(null, duplicates));
        assertTrue(ShulkerUuid.needsAutomaticName("WeSort-" + V4, duplicates));
        assertFalse(ShulkerUuid.needsAutomaticName("WeSort-" + V4, java.util.Set.of()));
        assertTrue(ShulkerUuid.needsAutomaticName("WeSort-not-a-uuid", duplicates));
        assertTrue(ShulkerUuid.needsAutomaticName("My supplies", duplicates));
    }

    @Test
    void rejectsWrongLengthPrefixAndCase() {
        assertNull(ShulkerUuid.parse(null));
        assertNull(ShulkerUuid.parse(""));
        assertNull(ShulkerUuid.parse("WeSort-" + V4 + "x"));
        assertNull(ShulkerUuid.parse("WeSort-" + V4.substring(1)));
        assertNull(ShulkerUuid.parse("WESORT-" + V4));
        assertNull(ShulkerUuid.parse("WeSort-" + V4.toUpperCase()));
        assertNull(ShulkerUuid.parse(V4));
        assertNull(ShulkerUuid.parse("WeSort-" + V4 + " "));
    }

    @Test
    void rejectsNonV4AndNonCanonicalUuids() {
        // Version nibble is not 4.
        assertNull(ShulkerUuid.parse("WeSort-550e8400-e29b-11d4-a716-446655440000"));
        // Variant nibble is not RFC 4122 (8/9/a/b).
        assertNull(ShulkerUuid.parse("WeSort-550e8400-e29b-41d4-1716-446655440000"));
        // Braced or urn forms are never canonical.
        assertNull(ShulkerUuid.parse("WeSort-{550e8400-e29b-41d4-a716-446655440000}"));
        assertNull(ShulkerUuid.parse("WeSort-urn:uuid:" + V4));
        // A non-UUID name is not a UUID.
        assertNull(ShulkerUuid.parse("WeSort-not-a-uuid-at-all-but-long-enough!!"));
    }

    @Test
    void extractsPlainCustomNameFromSnbtPatch() {
        String patch = "{\"minecraft:custom_name\":\"WeSort-" + V4 + "\"}";
        assertEquals("WeSort-" + V4, ShulkerUuid.customName(patch));
        assertEquals(UUID.fromString(V4), ShulkerUuid.boxUuid(patch));
        assertEquals(
                UUID.fromString(V4), ShulkerUuid.boxUuid("{\"minecraft:custom_name\":{text:\"WeSort-" + V4 + "\"}}"));
    }

    @Test
    void ignoresMissingStyledOrForeignNames() {
        assertNull(ShulkerUuid.customName("{}"));
        assertEquals("Beställning", ShulkerUuid.customName("{\"minecraft:custom_name\":\"Beställning\"}"));
        assertNull(ShulkerUuid.boxUuid("{\"minecraft:custom_name\":\"Beställning\"}"));
        assertNull(ShulkerUuid.boxUuid("{\"minecraft:custom_name\":{text:\"WeSort-" + V4 + "\",color:\"red\"}}"));
        assertNull(ShulkerUuid.boxUuid("{\"minecraft:custom_name\":[\"WeSort-\",\"" + V4 + "\"]}"));
        assertNull(ShulkerUuid.boxUuid("{\"minecraft:item_name\":\"WeSort-" + V4 + "\"}"));
        assertNull(ShulkerUuid.customName("{\"minecraft:custom_name\":{}}"));
    }

    @Test
    void identityJsonIsSyntacticOnly() {
        var plain = ShulkerUuid.identity("{\"minecraft:custom_name\":\"WeSort-" + V4 + "\"}");
        assertTrue(plain.get("hasUUID").getAsBoolean());
        assertEquals(V4, plain.get("boxUUID").getAsString());
        var foreign = ShulkerUuid.identity("{\"minecraft:custom_name\":\"Other\"}");
        assertFalse(foreign.get("hasUUID").getAsBoolean());
        assertFalse(foreign.has("boxUUID"));
    }
}

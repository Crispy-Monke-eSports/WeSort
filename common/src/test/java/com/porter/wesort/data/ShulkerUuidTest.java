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
    void automaticRepackingDoesNotDependOnSignsButProtectsHumanLabels() {
        assertTrue(ShulkerUuid.repackableName(null)); // unnamed spare shulkers
        assertTrue(ShulkerUuid.repackableName("WeSort-" + V4));
        assertFalse(ShulkerUuid.repackableName("My private stash"));
        assertFalse(ShulkerUuid.repackableName("WeSort-not-a-valid-uuid"));
        assertFalse(ShulkerUuid.repackableName("WeSort-" + V4.toUpperCase()));
    }

    @Test
    void automaticNamingPreservesHumanLabelsAndUniqueUuid() {
        var duplicates = java.util.Set.of(V4);
        assertTrue(ShulkerUuid.needsAutomaticName(null, duplicates));
        assertTrue(ShulkerUuid.needsAutomaticName("WeSort-" + V4, duplicates));
        assertFalse(ShulkerUuid.needsAutomaticName("WeSort-" + V4, java.util.Set.of()));
        assertFalse(ShulkerUuid.needsAutomaticName("WeSort-not-a-uuid", duplicates));
        assertFalse(ShulkerUuid.needsAutomaticName("My supplies", duplicates));
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
    void extractsPlainCustomNameFromTypedNbtPatch() {
        String patch = "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"WeSort-" + V4
                + "\\\"\"}}}";
        assertEquals("WeSort-" + V4, ShulkerUuid.customName(patch));
        assertEquals(UUID.fromString(V4), ShulkerUuid.boxUuid(patch));
    }

    @Test
    void extractsUuidFromCanonicalSnbt() {
        String snbt = "{id:\"minecraft:shulker_box\",components:{\"minecraft:custom_name\":\"WeSort-" + V4 + "\"}}";
        assertEquals(UUID.fromString(V4), ShulkerUuid.boxUuidFromSnbt(snbt));
        assertNull(ShulkerUuid.boxUuidFromSnbt("{id:\"minecraft:stone\",components:{}}"));
        assertNull(ShulkerUuid.boxUuidFromSnbt(
                "{id:\"minecraft:shulker_box\",components:{\"minecraft:custom_name\":\"Beställning\"}}"));
        assertNull(ShulkerUuid.boxUuidFromSnbt("not snbt {{{"));
    }

    @Test
    void ignoresMissingStyledOrForeignNames() {
        assertNull(ShulkerUuid.customName("{\"type\":10,\"value\":{}}"));
        // A foreign literal is a name, not a UUID.
        assertEquals(
                "Beställning",
                ShulkerUuid.customName(
                        "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"Beställning\\\"\"}}}"));
        assertNull(ShulkerUuid.boxUuid(
                "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"Beställning\\\"\"}}}"));
        // A styled component object is not a canonical WeSort label.
        assertNull(ShulkerUuid.boxUuid(
                "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"{\\\"text\\\":\\\"WeSort-"
                        + V4 + "\\\",\\\"color\\\":\\\"red\\\"}\"}}}"));
        // Wrong tag type (e.g. a nested compound) is not a plain name.
        assertNull(ShulkerUuid.customName(
                "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":10,\"value\":{}}}}"));
    }

    @Test
    void identityJsonIsSyntacticOnly() {
        var plain = ShulkerUuid.identity(
                "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"WeSort-" + V4
                        + "\\\"\"}}}");
        assertTrue(plain.get("hasUUID").getAsBoolean());
        assertEquals(V4, plain.get("boxUUID").getAsString());
        var foreign = ShulkerUuid.identity(
                "{\"type\":10,\"value\":{\"minecraft:custom_name\":{\"type\":8,\"value\":\"\\\"Other\\\"\"}}}");
        assertFalse(foreign.get("hasUUID").getAsBoolean());
        assertFalse(foreign.has("boxUUID"));
    }
}

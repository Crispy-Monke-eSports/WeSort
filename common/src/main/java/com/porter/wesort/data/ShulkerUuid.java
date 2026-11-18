package com.porter.wesort.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/**
 * The physical box identity W-063: a shulker whose anvil {@code CUSTOM_NAME} is exactly
 * {@code "WeSort-<canonical UUID v4>"}, e.g. {@code WeSort-550e8400-e29b-41d4-a716-446655440000}
 * (43 characters). The name is visible inventory metadata, never a Minecraft-native identity:
 * syntactic validity here does not promise that the UUID is unique, registered or safe. Uniqueness
 * and lifecycle live in the Manager registry (StockLedger).
 */
public final class ShulkerUuid {
    public static final String PREFIX = "WeSort-";
    public static final String CUSTOM_NAME = "minecraft:custom_name";
    public static final int NAME_LENGTH = PREFIX.length() + 36;

    private ShulkerUuid() {}

    /** The exact anvil label for a canonical UUID. */
    public static String label(UUID uuid) {
        return PREFIX + uuid;
    }

    /**
     * Strict parse of an exact {@code "WeSort-<canonical uuid v4>"} label, or {@code null} for anything
     * else: wrong length, missing prefix, malformed UUID, uppercase, non-canonical digits, wrong version
     * or a non-RFC-4122 variant. No whitespace or control characters are trimmed.
     */
    public static UUID parse(String name) {
        if (name == null || name.length() != NAME_LENGTH || !name.startsWith(PREFIX)) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(name.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (!name.equals(label(uuid)) || uuid.version() != 4 || uuid.variant() != 2) return null;
        return uuid;
    }

    public static boolean hasUuid(String name) {
        return parse(name) != null;
    }

    /**
     * The plain {@code CUSTOM_NAME} text carried by a typed-NBT component patch (the transport produced
     * by {@code DataComponentPatch.CODEC}), or {@code null}. Only a bare plain-text component counts; a
     * styled/translated/compound name is not a canonical WeSort label.
     */
    public static String customName(String componentsJson) {
        if (componentsJson == null || componentsJson.isBlank()) return null;
        try {
            var root = JsonParser.parseString(componentsJson);
            if (!root.isJsonObject()) return null;
            var values = root.getAsJsonObject().get("value");
            if (values == null || !values.isJsonObject()) return null;
            var custom = values.getAsJsonObject().get(CUSTOM_NAME);
            if (custom == null || !custom.isJsonObject()) return null;
            var node = custom.getAsJsonObject();
            if (!node.has("type") || node.get("type").getAsInt() != 8) return null; // StringTag
            return plainText(node.get("value").getAsString());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** The UUID of a box whose component patch carries an exact WeSort-UUID name, or {@code null}. */
    public static UUID boxUuid(String componentsJson) {
        String name = customName(componentsJson);
        return name == null ? null : parse(name);
    }

    public static JsonObject identity(String componentsJson) {
        String name = customName(componentsJson);
        UUID uuid = name == null ? null : parse(name);
        var out = new JsonObject();
        out.addProperty("hasUUID", uuid != null);
        if (uuid != null) out.addProperty("boxUUID", uuid.toString());
        return out;
    }

    private static String plainText(String componentJson) {
        if (componentJson == null) return null;
        try {
            var element = JsonParser.parseString(componentJson);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
            return element.getAsString();
        } catch (RuntimeException invalid) {
            return null;
        }
    }
}

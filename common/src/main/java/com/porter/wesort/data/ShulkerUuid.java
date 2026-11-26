package com.porter.wesort.data;

import com.google.gson.JsonObject;
import com.porter.wesort.snbt.SnbtCodec;
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

    /** Every managed box without a valid identity needs intake naming. */
    public static boolean needsAutomaticName(String name, java.util.Set<String> duplicates) {
        UUID uuid = parse(name);
        return uuid == null || (duplicates != null && duplicates.contains(uuid.toString()));
    }

    /** Repacking must not bypass intake naming. */
    public static boolean repackableName(String name) {
        return hasUuid(name);
    }

    /** Only a literal native custom_name qualifies; no tooltip or rich-text resolution. */
    public static String customName(String componentsSnbt) {
        if (componentsSnbt == null || componentsSnbt.isBlank()) return null;
        try {
            var root = SnbtCodec.compound(componentsSnbt);
            var custom = root.get(CUSTOM_NAME);
            if (custom instanceof SnbtCodec.TString text) return text.value();
            if (custom instanceof SnbtCodec.TCompound literal
                    && literal.value().size() == 1
                    && literal.get("text") instanceof SnbtCodec.TString text) return text.value();
            return null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** The UUID of a box whose component patch carries an exact WeSort-UUID name, or {@code null}. */
    public static UUID boxUuid(String componentsSnbt) {
        String name = customName(componentsSnbt);
        return name == null ? null : parse(name);
    }

    public static UUID boxUuidFromSnbt(String snbtItem) {
        try {
            var components = SnbtCodec.compound(snbtItem).get("components");
            return components instanceof SnbtCodec.TCompound patch ? boxUuid(SnbtCodec.write(patch)) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public static JsonObject identity(String componentsSnbt) {
        String name = customName(componentsSnbt);
        UUID uuid = name == null ? null : parse(name);
        var out = new JsonObject();
        out.addProperty("hasUUID", uuid != null);
        if (uuid != null) out.addProperty("boxUUID", uuid.toString());
        return out;
    }
}

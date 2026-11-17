package com.porter.wesort.compat;

import com.google.gson.*;
import com.porter.wesort.data.CanonicalJson;
import net.minecraft.nbt.*;

/** Compound order is irrelevant; NBT numeric types, slot order and arrays are significant. */
public final class NbtFingerprint {
    private NbtFingerprint() {}
    public static String encode(Tag tag) { return CanonicalJson.stringify(typed(tag)); }
    /** Typed component transport for new WeSort kit templates; numeric NBT types remain significant. */
    public static Tag decode(JsonElement value) { return decode(value, 0); }
    private static Tag decode(JsonElement value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Typed NBT exceeds template nesting limit");
        var node = value.getAsJsonObject(); int type = node.get("type").getAsBigDecimal().intValueExact();
        if (type == 10) {
            var out = new CompoundTag();
            node.getAsJsonObject("value").entrySet().forEach(entry -> out.put(entry.getKey(), decode(entry.getValue(), depth+1))); return out;
        }
        if (type == 9) {
            var out = new ListTag(); node.getAsJsonArray("value").forEach(item -> out.add(decode(item, depth+1))); return out;
        }
        try {
            Tag tag = TagParser.create(NbtOps.INSTANCE).parseFully(node.get("value").getAsString());
            if (tag.getId() != type || type <= 0 || type > 12) throw new IllegalArgumentException("Typed NBT type mismatch");
            return tag;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalArgumentException("Invalid typed NBT in template", failure); }
    }
    private static JsonElement typed(Tag tag) {
        JsonObject out = new JsonObject(); out.addProperty("type", tag.getId());
        if (tag instanceof CompoundTag compound) {
            JsonObject values = new JsonObject();
            compound.keySet().forEach(key -> values.add(key, typed(compound.get(key)))); out.add("value", values);
        } else if (tag instanceof ListTag list) {
            JsonArray values = new JsonArray(); list.forEach(value -> values.add(typed(value))); out.add("value", values);
        } else out.addProperty("value", tag.toString());
        return out;
    }
}

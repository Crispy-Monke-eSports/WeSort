package com.porter.wesort.compat;

import com.google.gson.*;
import com.porter.wesort.data.CanonicalJson;
import net.minecraft.nbt.*;

/** Compound order is irrelevant; NBT numeric types, slot order and arrays are significant. */
public final class NbtFingerprint {
    private NbtFingerprint() {}
    public static String encode(Tag tag) { return CanonicalJson.stringify(typed(tag)); }
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
